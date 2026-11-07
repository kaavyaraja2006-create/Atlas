package com.atlas.recovery;

import com.atlas.common.error.ApiException;
import com.atlas.common.json.Json;
import com.atlas.common.tenancy.Permissions;
import com.atlas.common.tenancy.TenantContext;
import com.atlas.recovery.engine.ConflictAnalyzer;
import com.atlas.recovery.engine.ConflictKind;
import com.atlas.recovery.engine.EntityKey;
import com.atlas.recovery.engine.FieldConflict;
import com.atlas.recovery.engine.RecoveryPlan;
import com.atlas.recovery.engine.VersionInfo;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Temporal conflict intelligence for an incident (ATLAS X, Milestone 3): computed from the
 * audit history on every call, joined with the latest human review of each conflict.
 * Conflicts that need review and have none block execution (a hard constraint).
 */
@Service
class ConflictService {

    record ConflictView(FieldConflict conflict, ConflictRepository.Review review, boolean open) {
    }

    record IncidentConflicts(UUID incidentId, UUID planId, List<ConflictView> conflicts, long open) {
    }

    private final RecoveryRepository recovery;
    private final IncidentAnalysis analysis;
    private final ConflictRepository reviews;
    private final Json json;
    private final ConflictAnalyzer analyzer = new ConflictAnalyzer();

    ConflictService(RecoveryRepository recovery, IncidentAnalysis analysis, ConflictRepository reviews, Json json) {
        this.recovery = recovery;
        this.analysis = analysis;
        this.reviews = reviews;
        this.json = json;
    }

    @Transactional(readOnly = true)
    IncidentConflicts conflicts(TenantContext tenant, UUID incidentId) {
        tenant.require(Permissions.AUDIT_READ);
        return compute(tenant.organizationId(), incidentId);
    }

    IncidentConflicts compute(UUID orgId, UUID incidentId) {
        RecoveryRepository.Incident incident = recovery.incident(orgId, incidentId)
                .orElseThrow(() -> ApiException.notFound("Recovery incident"));
        IncidentAnalysis.Result r = analysis.analyse(orgId, incident.faultyChangeEventId());
        List<FieldConflict> found = new ArrayList<>(analyzer.analyze(r.faulty(), r.report(), r.graph()));

        // Against the newest plan still waiting: records that moved on since it was made.
        Optional<RecoveryRepository.PlanRow> pending = recovery.plans(orgId, incidentId).stream()
                .filter(p -> p.status().equals("PROPOSED") || p.status().equals("APPROVED"))
                .reduce((a, b) -> b.planNumber() > a.planNumber() ? b : a);
        UUID planId = null;
        if (pending.isPresent()) {
            planId = pending.get().id();
            RecoveryPlan plan = json.read(json.write(pending.get().plan()), RecoveryPlan.class);
            Map<EntityKey, Long> observed = new HashMap<>();
            for (RecoveryPlan.Step step : plan.steps()) {
                r.graph().latest(step.key()).map(VersionInfo::version).ifPresent(v -> observed.put(step.key(), v));
            }
            found.addAll(analyzer.concurrent(plan, observed));
        }

        Map<String, ConflictRepository.Review> latest = new HashMap<>();
        for (ConflictRepository.Review rv : reviews.latest(orgId, incidentId)) {
            latest.put(rv.entityType() + ":" + rv.entityId() + "." + rv.field(), rv);
        }
        List<ConflictView> views = new ArrayList<>();
        long open = 0;
        for (FieldConflict c : found) {
            ConflictRepository.Review rv = latest.get(c.key() + "." + c.field());
            boolean isOpen = c.needsReview() && rv == null;
            open += isOpen ? 1 : 0;
            views.add(new ConflictView(c, rv, isOpen));
        }
        return new IncidentConflicts(incidentId, planId, views, open);
    }

    @Transactional
    ConflictRepository.Review review(TenantContext tenant, UUID incidentId, String entityType, UUID entityId,
                                     String field, String decision, String note) {
        tenant.require(Permissions.RECOVERY_CREATE);
        IncidentConflicts all = compute(tenant.organizationId(), incidentId);
        FieldConflict target = all.conflicts().stream().map(ConflictView::conflict)
                .filter(c -> c.key().equals(new EntityKey(entityType, entityId)) && c.field().equals(field))
                .findFirst()
                .orElseThrow(() -> ApiException.unprocessable(
                        "There is no conflict on " + entityType + ":" + entityId + "." + field + " in this incident."));
        var at = reviews.insert(tenant.organizationId(), incidentId, entityType, entityId, field, target.kind().name(),
                decision, note.strip(), tenant.userId());
        return new ConflictRepository.Review(entityType, entityId, field, target.kind().name(), decision, note.strip(),
                tenant.userId(), null, at);
    }

    /**
     * Hard constraint before execution: every conflict that needs a person has a decision.
     * Concurrent modifications cannot be reviewed away: the stale-plan check rejects them.
     */
    void requireReviewed(UUID orgId, UUID incidentId) {
        IncidentConflicts all = compute(orgId, incidentId);
        List<String> open = all.conflicts().stream().filter(ConflictView::open)
                .filter(v -> v.conflict().kind() != ConflictKind.CONCURRENT_MODIFICATION)
                .map(v -> v.conflict().key() + "." + v.conflict().field() + " (" + v.conflict().kind() + ")")
                .toList();
        if (!open.isEmpty()) {
            throw ApiException.conflict(open.size() + " conflict(s) need a review before this plan can run: "
                    + String.join(", ", open.subList(0, Math.min(5, open.size())))
                    + (open.size() > 5 ? ", …" : "") + ". Review them under the incident's Conflicts tab.");
        }
    }
}
