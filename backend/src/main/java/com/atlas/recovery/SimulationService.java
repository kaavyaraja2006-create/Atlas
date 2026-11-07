package com.atlas.recovery;

import com.atlas.common.error.ApiException;
import com.atlas.common.tenancy.Permissions;
import com.atlas.common.tenancy.TenantContext;
import com.atlas.provenance.VersionRef;
import com.atlas.recovery.engine.CounterfactualSimulator;
import com.atlas.recovery.engine.EntityKey;
import com.atlas.recovery.engine.FaultyChange;
import com.atlas.recovery.engine.IncidentGraph;
import com.atlas.recovery.engine.RecoveryOptimizer;
import com.atlas.recovery.engine.SimulationInput;
import com.atlas.recovery.engine.StrategyComparison;
import com.atlas.recovery.engine.StrategyResult;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Counterfactual simulation and plan assessment for an incident (ATLAS X, Milestones 4-5).
 *
 * <ol>
 *   <li>Reads one consistent snapshot in a REPEATABLE READ, READ ONLY transaction: the
 *       database itself refuses any write in it.</li>
 *   <li>Runs every strategy on in-memory copies ({@link CounterfactualSimulator} has no
 *       database access), then the optimiser.</li>
 *   <li>Stores the run, its results and the assessment; only these tables are written.</li>
 * </ol>
 */
@Service
@EnableScheduling
class SimulationService {

    private static final Logger log = LoggerFactory.getLogger(SimulationService.class);

    record RunView(SimulationRepository.Run run, List<SimulationRepository.ResultRow> results) {
    }

    record PlanAssessment(UUID planId, String planFingerprint, boolean stale, String staleReason,
                          SimulationRepository.Run simulation) {
    }

    private final RecoveryRepository recovery;
    private final ExecutionRepository execution;
    private final SimulationRepository simulations;
    private final ConflictRepository reviews;
    private final TransactionTemplate snapshotTx;
    private final TransactionTemplate writeTx;
    private final CounterfactualSimulator simulator = new CounterfactualSimulator();
    private final RecoveryOptimizer optimizer = new RecoveryOptimizer();
    private final int retentionDays;

    SimulationService(RecoveryRepository recovery, ExecutionRepository execution, SimulationRepository simulations,
                      ConflictRepository reviews, PlatformTransactionManager transactions,
                      @Value("${atlas.simulation.retention-days:180}") int retentionDays) {
        this.recovery = recovery;
        this.execution = execution;
        this.simulations = simulations;
        this.reviews = reviews;
        this.snapshotTx = new TransactionTemplate(transactions);
        this.snapshotTx.setReadOnly(true);
        this.snapshotTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.writeTx = new TransactionTemplate(transactions);
        this.retentionDays = retentionDays;
    }

    RunView simulate(TenantContext tenant, UUID incidentId) {
        tenant.require(Permissions.RECOVERY_CREATE);
        UUID org = tenant.organizationId();
        RecoveryRepository.Incident incident = recovery.incident(org, incidentId)
                .orElseThrow(() -> ApiException.notFound("Recovery incident"));
        UUID runId;
        try {
            runId = writeTx.execute(s -> simulations.start(org, incidentId, tenant.userId()));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("A simulation of this incident is already running.");
        }
        long t0 = System.nanoTime();
        try {
            Captured c = snapshotTx.execute(s -> capture(org, incidentId, incident.faultyChangeEventId()));
            CounterfactualSimulator.Report report = simulator.simulate(c.input());
            RecoveryOptimizer.Assessment assessment = optimizer.assess(report);
            int ms = (int) ((System.nanoTime() - t0) / 1_000_000);
            writeTx.executeWithoutResult(s -> {
                for (StrategyResult r : report.results()) {
                    simulations.insertResult(org, runId, r, report.recordSets().get(r.strategy()));
                }
                for (StrategyComparison cmp : report.comparisons()) {
                    simulations.insertComparison(org, runId, cmp);
                }
                simulations.complete(org, runId, c.chainIndex(), report.fingerprint(), report.records(), report.versions(),
                        assessment.recommended().map(Enum::name).orElse(null), assessment.risk().name(), assessment, ms);
            });
        } catch (RuntimeException e) {
            int ms = (int) ((System.nanoTime() - t0) / 1_000_000);
            log.warn("Simulation {} of incident {} failed", runId, incidentId, e);
            writeTx.executeWithoutResult(s -> simulations.fail(org, runId,
                    e instanceof IllegalArgumentException ? e.getMessage() : "Simulation failed: " + e.getClass().getSimpleName(), ms));
        }
        return view(org, runId);
    }

    private record Captured(SimulationInput input, long chainIndex) {
    }

