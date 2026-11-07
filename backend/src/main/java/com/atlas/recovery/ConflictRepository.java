package com.atlas.recovery;

import com.atlas.common.db.Db;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Human decisions on recovery conflicts (V11). The conflicts themselves are computed, not stored. */
@Repository
class ConflictRepository {

    record Review(String entityType, UUID entityId, String field, String kind, String decision, String note,
                  UUID reviewedBy, String reviewerName, Instant reviewedAt) {
    }

    static final String INSERT_CONFLICT_REVIEW = """
            INSERT INTO recovery_conflict_reviews (organization_id, incident_id, entity_type, entity_id, field, kind,
                                                   decision, note, reviewed_by)
            VALUES (:orgId, :incidentId, :entityType, :entityId, :field, :kind, :decision, :note, :reviewedBy)
            RETURNING reviewed_at
            """;

    /** The latest decision per (record, field) of an incident. */
    static final String LATEST_CONFLICT_REVIEWS = """
            SELECT DISTINCT ON (r.entity_type, r.entity_id, r.field)
                   r.entity_type, r.entity_id, r.field, r.kind, r.decision, r.note, r.reviewed_by,
                   u.display_name AS reviewer_name, r.reviewed_at
              FROM recovery_conflict_reviews r
              JOIN users u ON u.id = r.reviewed_by
             WHERE r.organization_id = :orgId AND r.incident_id = :incidentId
             ORDER BY r.entity_type, r.entity_id, r.field, r.reviewed_at DESC, r.id DESC
            """;

    private final JdbcClient jdbc;

    ConflictRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Instant insert(UUID orgId, UUID incidentId, String entityType, UUID entityId, String field, String kind,
                   String decision, String note, UUID reviewedBy) {
        return jdbc.sql(INSERT_CONFLICT_REVIEW).param("orgId", orgId).param("incidentId", incidentId)
                .param("entityType", entityType).param("entityId", entityId).param("field", field)
                .param("kind", kind).param("decision", decision).param("note", note).param("reviewedBy", reviewedBy)
                .query((rs, n) -> Db.instant(rs, "reviewed_at")).single();
    }

    List<Review> latest(UUID orgId, UUID incidentId) {
        return jdbc.sql(LATEST_CONFLICT_REVIEWS).param("orgId", orgId).param("incidentId", incidentId)
                .query((rs, n) -> new Review(rs.getString("entity_type"), rs.getObject("entity_id", UUID.class),
                        rs.getString("field"), rs.getString("kind"), rs.getString("decision"), rs.getString("note"),
                        rs.getObject("reviewed_by", UUID.class), rs.getString("reviewer_name"),
                        Db.instant(rs, "reviewed_at")))
                .list();
    }
}
