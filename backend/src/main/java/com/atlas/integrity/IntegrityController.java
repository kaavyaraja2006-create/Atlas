package com.atlas.integrity;

import com.atlas.common.tenancy.TenantContext;
import com.atlas.common.web.ETags;
import com.atlas.common.web.PageQuery;
import com.atlas.common.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Continuous integrity monitoring (ATLAS X, Milestone 6). */
@RestController
@RequestMapping("/api/v1/integrity")
class IntegrityController {

    private static final String SEVERITY = "LOW|MEDIUM|HIGH|CRITICAL";

    record CreateRuleRequest(@NotBlank @Size(max = 40) String kind,
                             @Size(max = 64) @Pattern(regexp = "^[A-Z][A-Z0-9_]*$") String code,
                             @Size(max = 200) String name, @Pattern(regexp = SEVERITY) String severity, Boolean enabled,
                             Map<String, Object> config) {
    }

    record UpdateRuleRequest(@NotBlank @Size(max = 200) String name, @NotNull @Pattern(regexp = SEVERITY) String severity,
                             @NotNull Boolean enabled, Map<String, Object> config) {
    }

    record TransitionRequest(@NotNull @Pattern(regexp = "ACKNOWLEDGED|RESOLVED") String status,
                             @NotBlank @Size(min = 3, max = 1000) String note) {
    }

    private final IntegrityService service;

    IntegrityController(IntegrityService service) {
        this.service = service;
    }

    @GetMapping("/rule-kinds")
    List<IntegrityService.RuleKind> kinds(TenantContext tenant) {
        return service.kinds(tenant);
    }

    @GetMapping("/rules")
    List<IntegrityRepository.Rule> rules(TenantContext tenant) {
        return service.rules(tenant);
    }

    @PostMapping("/rules")
    @ResponseStatus(HttpStatus.CREATED)
    IntegrityRepository.Rule create(TenantContext tenant, @Valid @RequestBody CreateRuleRequest r) {
        return service.create(tenant, r.kind(), r.code(), r.name(), r.severity(), r.enabled(), r.config());
    }

    @PutMapping("/rules/{id}")
    ResponseEntity<IntegrityRepository.Rule> update(TenantContext tenant, @PathVariable UUID id,
                                                    @RequestHeader(name = "If-Match", required = false) String ifMatch,
                                                    @Valid @RequestBody UpdateRuleRequest r) {
        IntegrityRepository.Rule rule = service.update(tenant, id, ETags.requireVersion(ifMatch), r.name(), r.severity(),
                r.enabled(), r.config());
        return ResponseEntity.ok().eTag(ETags.of(rule.version())).body(rule);
    }

    /** Every version of a rule, newest first. */
    @GetMapping("/rules/{id}/versions")
    List<IntegrityRepository.RuleVersion> versions(TenantContext tenant, @PathVariable UUID id) {
        return service.versions(tenant, id);
    }

    /** Runs every enabled rule now. Opens, refreshes or clears incidents; never changes business records. */
    @PostMapping("/checks")
    @ResponseStatus(HttpStatus.CREATED)
    IntegrityService.CheckResult check(TenantContext tenant) {
        return service.runManual(tenant);
    }

    @GetMapping("/checks")
    List<IntegrityRepository.Run> checks(TenantContext tenant) {
        return service.runs(tenant);
    }

    @GetMapping("/incidents")
    PageResponse<IntegrityRepository.Incident> incidents(TenantContext tenant,
                                                         @RequestParam(required = false) String status,
                                                         @RequestParam(required = false) String severity,
                                                         @RequestParam(required = false) Integer page,
                                                         @RequestParam(required = false) Integer size) {
        if (status != null && !status.matches("OPEN|ACKNOWLEDGED|RESOLVED|CLEARED")) {
            throw com.atlas.common.error.ApiException.badRequest("status must be OPEN, ACKNOWLEDGED, RESOLVED or CLEARED");
        }
        if (severity != null && !severity.matches(SEVERITY)) {
            throw com.atlas.common.error.ApiException.badRequest("severity must be LOW, MEDIUM, HIGH or CRITICAL");
        }
        // Ordered by severity, then most recently seen: one fixed order, so no sort parameter.
        PageQuery q = PageQuery.of(page, size, null, Map.of("lastSeenAt", "last_seen_at"), "lastSeenAt,desc");
        return PageResponse.of(service.incidents(tenant, status, severity, q.size(), q.offset()), q,
                service.count(tenant, status, severity));
    }

    @GetMapping("/incidents/{id}")
    IntegrityService.IncidentDetail incident(TenantContext tenant, @PathVariable UUID id) {
        return service.incident(tenant, id);
    }

    @PostMapping("/incidents/{id}/transitions")
    IntegrityService.IncidentDetail transition(TenantContext tenant, @PathVariable UUID id,
                                               @RequestHeader(name = "If-Match", required = false) String ifMatch,
                                               @Valid @RequestBody TransitionRequest r) {
        return service.transition(tenant, id, ETags.requireVersion(ifMatch), r.status(), r.note().strip());
    }
}
