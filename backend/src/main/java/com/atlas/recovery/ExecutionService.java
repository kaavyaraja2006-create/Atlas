package com.atlas.recovery;

import com.atlas.audit.ChangeRecorder;
import com.atlas.common.db.DocumentNumbers;
import com.atlas.common.error.ApiException;
import com.atlas.common.json.Json;
import com.atlas.common.observability.AtlasMetrics;
import com.atlas.common.tenancy.Permissions;
import com.atlas.common.tenancy.TenantContext;
import com.atlas.provenance.ProvenanceRecorder;
import com.atlas.recovery.execution.Decision;
import com.atlas.recovery.execution.ExecutionReport;
import com.atlas.recovery.execution.PlanStatus;
import com.atlas.recovery.execution.RecoveryException;
import com.atlas.recovery.execution.RecoveryWorkflow;
import com.atlas.recovery.execution.StepRecord;
import com.atlas.recovery.execution.Transactions;
import com.atlas.recovery.execution.Verification;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Approval, execution and verification of stored plans. The rules live in
 * {@link RecoveryWorkflow} (framework-free, unit-tested); this class checks
 * permissions, wires the workflow to PostgreSQL for the caller's tenant,
 * keeps the incident's status in step, and maps refusals to HTTP problems.
 *
 * <p>Deliberately not {@code @Transactional}: the workflow opens one short
 * transaction per step, so a long plan never holds locks for its whole run
 * and a crash loses at most the step in flight (which then rolls back).
 */
@Service
class ExecutionService {

    record ExecutionView(UUID planId, String status, List<ExecutionRepository.ActionRow> steps,
                         List<ExecutionRepository.DecisionRow> decisions) {
    }

    private final ExecutionRepository repo;
    private final RecoveryRepository recovery;
    private final RecoveryService incidents;
    private final IncidentAnalysis analysis;
    private final ChangeRecorder changes;
    private final ProvenanceRecorder provenance;
    private final DocumentNumbers numbers;
    private final Json json;
    private final TransactionTemplate tx;
    private final AtlasMetrics metrics;
    private final ConflictService conflicts;

    ExecutionService(ExecutionRepository repo, RecoveryRepository recovery, RecoveryService incidents,
                     IncidentAnalysis analysis, ChangeRecorder changes, ProvenanceRecorder provenance,
                     DocumentNumbers numbers, Json json, PlatformTransactionManager transactions,
                     AtlasMetrics metrics, ConflictService conflicts) {
        this.conflicts = conflicts;
        this.metrics = metrics;
        this.repo = repo;
        this.recovery = recovery;
        this.incidents = incidents;
        this.analysis = analysis;
        this.changes = changes;
        this.provenance = provenance;
        this.numbers = numbers;
        this.json = json;
        this.tx = new TransactionTemplate(transactions);
    }

    Decision approve(TenantContext tenant, UUID planId, String fingerprint, String comment) {
        tenant.require(Permissions.RECOVERY_APPROVE);
        Decision d = run(() -> workflow(tenant).approve(planId, tenant.userId(), fingerprint, comment));
        moveIncident(tenant, planId, "APPROVED", "plan approved");
        return d;
    }

    Decision reject(TenantContext tenant, UUID planId, String comment) {
        tenant.require(Permissions.RECOVERY_APPROVE);
        Decision d = run(() -> workflow(tenant).reject(planId, tenant.userId(), comment));
        moveIncident(tenant, planId, "OPEN", "plan rejected");
        return d;
    }

    ExecutionReport execute(TenantContext tenant, UUID planId) {
        tenant.require(Permissions.RECOVERY_EXECUTE);
        // ATLAS X hard constraint: unresolved conflicts that need a person block automatic recovery.
        recovery.plan(tenant.organizationId(), planId).ifPresent(
                p -> tx.executeWithoutResult(s -> conflicts.requireReviewed(tenant.organizationId(), p.incidentId())));
        ExecutionReport report = run(() -> workflow(tenant).execute(planId, tenant.userId()));
        report.counts().forEach((status, n) -> metrics.recoverySteps(status.name(), n));
        if (report.status() == PlanStatus.FAILED) {
            moveIncident(tenant, planId, "OPEN", "plan execution stopped; generate a new plan for what is left");
        } else {
            moveIncident(tenant, planId, "EXECUTING", "plan executed; awaiting verification");
        }
        return report;
    }

    StepRecord acknowledge(TenantContext tenant, UUID planId, int stepNo, String note) {
        tenant.require(Permissions.RECOVERY_EXECUTE);
        return run(() -> workflow(tenant).acknowledge(planId, stepNo, tenant.userId(), note));
    }

    Verification verify(TenantContext tenant, UUID planId) {
        tenant.require(Permissions.AUDIT_READ);
        Verification v = run(() -> workflow(tenant).verify(planId));
        switch (v.outcome()) {
            case PASSED -> moveIncident(tenant, planId, "RESOLVED", "recovery verified");
            case FAILED -> moveIncident(tenant, planId, "OPEN", "verification failed; generate a new plan");
            case INCOMPLETE -> {
                // manual steps outstanding; the incident stays EXECUTING
            }
        }
        return v;
    }

    ExecutionView execution(TenantContext tenant, UUID planId) {
        tenant.require(Permissions.AUDIT_READ);
        UUID org = tenant.organizationId();
        RecoveryRepository.PlanRow plan = recovery.plan(org, planId).orElseThrow(() -> ApiException.notFound("Plan"));
        return tx.execute(s -> new ExecutionView(planId, plan.status(), repo.actions(org, planId),
                repo.decisions(org, planId)));
    }

    List<ExecutionRepository.VerificationRow> verifications(TenantContext tenant, UUID planId) {
        tenant.require(Permissions.AUDIT_READ);
        recovery.plan(tenant.organizationId(), planId).orElseThrow(() -> ApiException.notFound("Plan"));
        return repo.verifications(tenant.organizationId(), planId);
    }

    // ------------------------------------------------------------------ wiring

    private RecoveryWorkflow workflow(TenantContext tenant) {
        JdbcRecoveryPorts ports = new JdbcRecoveryPorts(tenant.organizationId(), tenant.userId(), repo, analysis,
                changes, provenance, numbers, json);
        Transactions transactions = new Transactions() {
            @Override
            public <T> T inTransaction(Supplier<T> work) {
                return tx.execute(status -> work.get());
            }
        };
        return new RecoveryWorkflow(transactions, ports, ports, ports, ports);
    }

    private static <T> T run(Supplier<T> call) {
        try {
            return call.get();
        } catch (RecoveryException e) {
            throw switch (e.kind()) {
                case NOT_FOUND -> ApiException.notFound("Plan");
                case SELF_APPROVAL -> ApiException.denied(e.getMessage());
                case STALE_PLAN, NOT_APPROVED, ILLEGAL_STATE -> ApiException.conflict(e.getMessage());
                case INVALID -> ApiException.badRequest(e.getMessage());
            };
        }
    }

    /** Keeps the incident's summary status in step with its plan; recorded in the audit log. */
    private void moveIncident(TenantContext tenant, UUID planId, String status, String why) {
        tx.executeWithoutResult(s -> {
            UUID org = tenant.organizationId();
            RecoveryRepository.PlanRow plan = recovery.plan(org, planId).orElseThrow(() -> ApiException.notFound("Plan"));
            RecoveryRepository.Incident incident = recovery.incidentForUpdate(org, plan.incidentId())
                    .orElseThrow(() -> new IllegalStateException("Plan without incident"));
            incidents.moveIncident(tenant, incident, status, why);
        });
    }
}
