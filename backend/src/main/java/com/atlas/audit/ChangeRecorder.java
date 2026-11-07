package com.atlas.audit;

import com.atlas.common.json.Json;
import com.atlas.common.tenancy.Actor;
import com.atlas.common.web.CorrelationIdFilter;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only way any module writes history. Must be called inside the same
 * transaction as the business write it describes (enforced: MANDATORY), so a
 * change and its record commit or roll back together. The database assigns
 * the chain position and hash.
 *
 * <p>Snapshots use the table's column names (snake_case) so they line up
 * with provenance edge field names.
 */
@Component
public class ChangeRecorder {

    static final String INSERT_CHANGE_EVENT = """
            INSERT INTO change_events (organization_id, txn_id, actor_kind, actor_user_id,
                                       entity_type, entity_id, entity_version, operation,
                                       changed_fields, before_state, after_state, reason, correlation_id)
            VALUES (:orgId, :txnId, :actorKind, :actorUserId,
                    :entityType, :entityId, :version, :operation,
                    ARRAY(SELECT jsonb_array_elements_text(CAST(:changedFields AS jsonb))),
                    CAST(:before AS jsonb), CAST(:after AS jsonb), :reason, :correlationId)
            RETURNING id
            """;

    private final JdbcClient jdbc;
    private final Json json;

    public ChangeRecorder(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Version 0 of a new record. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID recordInsert(UUID orgId, Actor actor, String entityType, UUID entityId,
                             Map<String, ?> after, String reason) {
        List<String> fields = List.copyOf(new TreeSet<>(after.keySet()));
        return insert(orgId, actor, entityType, entityId, 0, "INSERT", fields, null, after, reason);
    }

    /**
     * A new version of an existing record. Returns the changed fields.
     * Callers must not record a no-op; a snapshot pair with no difference is
     * a programming error.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<String> recordUpdate(UUID orgId, Actor actor, String entityType, UUID entityId,
                                     long newVersion, Map<String, ?> before, Map<String, ?> after,
                                     String reason) {
        List<String> changed = ChangeDiff.changedFields(before, after);
        if (changed.isEmpty()) {
            throw new IllegalArgumentException("recordUpdate called for " + entityType + " " + entityId
                    + " but nothing changed");
        }
        insert(orgId, actor, entityType, entityId, newVersion, "UPDATE", changed, before, after, reason);
        return changed;
    }

    private UUID insert(UUID orgId, Actor actor, String entityType, UUID entityId, long version,
                        String operation, List<String> changed, Map<String, ?> before,
                        Map<String, ?> after, String reason) {
        return jdbc.sql(INSERT_CHANGE_EVENT)
                .param("orgId", orgId)
                .param("txnId", TransactionIds.current())
                .param("actorKind", actor.kind())
                .param("actorUserId", actor.userId())
                .param("entityType", entityType)
                .param("entityId", entityId)
                .param("version", version)
                .param("operation", operation)
                .param("changedFields", json.write(changed))
                .param("before", before == null ? null : json.write(before))
                .param("after", after == null ? null : json.write(after))
                .param("reason", reason)
                .param("correlationId", MDC.get(CorrelationIdFilter.MDC_KEY))
                .query(UUID.class)
                .single();
    }
}
