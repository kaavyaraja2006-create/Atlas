package com.atlas.recovery;

import com.atlas.provenance.VersionRef;
import com.atlas.recovery.engine.EntityKey;
import com.atlas.recovery.engine.FaultyChange;
import com.atlas.recovery.engine.ImpactAnalyzer;
import com.atlas.recovery.engine.ImpactReport;
import com.atlas.recovery.engine.IncidentGraph;
import com.atlas.recovery.engine.PlanGenerator;
import com.atlas.recovery.engine.RecoveryPlan;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Loads an incident's graph and runs PASCAR on it. Shared by plan generation,
 * the stale-plan check and the verifier's re-plan, so all three see the data
 * the same way and count credits already issued for the incident.
 */
@Component
class IncidentAnalysis {

    record Result(FaultyChange faulty, IncidentGraph graph, ImpactReport report) {
    }

    private final RecoveryRepository recovery;
    private final ExecutionRepository execution;
    private final ImpactAnalyzer analyzer = new ImpactAnalyzer();
    private final PlanGenerator planner = new PlanGenerator();

    IncidentAnalysis(RecoveryRepository recovery, ExecutionRepository execution) {
        this.recovery = recovery;
        this.execution = execution;
    }

    Result analyse(UUID orgId, UUID faultyChangeEventId) {
        RecoveryRepository.ChangeEventRow event = recovery.changeEvent(orgId, faultyChangeEventId)
                .orElseThrow(() -> new IllegalStateException("Incident points at a missing change event"));
        VersionRef ref = new VersionRef(event.entityType(), event.entityId(), event.version());
        FaultyChange faulty = new FaultyChange(ref, new HashSet<>(event.changedFields()), event.before(), event.after());
        IncidentGraph graph = recovery.loadGraph(orgId, ref);
        return new Result(faulty, graph, analyzer.analyze(faulty, graph));
    }

    RecoveryPlan plan(UUID orgId, UUID incidentId, Result analysis) {
        Map<EntityKey, BigDecimal> credited = execution.creditedByIncident(orgId, incidentId);
        return planner.plan(analysis.report(), analysis.graph(), credited);
    }
}
