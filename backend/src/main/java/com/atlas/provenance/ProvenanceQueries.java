package com.atlas.provenance;

import com.atlas.common.db.Db;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read side of field-level provenance (ATLAS X Milestone 2): multi-hop lineage with a
 * computed confidence per edge, and gaps where required provenance is missing.
 *
 * <p>Traversal hops from a record VERSION to the versions derived from it (downstream) or
 * that it was derived from (upstream). It is bounded by depth and deduplicated per
 * (edge, depth), so it terminates on cyclic graphs and cannot blow up on diamonds.
 * A foreign key alone never creates an edge: only operations that consumed a value record one.
 */
@Repository
public class ProvenanceQueries {

    public static final int MAX_DEPTH = 12;
    public static final int MAX_EDGES = 2000;

    /** One lineage edge with its confidence and the values that decided it. */
    public record LineageEdge(int depth, VersionRef source, String sourceField, VersionRef target, String targetField,
                              String relation, String operation, String evidence, ProvenanceConfidence confidence,
                              String sourceValue, String targetValue, UUID txnId, String actorKind, UUID actorUserId,
                              String correlationId, Instant recordedAt) {
    }

    /** A value written without the provenance its requirement demands. */
    public record Gap(VersionRef target, String field, String requirement, String operation, String actorKind,
                      UUID changeEventId, Instant occurredAt) {
    }

    // Shared tail: details, actor (from the target's change event, same transaction) and confidence.
    private static final String DETAILS = """
            SELECT h.depth, e.source_entity_type, e.source_entity_id, e.source_entity_version, e.source_field,
                   e.target_entity_type, e.target_entity_id, e.target_entity_version, e.target_field,
                   e.relation, e.operation, e.evidence,
                   CASE
                     WHEN e.evidence = 'BACKFILLED' THEN 'INFERRED'
                     WHEN e.relation IN ('DERIVED_VALUE', 'COPIED_INTO')
                          AND s.after_state -> e.source_field IS NOT NULL
                          AND s.after_state -> e.source_field = t.after_state -> e.target_field THEN 'VERIFIED'
                     WHEN e.relation = 'AGGREGATED_INTO'
                          AND jsonb_typeof(t.after_state -> e.target_field) = 'number'
                          AND (t.after_state ->> e.target_field)::numeric = (
                              SELECT sum((si.after_state ->> a.source_field)::numeric)
                                FROM provenance_edges a
                                JOIN change_events si
                                  ON si.organization_id = a.organization_id AND si.entity_type = a.source_entity_type
                                 AND si.entity_id = a.source_entity_id AND si.entity_version = a.source_entity_version
                               WHERE a.organization_id = e.organization_id AND a.relation = 'AGGREGATED_INTO'
                                 AND a.target_entity_type = e.target_entity_type AND a.target_entity_id = e.target_entity_id
                                 AND a.target_entity_version = e.target_entity_version
                                 AND a.target_field = e.target_field
                                 AND jsonb_typeof(si.after_state -> a.source_field) = 'number') THEN 'VERIFIED'
                     ELSE 'DECLARED'
                   END AS confidence,
                   CAST(s.after_state -> e.source_field AS text) AS source_value,
                   CAST(t.after_state -> e.target_field AS text) AS target_value,
                   e.txn_id, t.actor_kind, t.actor_user_id, t.correlation_id, e.recorded_at
              FROM hops h
              JOIN provenance_edges e ON e.id = h.id
              JOIN change_events s
                ON s.organization_id = e.organization_id AND s.entity_type = e.source_entity_type
               AND s.entity_id = e.source_entity_id AND s.entity_version = e.source_entity_version
              JOIN change_events t
                ON t.organization_id = e.organization_id AND t.entity_type = e.target_entity_type
               AND t.entity_id = e.target_entity_id AND t.entity_version = e.target_entity_version
             ORDER BY h.depth, e.source_entity_type, e.source_entity_id, e.source_entity_version,
                      e.target_entity_type, e.target_entity_id, e.target_entity_version, e.target_field
             LIMIT 2000
            """;

    static final String LINEAGE_DOWNSTREAM = """
            WITH RECURSIVE walk(id, depth) AS (
                SELECT e.id, 1
                  FROM provenance_edges e
                 WHERE e.organization_id = :orgId AND e.source_entity_type = :entityType
                   AND e.source_entity_id = :entityId
                   AND (CAST(:version AS bigint) IS NULL OR e.source_entity_version = CAST(:version AS bigint))
              UNION
                SELECT n.id, w.depth + 1
                  FROM walk w
                  JOIN provenance_edges c ON c.id = w.id
                  JOIN provenance_edges n
                    ON n.organization_id = c.organization_id AND n.source_entity_type = c.target_entity_type
                   AND n.source_entity_id = c.target_entity_id AND n.source_entity_version = c.target_entity_version
                 WHERE w.depth < :maxDepth
            ), hops AS (SELECT id, min(depth) AS depth FROM walk GROUP BY id)
            """ + DETAILS;

