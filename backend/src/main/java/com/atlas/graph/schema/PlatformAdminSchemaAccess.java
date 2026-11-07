package com.atlas.graph.schema;

import com.atlas.common.error.ApiException;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Schema access for platform administrators only. Checked against {@code users.platform_admin}
 * on every request, not just the access token's claim, so revoking the flag takes effect
 * immediately rather than when the token expires.
 */
@Component
class PlatformAdminSchemaAccess implements SchemaAccessPolicy {

    static final String IS_ACTIVE_PLATFORM_ADMIN = """
            SELECT EXISTS (SELECT 1 FROM users WHERE id = :userId AND platform_admin AND status = 'ACTIVE')
            """;

    private final JdbcClient jdbc;
    private final PlatformAudit audit;

    PlatformAdminSchemaAccess(JdbcClient jdbc, PlatformAudit audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Override
    public String rule() {
        return "platform administrators only";
    }

    @Override
    public boolean mayInspect(UUID userId) {
        return Boolean.TRUE.equals(jdbc.sql(IS_ACTIVE_PLATFORM_ADMIN).param("userId", userId).query(Boolean.class).single());
    }

    @Override
    public void requireSchemaAccess(UUID userId, String attemptedAction) {
        if (!mayInspect(userId)) {
            audit.record(userId, PlatformAudit.SCHEMA_ACCESS_DENIED, attemptedAction, Map.of("rule", rule()));
            throw ApiException.denied("The database schema is visible to platform administrators only.");
        }
    }
}
