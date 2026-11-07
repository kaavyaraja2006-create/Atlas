package com.atlas.recovery;

import com.atlas.audit.ChangeRecorder;
import com.atlas.common.db.DocumentKind;
import com.atlas.common.db.DocumentNumbers;
import com.atlas.common.error.ApiException;
import com.atlas.common.json.Json;
import com.atlas.common.tenancy.Permissions;
import com.atlas.common.tenancy.TenantContext;
import com.atlas.common.web.PageQuery;
import com.atlas.common.web.PageResponse;
import com.atlas.recovery.engine.Classification;
import com.atlas.recovery.engine.ImpactReport;
import com.atlas.recovery.engine.RecoveryPlan;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opens incidents, runs PASCAR impact analysis on the live provenance graph,
 * and stores immutable recovery plans. Nothing here changes business data;
 * approval and execution are in {@link ExecutionService}.
 */
@Service
class RecoveryService {

    static final String INCIDENT = "recovery_incident";
    static final String PLAN = "recovery_plan";

    record ImpactView(UUID incidentId, ImpactReport report, Map<Classification, Long> counts) {
    }

    record PlanView(RecoveryRepository.PlanRow plan) {
    }

    private final RecoveryRepository recovery;
    private final DocumentNumbers numbers;
    private final ChangeRecorder changes;
    private final Json json;
    private final IncidentAnalysis analysis;
    private final ExecutionRepository execution;

    RecoveryService(RecoveryRepository recovery, DocumentNumbers numbers, ChangeRecorder changes, Json json,
                    IncidentAnalysis analysis, ExecutionRepository execution) {
        this.recovery = recovery;
        this.numbers = numbers;
        this.changes = changes;
        this.json = json;
        this.analysis = analysis;
        this.execution = execution;
    }

    @Transactional
    RecoveryRepository.Incident open(TenantContext tenant, UUID changeEventId, String title, String reason) {
        tenant.require(Permissions.RECOVERY_CREATE);
        RecoveryRepository.ChangeEventRow event = recovery.changeEvent(tenant.organizationId(), changeEventId)
                .orElseThrow(() -> ApiException.unprocessable("changeEventId does not refer to a change in this organization."));
        if (!"UPDATE".equals(event.operation())) {
            throw ApiException.unprocessable("Only an UPDATE can be declared faulty; this change is " + event.operation() + ".");
        }
        RecoveryRepository.Incident incident;
        try {
            incident = recovery.insertIncident(tenant.organizationId(), numbers.next(tenant.organizationId(), DocumentKind.INCIDENT),
                    changeEventId, title.trim(), reason.trim(), tenant.userId());
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("An open incident already exists for this change.");
        }
        changes.recordInsert(tenant.organizationId(), tenant.actor(), INCIDENT, incident.id(), incidentSnapshot(incident),
                "incident opened");
        return incident;
    }

    @Transactional(readOnly = true)
    RecoveryRepository.Incident get(TenantContext tenant, UUID id) {
        tenant.require(Permissions.AUDIT_READ);
        return recovery.incident(tenant.organizationId(), id).orElseThrow(() -> ApiException.notFound("Incident"));
    }

    @Transactional(readOnly = true)
    PageResponse<RecoveryRepository.Incident> list(TenantContext tenant, PageQuery page) {
        tenant.require(Permissions.AUDIT_READ);
        return PageResponse.of(recovery.incidents(tenant.organizationId(), page.size(), page.offset()), page,
                recovery.countIncidents(tenant.organizationId()));
    }