    static final String LINEAGE_UPSTREAM = """
            WITH RECURSIVE walk(id, depth) AS (
                SELECT e.id, 1
                  FROM provenance_edges e
                 WHERE e.organization_id = :orgId AND e.target_entity_type = :entityType
                   AND e.target_entity_id = :entityId
                   AND (CAST(:version AS bigint) IS NULL OR e.target_entity_version = CAST(:version AS bigint))
              UNION
                SELECT n.id, w.depth + 1
                  FROM walk w
                  JOIN provenance_edges c ON c.id = w.id
                  JOIN provenance_edges n
                    ON n.organization_id = c.organization_id AND n.target_entity_type = c.source_entity_type
                   AND n.target_entity_id = c.source_entity_id AND n.target_entity_version = c.source_entity_version
                 WHERE w.depth < :maxDepth
            ), hops AS (SELECT id, min(depth) AS depth FROM walk GROUP BY id)
            """ + DETAILS;

    /**
     * Provenance requirements (fixed in code: they describe how this application computes
     * values). A version that wrote one of these fields must have at least one incoming edge
     * into that field; an aggregate that is zero may have none (an order without lines).
     */
    static final String PROVENANCE_GAPS = """
            WITH req(entity_type, field, zero_allowed, requirement) AS (VALUES
                ('order_line',   'unit_price',   false, 'priced from a product version (order.add_line)'),
                ('order',        'total_amount', true,  'summed from its lines (order.add_line)'),
                ('invoice_line', 'unit_price',   false, 'copied from an order line (invoice.create_from_order)'),
                ('invoice',      'total_amount', true,  'summed from its lines (invoice.create_from_order)'))
            SELECT c.entity_type, c.entity_id, c.entity_version, r.field, r.requirement, c.operation, c.actor_kind,
                   c.id AS change_event_id, c.occurred_at
              FROM change_events c
              JOIN req r ON r.entity_type = c.entity_type
             WHERE c.organization_id = :orgId
               AND c.operation IN ('INSERT', 'UPDATE')
               AND r.field = ANY (c.changed_fields)
               AND (CAST(:entityType AS text) IS NULL OR c.entity_type = CAST(:entityType AS text))
               AND NOT (r.zero_allowed AND jsonb_typeof(c.after_state -> r.field) = 'number'
                        AND (c.after_state ->> r.field)::numeric = 0)
               AND NOT EXISTS (
                   SELECT 1 FROM provenance_edges p
                    WHERE p.organization_id = c.organization_id AND p.target_entity_type = c.entity_type
                      AND p.target_entity_id = c.entity_id AND p.target_entity_version = c.entity_version
                      AND p.target_field = r.field)
             ORDER BY c.chain_index
             LIMIT :limit
            """;

    private final JdbcClient jdbc;

    public ProvenanceQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<LineageEdge> lineage(UUID orgId, String entityType, UUID entityId, Long version, boolean downstream,
                                     int depth) {
        return jdbc.sql(downstream ? LINEAGE_DOWNSTREAM : LINEAGE_UPSTREAM)
                .param("orgId", orgId).param("entityType", entityType).param("entityId", entityId)
                .param("version", version).param("maxDepth", Math.max(1, Math.min(depth, MAX_DEPTH)))
                .query((rs, n) -> new LineageEdge(
                        rs.getInt("depth"),
                        new VersionRef(rs.getString("source_entity_type"), rs.getObject("source_entity_id", UUID.class),
                                rs.getLong("source_entity_version")),
                        rs.getString("source_field"),
                        new VersionRef(rs.getString("target_entity_type"), rs.getObject("target_entity_id", UUID.class),
                                rs.getLong("target_entity_version")),
                        rs.getString("target_field"),
                        rs.getString("relation"), rs.getString("operation"), rs.getString("evidence"),
                        ProvenanceConfidence.valueOf(rs.getString("confidence")),
                        rs.getString("source_value"), rs.getString("target_value"),
                        rs.getObject("txn_id", UUID.class), rs.getString("actor_kind"),
                        rs.getObject("actor_user_id", UUID.class), rs.getString("correlation_id"),
                        Db.instant(rs, "recorded_at")))
                .list();
    }

    public List<Gap> gaps(UUID orgId, String entityType, int limit) {
        return jdbc.sql(PROVENANCE_GAPS)
                .param("orgId", orgId).param("entityType", entityType).param("limit", Math.max(1, Math.min(limit, 1000)))
                .query((rs, n) -> new Gap(
                        new VersionRef(rs.getString("entity_type"), rs.getObject("entity_id", UUID.class),
                                rs.getLong("entity_version")),
                        rs.getString("field"), rs.getString("requirement"), rs.getString("operation"),
                        rs.getString("actor_kind"), rs.getObject("change_event_id", UUID.class),
                        Db.instant(rs, "occurred_at")))
                .list();
    }
}
