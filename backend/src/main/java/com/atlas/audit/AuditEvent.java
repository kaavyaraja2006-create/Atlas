package com.atlas.audit;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One recorded change, as returned by the audit API. */
public record AuditEvent(
        UUID id,
        long chainIndex,
        UUID txnId,
        String actorKind,
        UUID actorUserId,
        String entityType,
        UUID entityId,
        long entityVersion,
        String operation,
        List<String> changedFields,
        JsonNode before,
        JsonNode after,
        String reason,
        String correlationId,
        Instant occurredAt,
        String hash,
        String actorName) {
}
