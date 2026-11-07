package com.atlas.organization;

import com.atlas.common.db.Db;
import com.atlas.common.json.Json;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class OrganizationRepository {

    record Organization(UUID id, String slug, String name, String currency, String status,
                        Instant createdAt, long version, List<String> myRoles) {

        Map<String, Object> snapshot() {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("slug", slug);
            s.put("name", name);
            s.put("currency", currency);
            s.put("status", status);
            return s;
        }
    }

    static final String INSERT_ORGANIZATION = """
            INSERT INTO organizations (slug, name)
            VALUES (:slug, :name)
            RETURNING id, slug, name, currency, status, created_at, version
            """;

    static final String LIST_ORGANIZATIONS_FOR_USER = """
            SELECT o.id, o.slug, o.name, o.currency, o.status, o.created_at, o.version,
                   CAST(COALESCE(jsonb_agg(mr.role_code ORDER BY mr.role_code) FILTER (WHERE mr.role_code IS NOT NULL),
                                 CAST('[]' AS jsonb)) AS text) AS roles
              FROM organization_members m
              JOIN organizations o ON o.id = m.organization_id
              LEFT JOIN member_roles mr ON mr.organization_id = m.organization_id AND mr.user_id = m.user_id
             WHERE m.user_id = :userId AND m.status = 'ACTIVE'
             GROUP BY o.id, o.slug, o.name, o.currency, o.status, o.created_at, o.version
             ORDER BY o.name, o.id
            """;

    private final JdbcClient jdbc;
    private final Json json;

    OrganizationRepository(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    Organization insert(String slug, String name) {
        return jdbc.sql(INSERT_ORGANIZATION).param("slug", slug).param("name", name)
                .query((rs, n) -> new Organization(
                        rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("name"),
                        rs.getString("currency"), rs.getString("status"), Db.instant(rs, "created_at"),
                        rs.getLong("version"), List.of()))
                .single();
    }

    List<Organization> listForUser(UUID userId) {
        return jdbc.sql(LIST_ORGANIZATIONS_FOR_USER).param("userId", userId)
                .query((rs, n) -> new Organization(
                        rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("name"),
                        rs.getString("currency"), rs.getString("status"), Db.instant(rs, "created_at"),
                        rs.getLong("version"), json.stringList(rs.getString("roles"))))
                .list();
    }
}
