package com.atlas.graph.schema;

import com.atlas.common.json.Json;
import com.atlas.common.web.CorrelationIdFilter;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Platform-level audit trail (V14): actions on things that belong to no tenant, such as
 * inspecting the database schema. Append-only. Each write commits on its own (the caller
 * holds no transaction), so a denial is recorded even though the request then fails.
 */
@Component
public class PlatformAudit {

    public static final String SCHEMA_VIEW = "SCHEMA_VIEW";
    public static final String SCHEMA_VERSIONS = "SCHEMA_VERSIONS";
    public static final String SCHEMA_DIFF = "SCHEMA_DIFF";
    public static final String SCHEMA_EXPORT = "SCHEMA_EXPORT";
    public static final String SCHEMA_ACCESS_DENIED = "SCHEMA_ACCESS_DENIED";

    static final String INSERT_PLATFORM_AUDIT_EVENT = """
            INSERT INTO platform_audit_events (actor_user_id, action, target, detail, correlation_id)
            VALUES (:actor, :action, :target, CAST(:detail AS jsonb), :correlationId)
            RETURNING id
            """;

    private final JdbcClient jdbc;
    private final Json json;

    public PlatformAudit(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public UUID record(UUID actor, String action, String target, Map<String, ?> detail) {
        return jdbc.sql(INSERT_PLATFORM_AUDIT_EVENT).param("actor", actor).param("action", action).param("target", target)
                .param("detail", json.write(detail)).param("correlationId", MDC.get(CorrelationIdFilter.MDC_KEY))
                .query(UUID.class).single();
    }
}
