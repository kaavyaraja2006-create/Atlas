package com.atlas.provenance;

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
 * Provenance around a record: one hop ({@code /{type}/{id}}), multi-hop lineage with
 * confidence ({@code /lineage}, ATLAS X) and missing required provenance ({@code /gaps}).
 */
@RestController
@RequestMapping("/api/v1/provenance")
class ProvenanceController {

    record Edge(VersionRef source, String sourceField, VersionRef target, String targetField,
                String relation, String evidence, String operation, UUID txnId, Instant recordedAt) {
    }

    /** Multi-hop lineage around one record (ATLAS X): edges with confidence, plus whether it was cut short. */
    record Lineage(String direction, int maxDepth, boolean truncated, List<ProvenanceQueries.LineageEdge> edges) {
    }

    static final String LIST_DOWNSTREAM_EDGES = """
            SELECT source_entity_type, source_entity_id, source_entity_version, source_field,
                   target_entity_type, target_entity_id, target_entity_version, target_field,
                   relation, evidence, operation, txn_id, recorded_at
              FROM provenance_edges
             WHERE organization_id = :orgId AND source_entity_type = :entityType AND source_entity_id = :entityId
               AND (CAST(:version AS bigint) IS NULL OR source_entity_version = CAST(:version AS bigint))
             ORDER BY source_entity_version, target_entity_type, target_entity_id, target_entity_version
             LIMIT 1000
            """;

    static final String LIST_UPSTREAM_EDGES = """
            SELECT source_entity_type, source_entity_id, source_entity_version, source_field,
                   target_entity_type, target_entity_id, target_entity_version, target_field,
                   relation, evidence, operation, txn_id, recorded_at
              FROM provenance_edges
             WHERE organization_id = :orgId AND target_entity_type = :entityType AND target_entity_id = :entityId
               AND (CAST(:version AS bigint) IS NULL OR target_entity_version = CAST(:version AS bigint))
             ORDER BY target_entity_version, source_entity_type, source_entity_id, source_entity_version
             LIMIT 1000
            """;

    private final JdbcClient jdbc;
    private final ProvenanceQueries queries;

    ProvenanceController(JdbcClient jdbc, ProvenanceQueries queries) {
        this.jdbc = jdbc;
        this.queries = queries;
    }

    /**
     * Multi-hop, field-level lineage from (or to) a record, optionally from one version.
     * Each edge carries its confidence (VERIFIED / DECLARED / INFERRED) and the values
     * that decided it. Bounded by {@code depth} (max 12) and 2,000 edges.
     */
    @GetMapping("/{entityType}/{entityId}/lineage")
    Lineage lineage(TenantContext tenant, @PathVariable String entityType, @PathVariable UUID entityId,
                    @RequestParam(required = false) Long version,
                    @RequestParam(defaultValue = "downstream") String direction,
                    @RequestParam(defaultValue = "6") int depth) {
        tenant.require(Permissions.AUDIT_READ);
        if (!direction.equals("downstream") && !direction.equals("upstream")) {
            throw ApiException.badRequest("direction must be 'downstream' or 'upstream'");
        }
        if (depth < 1 || depth > ProvenanceQueries.MAX_DEPTH) {
            throw ApiException.badRequest("depth must be between 1 and " + ProvenanceQueries.MAX_DEPTH);
        }
        List<ProvenanceQueries.LineageEdge> edges = queries.lineage(tenant.organizationId(), entityType, entityId,
                version, direction.equals("downstream"), depth);
        return new Lineage(direction, depth, edges.size() >= ProvenanceQueries.MAX_EDGES, edges);
    }

    /**
     * Values written without the provenance their requirement demands (confidence UNKNOWN).
     * A gap is evidence that lineage is incomplete there; nothing is invented to fill it.
     */
    @GetMapping("/gaps")
    List<ProvenanceQueries.Gap> gaps(TenantContext tenant, @RequestParam(required = false) String entityType,
                                     @RequestParam(defaultValue = "200") int limit) {
        tenant.require(Permissions.AUDIT_READ);
        return queries.gaps(tenant.organizationId(), entityType, limit);
    }

    @GetMapping("/{entityType}/{entityId}")
    List<Edge> edges(TenantContext tenant, @PathVariable String entityType, @PathVariable UUID entityId,
                     @RequestParam(required = false) Long version,
                     @RequestParam(defaultValue = "downstream") String direction) {
        tenant.require(Permissions.AUDIT_READ);
        String sql = switch (direction) {
            case "downstream" -> LIST_DOWNSTREAM_EDGES;
            case "upstream" -> LIST_UPSTREAM_EDGES;
            default -> throw ApiException.badRequest("direction must be 'downstream' or 'upstream'");
        };
        return jdbc.sql(sql)
                .param("orgId", tenant.organizationId())
                .param("entityType", entityType)
                .param("entityId", entityId)
                .param("version", version)
                .query((rs, n) -> new Edge(
                        new VersionRef(rs.getString("source_entity_type"), rs.getObject("source_entity_id", UUID.class),
                                rs.getLong("source_entity_version")),
                        rs.getString("source_field"),
                        new VersionRef(rs.getString("target_entity_type"), rs.getObject("target_entity_id", UUID.class),
                                rs.getLong("target_entity_version")),
                        rs.getString("target_field"),
                        rs.getString("relation"),
                        rs.getString("evidence"),
                        rs.getString("operation"),
                        rs.getObject("txn_id", UUID.class),
                        Db.instant(rs, "recorded_at")))
                .list();
    }
}
