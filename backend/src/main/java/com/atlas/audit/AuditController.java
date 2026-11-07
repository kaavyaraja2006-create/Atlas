package com.atlas.audit;

import com.atlas.common.error.ApiException;
import com.atlas.common.web.PageQuery;
import com.atlas.common.web.PageResponse;
import com.atlas.common.tenancy.Permissions;
import com.atlas.common.tenancy.TenantContext;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit")
class AuditController {

    private final AuditRepository audit;
    private final Anchoring anchoring;

    AuditController(AuditRepository audit, Anchoring anchoring) {
        this.audit = audit;
        this.anchoring = anchoring;
    }

    record ChainVerification(boolean intact, Long firstBrokenIndex, long eventCount) {
    }

    @GetMapping("/events")
    PageResponse<AuditEvent> list(TenantContext tenant,
                                  @RequestParam(required = false) String entityType,
                                  @RequestParam(required = false) UUID entityId,
                                  @RequestParam(required = false) UUID actorUserId,
                                  @RequestParam(required = false) UUID txnId,
                                  @RequestParam(required = false)
                                  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
                                  @RequestParam(required = false)
                                  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
                                  @RequestParam(required = false) Integer page,
                                  @RequestParam(required = false) Integer size) {
        tenant.require(Permissions.AUDIT_READ);
        // Newest first by time, ties by chain position (one index serves every filter; V8).
        PageQuery query = PageQuery.of(page, size, null, Map.of("chainIndex", "chain_index"), "chainIndex,desc");
        var filter = new AuditRepository.Filter(entityType, entityId, actorUserId, txnId, from, to);
        return PageResponse.of(
                audit.list(tenant.organizationId(), filter, query.size(), query.offset()),
                query,
                audit.count(tenant.organizationId(), filter));
    }

    @GetMapping("/events/{id}")
    AuditEvent get(TenantContext tenant, @PathVariable UUID id) {
        tenant.require(Permissions.AUDIT_READ);
        return audit.find(tenant.organizationId(), id).orElseThrow(() -> ApiException.notFound("Audit event"));
    }

    /** Recomputes this organization's hash chain from the first event. */
    @PostMapping("/verify")
    ChainVerification verify(TenantContext tenant) {
        tenant.require(Permissions.AUDIT_READ);
        AuditRepository.ChainCheck check = audit.verify(tenant.organizationId());
        return new ChainVerification(check.firstBrokenIndex() == null, check.firstBrokenIndex(), check.eventCount());
    }

    /**
     * Checks the chain against its externally stored, signed anchors, recomputing
     * from event content. Unlike /verify this detects a rewrite that recomputed
     * every hash, for everything up to the latest anchor.
     */
    @GetMapping("/anchors/verification")
    Anchoring.Verification verifyAnchors(TenantContext tenant) {
        tenant.require(Permissions.AUDIT_READ);
        return anchoring.verify(tenant.organizationId());
    }
}
