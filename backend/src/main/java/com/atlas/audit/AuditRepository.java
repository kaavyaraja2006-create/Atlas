package com.atlas.audit;

import com.atlas.common.db.Db;
import com.atlas.common.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class AuditRepository {

    /** Actor names come from users; a user who left the organization still shows by name in history. */
    static final String LIST_EVENTS = """
            SELECT e.id, e.chain_index, e.txn_id, e.actor_kind, e.actor_user_id, e.entity_type, e.entity_id,
                   e.entity_version, e.operation, CAST(to_jsonb(e.changed_fields) AS text) AS changed_fields,
                   CAST(e.before_state AS text) AS before_state, CAST(e.after_state AS text) AS after_state,
                   e.reason, e.correlation_id, e.occurred_at, encode(e.hash, 'hex') AS hash,
                   u.display_name AS actor_name
              FROM change_events e
              LEFT JOIN users u ON u.id = e.actor_user_id
             WHERE e.organization_id = :orgId
               AND (CAST(:entityType AS varchar) IS NULL OR e.entity_type = CAST(:entityType AS varchar))
               AND (CAST(:entityId AS uuid) IS NULL OR e.entity_id = CAST(:entityId AS uuid))
               AND (CAST(:actorUserId AS uuid) IS NULL OR e.actor_user_id = CAST(:actorUserId AS uuid))
               AND (CAST(:txnId AS uuid) IS NULL OR e.txn_id = CAST(:txnId AS uuid))
               AND (CAST(:fromTs AS timestamptz) IS NULL OR e.occurred_at >= CAST(:fromTs AS timestamptz))
               AND (CAST(:toTs AS timestamptz) IS NULL OR e.occurred_at < CAST(:toTs AS timestamptz))
             ORDER BY e.occurred_at DESC, e.chain_index DESC
             LIMIT :limit OFFSET :offset
            """;

    static final String COUNT_EVENTS = """
            SELECT count(*)
              FROM change_events
             WHERE organization_id = :orgId
               AND (CAST(:entityType AS varchar) IS NULL OR entity_type = CAST(:entityType AS varchar))
               AND (CAST(:entityId AS uuid) IS NULL OR entity_id = CAST(:entityId AS uuid))
               AND (CAST(:actorUserId AS uuid) IS NULL OR actor_user_id = CAST(:actorUserId AS uuid))
               AND (CAST(:txnId AS uuid) IS NULL OR txn_id = CAST(:txnId AS uuid))
               AND (CAST(:fromTs AS timestamptz) IS NULL OR occurred_at >= CAST(:fromTs AS timestamptz))
               AND (CAST(:toTs AS timestamptz) IS NULL OR occurred_at < CAST(:toTs AS timestamptz))
            """;

    static final String FIND_EVENT = """
            SELECT e.id, e.chain_index, e.txn_id, e.actor_kind, e.actor_user_id, e.entity_type, e.entity_id,
                   e.entity_version, e.operation, CAST(to_jsonb(e.changed_fields) AS text) AS changed_fields,
                   CAST(e.before_state AS text) AS before_state, CAST(e.after_state AS text) AS after_state,
                   e.reason, e.correlation_id, e.occurred_at, encode(e.hash, 'hex') AS hash,
                   u.display_name AS actor_name
              FROM change_events e
              LEFT JOIN users u ON u.id = e.actor_user_id
             WHERE e.organization_id = :orgId AND e.id = :id
            """;

    static final String VERIFY_CHAIN = """
            SELECT verify_audit_chain(:orgId) AS first_broken_index,
                   (SELECT count(*) FROM change_events WHERE organization_id = :orgId) AS event_count
            """;

    record Filter(String entityType, UUID entityId, UUID actorUserId, UUID txnId, Instant from, Instant to) {
    }

    record ChainCheck(Long firstBrokenIndex, long eventCount) {
    }

    private final JdbcClient jdbc;
    private final Json json;

    AuditRepository(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    List<AuditEvent> list(UUID orgId, Filter f, int limit, long offset) {
        return bind(jdbc.sql(LIST_EVENTS), orgId, f)
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, n) -> map(rs))
                .list();
    }

    long count(UUID orgId, Filter f) {
        return bind(jdbc.sql(COUNT_EVENTS), orgId, f).query(Long.class).single();
    }

    Optional<AuditEvent> find(UUID orgId, UUID id) {
        return jdbc.sql(FIND_EVENT).param("orgId", orgId).param("id", id)
                .query((rs, n) -> map(rs)).optional();
    }

    ChainCheck verify(UUID orgId) {
        return jdbc.sql(VERIFY_CHAIN).param("orgId", orgId)
                .query((rs, n) -> new ChainCheck(rs.getObject("first_broken_index", Long.class),
                        rs.getLong("event_count")))
                .single();
    }

    private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec spec, UUID orgId, Filter f) {
        return spec.param("orgId", orgId)
                .param("entityType", f.entityType())
                .param("entityId", f.entityId())
                .param("actorUserId", f.actorUserId())
                .param("txnId", f.txnId())
                .param("fromTs", Db.ts(f.from()))
                .param("toTs", Db.ts(f.to()));
    }

    private AuditEvent map(java.sql.ResultSet rs) throws java.sql.SQLException {
        JsonNode before = json.tree(rs.getString("before_state"));
        JsonNode after = json.tree(rs.getString("after_state"));
        return new AuditEvent(
                rs.getObject("id", UUID.class),
                rs.getLong("chain_index"),
                rs.getObject("txn_id", UUID.class),
                rs.getString("actor_kind"),
                rs.getObject("actor_user_id", UUID.class),
                rs.getString("entity_type"),
                rs.getObject("entity_id", UUID.class),
                rs.getLong("entity_version"),
                rs.getString("operation"),
                json.stringList(rs.getString("changed_fields")),
                before, after,
                rs.getString("reason"),
                rs.getString("correlation_id"),
                Db.instant(rs, "occurred_at"),
                rs.getString("hash"),
                rs.getString("actor_name"));
    }
}
