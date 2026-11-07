package com.atlas.recovery;

import com.atlas.common.tenancy.TenantContext;
import com.atlas.common.web.PageQuery;
import com.atlas.common.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.atlas.recovery.execution.Decision;
import com.atlas.recovery.execution.ExecutionReport;
import com.atlas.recovery.execution.StepRecord;
import com.atlas.recovery.execution.Verification;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/recovery")
class RecoveryController {

    record OpenIncidentRequest(@NotNull UUID changeEventId,
                               @NotBlank @Size(max = 200) String title,
                               @NotBlank @Size(max = 1000) String reason) {
    }

    record ApproveRequest(@NotBlank @Size(max = 80) String fingerprint, @Size(max = 1000) String comment) {
    }

    record RejectRequest(@NotBlank @Size(max = 1000) String comment) {
    }

    record AcknowledgeRequest(@NotBlank @Size(max = 1000) String note) {
    }

    private final RecoveryService service;
    private final ExecutionService execution;

    RecoveryController(RecoveryService service, ExecutionService execution) {
        this.service = service;
        this.execution = execution;
    }

    @PostMapping("/incidents")
    ResponseEntity<RecoveryRepository.Incident> open(TenantContext tenant, @Valid @RequestBody OpenIncidentRequest request) {
        RecoveryRepository.Incident incident = service.open(tenant, request.changeEventId(), request.title(), request.reason());
        return ResponseEntity.created(URI.create("/api/v1/recovery/incidents/" + incident.id())).body(incident);
    }

    @GetMapping("/incidents")
    PageResponse<RecoveryRepository.Incident> list(TenantContext tenant, @RequestParam(required = false) Integer page,
                                                   @RequestParam(required = false) Integer size) {
        return service.list(tenant, PageQuery.of(page, size, null, Map.of("openedAt", "opened_at"), "openedAt,desc"));
    }

    @GetMapping("/incidents/{id}")
    RecoveryRepository.Incident get(TenantContext tenant, @PathVariable UUID id) {
        return service.get(tenant, id);
    }

    /** Impact analysis of the current data. Read-only and repeatable. */
    @PostMapping("/incidents/{id}/impact")
    RecoveryService.ImpactView impact(TenantContext tenant, @PathVariable UUID id) {
        return service.impact(tenant, id);
    }

    @PostMapping("/incidents/{id}/plans")
    ResponseEntity<RecoveryRepository.PlanRow> plan(TenantContext tenant, @PathVariable UUID id) {
        RecoveryRepository.PlanRow plan = service.plan(tenant, id);
        return ResponseEntity.created(URI.create("/api/v1/recovery/plans/" + plan.id())).body(plan);
    }

    @GetMapping("/incidents/{id}/plans")
    List<RecoveryRepository.PlanRow> plans(TenantContext tenant, @PathVariable UUID id) {
        return service.plans(tenant, id);
    }

    @GetMapping("/plans/{id}")
    RecoveryRepository.PlanRow getPlan(TenantContext tenant, @PathVariable UUID id) {
        return service.getPlan(tenant, id);
    }

    /** Approve the plan as seen: the fingerprint shown to the approver must still be the plan's. */
    @PostMapping("/plans/{id}/approval")
    Decision approve(TenantContext tenant, @PathVariable UUID id, @Valid @RequestBody ApproveRequest request) {
        return execution.approve(tenant, id, request.fingerprint(), request.comment());
    }

    @PostMapping("/plans/{id}/rejection")
    Decision reject(TenantContext tenant, @PathVariable UUID id, @Valid @RequestBody RejectRequest request) {
        return execution.reject(tenant, id, request.comment());
    }

    /** Runs the plan, resumes a stopped run, or (if finished) returns the same result again. */
    @PostMapping("/plans/{id}/execution")
    ExecutionReport execute(TenantContext tenant, @PathVariable UUID id) {
        return execution.execute(tenant, id);
    }

    @GetMapping("/plans/{id}/execution")
    ExecutionService.ExecutionView executionOf(TenantContext tenant, @PathVariable UUID id) {
        return execution.execution(tenant, id);
    }

    @PostMapping("/plans/{id}/steps/{stepNo}/acknowledgement")
    StepRecord acknowledge(TenantContext tenant, @PathVariable UUID id, @PathVariable int stepNo,
                           @Valid @RequestBody AcknowledgeRequest request) {
        return execution.acknowledge(tenant, id, stepNo, request.note());
    }

    /** Checks the plan's invariants against the current data and records the result. */
    @PostMapping("/plans/{id}/verifications")
    Verification verify(TenantContext tenant, @PathVariable UUID id) {
        return execution.verify(tenant, id);
    }

    @GetMapping("/plans/{id}/verifications")
    List<ExecutionRepository.VerificationRow> verifications(TenantContext tenant, @PathVariable UUID id) {
        return execution.verifications(tenant, id);
    }
}