    private Captured capture(UUID org, UUID incidentId, UUID faultyEventId) {
        RecoveryRepository.ChangeEventRow event = recovery.changeEvent(org, faultyEventId)
                .orElseThrow(() -> new IllegalStateException("Incident points at a missing change event"));
        VersionRef ref = new VersionRef(event.entityType(), event.entityId(), event.version());
        FaultyChange faulty = new FaultyChange(ref, new HashSet<>(event.changedFields()), event.before(), event.after());
        SimulationRepository.Snapshot snap = simulations.snapshot(org, ref);
        IncidentGraph graph = new IncidentGraph(recovery.loadEdges(org, ref), snap.histories())
                .withGaps(recovery.loadGaps(org, ref));
        Map<EntityKey, BigDecimal> credited = execution.creditedByIncident(org, incidentId);
        Set<String> reviewed = new HashSet<>();
        reviews.latest(org, incidentId).forEach(r -> reviewed.add(r.entityType() + ":" + r.entityId() + "." + r.field()));
        return new Captured(new SimulationInput(faulty, graph, snap.sequence(), credited, reviewed), snap.chainIndex());
    }

    RunView get(TenantContext tenant, UUID runId) {
        tenant.require(Permissions.AUDIT_READ);
        return view(tenant.organizationId(), runId);
    }

    List<SimulationRepository.Run> list(TenantContext tenant, UUID incidentId) {
        tenant.require(Permissions.AUDIT_READ);
        recovery.incident(tenant.organizationId(), incidentId).orElseThrow(() -> ApiException.notFound("Recovery incident"));
        return simulations.runs(tenant.organizationId(), incidentId);
    }

    /** The latest completed simulation for a plan's incident, and whether it still describes the plan. */
    PlanAssessment assessment(TenantContext tenant, UUID planId) {
        tenant.require(Permissions.AUDIT_READ);
        RecoveryRepository.PlanRow plan = recovery.plan(tenant.organizationId(), planId)
                .orElseThrow(() -> ApiException.notFound("Recovery plan"));
        Optional<SimulationRepository.Run> run = simulations.latestCompleted(tenant.organizationId(), plan.incidentId());
        if (run.isEmpty()) {
            return new PlanAssessment(planId, plan.fingerprint(), true, "No simulation has been run for this incident.", null);
        }
        boolean stale = !plan.fingerprint().equals(run.get().fingerprint());
        return new PlanAssessment(planId, plan.fingerprint(), stale,
                stale ? "The simulation read different record versions than this plan: run it again." : null, run.get());
    }

    static final Set<String> CATEGORIES = Set.of("INSPECTED", "PROPOSED", "MODIFIED", "PRESERVED", "CONFLICTING", "UNRESOLVED");

    /**
     * One page of a strategy's stored record details.
     *
     * @param totals         exact counts for the strategy (null for runs before record details existed)
     * @param storedMatching stored details matching {@code category}; when {@code partial}, records of
     *                       this category may also be among the omitted ones
     * @param partial        the strategy's detail list was cut at the limit: these are not complete results
     */
    record RecordsPage(String strategy, SimulationRepository.Details totals, String category, long storedMatching,
                       boolean partial, int page, int size, List<SimulationRepository.RecordRow> items) {
    }

    RecordsPage records(TenantContext tenant, UUID runId, String strategy, String category, int page, int size) {
        tenant.require(Permissions.AUDIT_READ);
        UUID org = tenant.organizationId();
        if (category != null && !CATEGORIES.contains(category)) {
            throw ApiException.badRequest("category must be one of " + new java.util.TreeSet<>(CATEGORIES));
        }
        if (page < 0 || size < 1 || size > 500) {
            throw ApiException.badRequest("page must be ≥ 0 and size between 1 and 500");
        }
        SimulationRepository.ResultRow result = simulations.results(org, runId).stream()
                .filter(r -> r.strategy().equals(strategy)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Simulation strategy"));
        SimulationRepository.Details d = result.details();
        long matching = d == null ? 0 : simulations.countStoredRecords(org, runId, strategy, category);
        List<SimulationRepository.RecordRow> items = d == null ? List.of()
                : simulations.records(org, runId, strategy, category, size, page * size);
        return new RecordsPage(strategy, d, category, matching, d != null && d.truncated(), page, size, items);
    }

    List<SimulationRepository.ComparisonRow> comparisons(TenantContext tenant, UUID runId) {
        tenant.require(Permissions.AUDIT_READ);
        simulations.run(tenant.organizationId(), runId).orElseThrow(() -> ApiException.notFound("Simulation"));
        return simulations.comparisons(tenant.organizationId(), runId);
    }

    SimulationRepository.ComparisonRow comparison(TenantContext tenant, UUID runId, String a, String b) {
        tenant.require(Permissions.AUDIT_READ);
        return simulations.comparison(tenant.organizationId(), runId, a, b)
                .orElseThrow(() -> ApiException.notFound("Strategy comparison"));
    }

    private RunView view(UUID org, UUID runId) {
        SimulationRepository.Run run = simulations.run(org, runId).orElseThrow(() -> ApiException.notFound("Simulation"));
        return new RunView(run, simulations.results(org, runId));
    }

    /** Retention: simulations are operational evidence, deleted after the configured number of days. */
    @Scheduled(cron = "${atlas.simulation.retention-cron:0 17 3 * * *}")
    void deleteOldRuns() {
        int n = writeTx.execute(s -> simulations.deleteOlderThan(retentionDays));
        if (n > 0) {
            log.info("Deleted {} simulation run(s) older than {} days", n, retentionDays);
        }
    }
}
