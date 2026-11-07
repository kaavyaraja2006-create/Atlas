package com.atlas.history;

import com.atlas.common.db.Db;
import com.atlas.common.error.ApiException;
import com.atlas.common.tenancy.Permissions;
import com.atlas.common.tenancy.TenantContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Field-level history of one record (ATLAS X, Milestone 3), read from the field_changes view
 * over change_events (V10): no second copy of the history exists.
 */
@RestController
@RequestMapping("/api/v1/history")
class HistoryController {

    record FieldChange(long chainIndex, UUID changeEventId, String field, Long previousVersion, long newVersion,
                       String previousValue, String newValue, String operation, String actorKind, UUID actorUserId,
                       String actorName, UUID txnId, String reason, String correlationId, Instant occurredAt) {
    }

    /** One version: who changed which fields, when, and why. */
    record TimelineEntry(long version, long chainIndex, String operation, List<String> fields, String actorKind,
                         UUID actorUserId, String actorName, UUID txnId, String reason, Instant occurredAt) {
    }

    static final String FIELD_HISTORY = """
            SELECT f.chain_index, f.change_event_id, f.field, f.previous_version, f.new_version,
                   CAST(f.previous_value AS text) AS previous_value, CAST(f.new_value AS text) AS new_value,
                   f.operation, f.actor_kind, f.actor_user_id, u.display_name AS actor_name, f.txn_id, f.reason,
                   f.correlation_id, f.occurred_at
              FROM field_changes f
              LEFT JOIN users u ON u.id = f.actor_user_id
             WHERE f.organization_id = :orgId AND f.entity_type = :entityType AND f.entity_id = :entityId
               AND (CAST(:field AS text) IS NULL OR f.field = CAST(:field AS text))
             ORDER BY f.new_version, f.field
             LIMIT 2000
            """;

    static final String TIMELINE = """
            SELECT e.entity_version, e.chain_index, e.operation, CAST(to_jsonb(e.changed_fields) AS text) AS fields,
                   e.actor_kind, e.actor_user_id, u.display_name AS actor_name, e.txn_id, e.reason, e.occurred_at
              FROM change_events e
              LEFT JOIN users u ON u.id = e.actor_user_id
             WHERE e.organization_id = :orgId AND e.entity_type = :entityType AND e.entity_id = :entityId
             ORDER BY e.entity_version
             LIMIT 2000
            """;

    private final JdbcClient jdbc;
    private final com.atlas.common.json.Json json;

    HistoryController(JdbcClient jdbc, com.atlas.common.json.Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @GetMapping("/{entityType}/{entityId}/fields")
    List<FieldChange> fields(TenantContext tenant, @PathVariable String entityType, @PathVariable UUID entityId,
                             @RequestParam(required = false) String field) {
        tenant.require(Permissions.AUDIT_READ);
        if (field != null && !field.matches("[a-z][a-z0-9_]{0,63}")) {
            throw ApiException.badRequest("field must be a column name like 'unit_price'");
        }
        return jdbc.sql(FIELD_HISTORY).param("orgId", tenant.organizationId()).param("entityType", entityType)
                .param("entityId", entityId).param("field", field)
                .query((rs, n) -> new FieldChange(rs.getLong("chain_index"), rs.getObject("change_event_id", UUID.class),
                        rs.getString("field"), rs.getObject("previous_version", Long.class), rs.getLong("new_version"),
                        rs.getString("previous_value"), rs.getString("new_value"), rs.getString("operation"),
                        rs.getString("actor_kind"), rs.getObject("actor_user_id", UUID.class), rs.getString("actor_name"),
                        rs.getObject("txn_id", UUID.class), rs.getString("reason"), rs.getString("correlation_id"),
                        Db.instant(rs, "occurred_at")))
                .list();
    }

    @GetMapping("/{entityType}/{entityId}/timeline")
    List<TimelineEntry> timeline(TenantContext tenant, @PathVariable String entityType, @PathVariable UUID entityId) {
        tenant.require(Permissions.AUDIT_READ);
        return jdbc.sql(TIMELINE).param("orgId", tenant.organizationId()).param("entityType", entityType)
                .param("entityId", entityId)
                .query((rs, n) -> new TimelineEntry(rs.getLong("entity_version"), rs.getLong("chain_index"),
                        rs.getString("operation"), json.stringList(rs.getString("fields")), rs.getString("actor_kind"),
                        rs.getObject("actor_user_id", UUID.class), rs.getString("actor_name"),
                        rs.getObject("txn_id", UUID.class), rs.getString("reason"), Db.instant(rs, "occurred_at")))
                .list();
    }
}
