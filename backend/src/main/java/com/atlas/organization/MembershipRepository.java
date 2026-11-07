package com.atlas.organization;

import com.atlas.common.db.Db;
import com.atlas.common.json.Json;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class MembershipRepository {

    record Access(Set<String> roles, Set<String> permissions) {
    }

    record Member(UUID userId, String email, String displayName, String status, Instant joinedAt,
                  List<String> roles, long version) {
    }

    /** One row only for an ACTIVE member of an ACTIVE organization; no row means "not found". */
    static final String LOAD_ACCESS = """
            SELECT CAST(COALESCE(jsonb_agg(DISTINCT mr.role_code) FILTER (WHERE mr.role_code IS NOT NULL),
                                 CAST('[]' AS jsonb)) AS text) AS roles,
                   CAST(COALESCE(jsonb_agg(DISTINCT rp.permission_code) FILTER (WHERE rp.permission_code IS NOT NULL),
                                 CAST('[]' AS jsonb)) AS text) AS permissions
              FROM organization_members m
              JOIN organizations o ON o.id = m.organization_id AND o.status = 'ACTIVE'
              LEFT JOIN member_roles mr ON mr.organization_id = m.organization_id AND mr.user_id = m.user_id
              LEFT JOIN role_permissions rp ON rp.role_code = mr.role_code
             WHERE m.organization_id = :orgId AND m.user_id = :userId AND m.status = 'ACTIVE'
             GROUP BY m.organization_id
            """;

    static final String INSERT_MEMBER = """
            INSERT INTO organization_members (organization_id, user_id, status)
            VALUES (:orgId, :userId, 'ACTIVE')
            """;

    static final String INSERT_MEMBER_ROLE = """
            INSERT INTO member_roles (organization_id, user_id, role_code)
            VALUES (:orgId, :userId, :role)
            ON CONFLICT DO NOTHING
            """;

    static final String DELETE_ROLES_NOT_IN = """
            DELETE FROM member_roles
             WHERE organization_id = :orgId AND user_id = :userId
               AND role_code <> ALL (ARRAY(SELECT jsonb_array_elements_text(CAST(:roles AS jsonb))))
            """;

    static final String BUMP_MEMBER_VERSION = """
            UPDATE organization_members
               SET version = version + 1
             WHERE organization_id = :orgId AND user_id = :userId
            RETURNING version
            """;

    /** Serialises role changes per organization, so two admins cannot demote each other at once. */
    static final String LOCK_ORGANIZATION = """
            SELECT id FROM organizations WHERE id = :orgId FOR UPDATE
            """;

    static final String COUNT_OTHER_ACTIVE_ADMINS = """
            SELECT count(*)
              FROM member_roles mr
              JOIN organization_members m
                ON m.organization_id = mr.organization_id AND m.user_id = mr.user_id
             WHERE mr.organization_id = :orgId AND mr.role_code = 'ORGANIZATION_ADMIN'
               AND m.status = 'ACTIVE' AND mr.user_id <> :userId
            """;

    static final String FIND_MEMBER = """
            SELECT u.id AS user_id, u.email, u.display_name, m.status, m.joined_at, m.version,
                   CAST(COALESCE(jsonb_agg(mr.role_code ORDER BY mr.role_code) FILTER (WHERE mr.role_code IS NOT NULL),
                                 CAST('[]' AS jsonb)) AS text) AS roles
              FROM organization_members m
              JOIN users u ON u.id = m.user_id
              LEFT JOIN member_roles mr ON mr.organization_id = m.organization_id AND mr.user_id = m.user_id
             WHERE m.organization_id = :orgId AND m.user_id = :userId
             GROUP BY u.id, u.email, u.display_name, m.status, m.joined_at, m.version
            """;

    static final String LIST_MEMBERS = """
            SELECT u.id AS user_id, u.email, u.display_name, m.status, m.joined_at, m.version,
                   CAST(COALESCE(jsonb_agg(mr.role_code ORDER BY mr.role_code) FILTER (WHERE mr.role_code IS NOT NULL),
                                 CAST('[]' AS jsonb)) AS text) AS roles
              FROM organization_members m
              JOIN users u ON u.id = m.user_id
              LEFT JOIN member_roles mr ON mr.organization_id = m.organization_id AND mr.user_id = m.user_id
             WHERE m.organization_id = :orgId AND m.status = 'ACTIVE'
             GROUP BY u.id, u.email, u.display_name, m.status, m.joined_at, m.version
             ORDER BY u.display_name, u.id
             LIMIT :limit OFFSET :offset
            """;

    static final String COUNT_MEMBERS = """
            SELECT count(*) FROM organization_members WHERE organization_id = :orgId AND status = 'ACTIVE'
            """;

    static final String LIST_ROLE_CODES = """
            SELECT code FROM roles ORDER BY code
            """;

    private final JdbcClient jdbc;
    private final Json json;

    MembershipRepository(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    Optional<Access> loadAccess(UUID orgId, UUID userId) {
        return jdbc.sql(LOAD_ACCESS).param("orgId", orgId).param("userId", userId)
                .query((rs, n) -> new Access(
                        Set.copyOf(json.stringList(rs.getString("roles"))),
                        Set.copyOf(json.stringList(rs.getString("permissions")))))
                .optional();
    }

    void insertMember(UUID orgId, UUID userId) {
        jdbc.sql(INSERT_MEMBER).param("orgId", orgId).param("userId", userId).update();
    }

    void insertRole(UUID orgId, UUID userId, String role) {
        jdbc.sql(INSERT_MEMBER_ROLE).param("orgId", orgId).param("userId", userId).param("role", role).update();
    }

    void deleteRolesNotIn(UUID orgId, UUID userId, Collection<String> keep) {
        jdbc.sql(DELETE_ROLES_NOT_IN).param("orgId", orgId).param("userId", userId)
                .param("roles", json.write(List.copyOf(keep))).update();
    }

    long bumpVersion(UUID orgId, UUID userId) {
        return jdbc.sql(BUMP_MEMBER_VERSION).param("orgId", orgId).param("userId", userId)
                .query(Long.class).single();
    }

    void lockOrganization(UUID orgId) {
        jdbc.sql(LOCK_ORGANIZATION).param("orgId", orgId).query(UUID.class).single();
    }

    long countOtherActiveAdmins(UUID orgId, UUID userId) {
        return jdbc.sql(COUNT_OTHER_ACTIVE_ADMINS).param("orgId", orgId).param("userId", userId)
                .query(Long.class).single();
    }

    Optional<Member> find(UUID orgId, UUID userId) {
        return jdbc.sql(FIND_MEMBER).param("orgId", orgId).param("userId", userId)
                .query((rs, n) -> mapMember(rs)).optional();
    }

    List<Member> list(UUID orgId, int limit, long offset) {
        return jdbc.sql(LIST_MEMBERS).param("orgId", orgId).param("limit", limit).param("offset", offset)
                .query((rs, n) -> mapMember(rs)).list();
    }

    long count(UUID orgId) {
        return jdbc.sql(COUNT_MEMBERS).param("orgId", orgId).query(Long.class).single();
    }

    Set<String> roleCodes() {
        return Set.copyOf(jdbc.sql(LIST_ROLE_CODES).query(String.class).list());
    }

    private Member mapMember(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Member(
                rs.getObject("user_id", UUID.class),
                rs.getString("email"),
                rs.getString("display_name"),
                rs.getString("status"),
                Db.instant(rs, "joined_at"),
                json.stringList(rs.getString("roles")),
                rs.getLong("version"));
    }
}