    /** Read-only analysis of the current data; one snapshot for both loader queries; stores nothing. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    ImpactView impact(TenantContext tenant, UUID incidentId) {
        tenant.require(Permissions.AUDIT_READ);
        RecoveryRepository.Incident incident = get(tenant, incidentId);
        IncidentAnalysis.Result a = analysis.analyse(tenant.organizationId(), incident.faultyChangeEventId());
        return new ImpactView(incident.id(), a.report(), a.report().counts());
    }

    /**
     * Generates a new plan from a fresh analysis. REPEATABLE READ makes the
     * edge and version queries, and so the fingerprint, describe one
     * consistent snapshot; a concurrent change surfaces as a serialization
     * failure (409, retry) rather than a plan built on mixed data. Any
     * earlier proposed or approved plan for the incident is superseded.
     * After a partial execution (incident back to OPEN) the new plan covers
     * only what is left: repaired records are no longer affected, and
     * credits already issued for the incident are deducted.
     */
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    RecoveryRepository.PlanRow plan(TenantContext tenant, UUID incidentId) {
        tenant.require(Permissions.RECOVERY_CREATE);
        RecoveryRepository.Incident incident = recovery.incidentForUpdate(tenant.organizationId(), incidentId)
                .orElseThrow(() -> ApiException.notFound("Incident"));
        if (!incident.status().equals("OPEN") && !incident.status().equals("PLANNED") && !incident.status().equals("APPROVED")) {
            throw ApiException.unprocessable("Incident " + incident.incidentNumber() + " is " + incident.status() + ".");
        }
        if (execution.anyExecuting(tenant.organizationId(), incidentId)) {
            throw ApiException.conflict("A plan for this incident is executing; wait for it to finish.");
        }
        IncidentAnalysis.Result a = analysis.analyse(tenant.organizationId(), incident.faultyChangeEventId());
        RecoveryPlan plan = analysis.plan(tenant.organizationId(), incidentId, a);

        recovery.supersedePlans(tenant.organizationId(), incidentId);
        int number = recovery.nextPlanNumber(tenant.organizationId(), incidentId);
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("counts", a.report().counts());
        impact.put("traversedEdges", a.report().traversedEdges());
        impact.put("records", a.report().records());
        RecoveryRepository.PlanRow row = recovery.insertPlan(tenant.organizationId(), incidentId, number, plan.fingerprint(),
                plan.risk().name(), plan.requiresApproval(), plan.steps().size(), (int) plan.manualSteps(),
                json.write(impact), json.write(plan), tenant.userId());

        Map<String, Object> planSnapshot = new LinkedHashMap<>();
        planSnapshot.put("incident_id", incidentId.toString());
        planSnapshot.put("plan_number", number);
        planSnapshot.put("fingerprint", plan.fingerprint());
        planSnapshot.put("risk", plan.risk().name());
        planSnapshot.put("step_count", plan.steps().size());
        planSnapshot.put("status", row.status());
        changes.recordInsert(tenant.organizationId(), tenant.actor(), PLAN, row.id(), planSnapshot,
                "plan " + number + " generated");
        moveIncident(tenant, incident, "PLANNED", "plan " + number + " proposed");
        return row;
    }

    @Transactional(readOnly = true)
    RecoveryRepository.PlanRow getPlan(TenantContext tenant, UUID planId) {
        tenant.require(Permissions.AUDIT_READ);
        return recovery.plan(tenant.organizationId(), planId).orElseThrow(() -> ApiException.notFound("Plan"));
    }

    @Transactional(readOnly = true)
    List<RecoveryRepository.PlanRow> plans(TenantContext tenant, UUID incidentId) {
        get(tenant, incidentId);
        return recovery.plans(tenant.organizationId(), incidentId);
    }

    /** Moves the incident to a new status (no-op if already there) and records it. Needs a transaction. */
    void moveIncident(TenantContext tenant, RecoveryRepository.Incident incident, String status, String why) {
        if (incident.status().equals(status)) {
            return;
        }
        long v = recovery.setIncidentStatus(tenant.organizationId(), incident.id(), status);
        Map<String, Object> after = incidentSnapshot(incident);
        after.put("status", status);
        changes.recordUpdate(tenant.organizationId(), tenant.actor(), INCIDENT, incident.id(), v,
                incidentSnapshot(incident), after, why);
    }

    static Map<String, Object> incidentSnapshot(RecoveryRepository.Incident i) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("incident_number", i.incidentNumber());
        s.put("faulty_change_event_id", i.faultyChangeEventId().toString());
        s.put("title", i.title());
        s.put("reason", i.reason());
        s.put("status", i.status());
        return s;
    }
}
