package com.atlas.integrity;

import com.atlas.common.db.Db;
import com.atlas.common.json.Json;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Integrity rules, checks and incidents (V13), and the SQL that evaluates each rule kind. */
@Repository
class IntegrityRepository {

    record Rule(UUID id, String code, String kind, String name, String severity, boolean enabled, Object config,
                long version, UUID createdBy, Instant createdAt, Instant updatedAt) {
    }

    record RuleVersion(long version, String kind, String name, String severity, boolean enabled, Object config,
                       UUID changedBy, String changedByName, Instant changedAt) {
    }

    record Finding(String subjectType, UUID subjectId, String title, String detail) {
    }

    record Run(UUID id, String trigger, UUID startedBy, String status, int rulesEvaluated, int findings, int opened,
               int cleared, String failure, Instant startedAt, Instant finishedAt) {
    }

    record Incident(UUID id, UUID ruleId, String ruleCode, String ruleName, long ruleVersion, String subjectType,
                    UUID subjectId, String severity, String status, String title, Object detail, int occurrences,
                    Instant firstSeenAt, Instant lastSeenAt, Instant closedAt, long version) {
    }

    record Event(String fromStatus, String toStatus, UUID actorUserId, String actorName, String note, UUID runId,
                 Instant occurredAt) {
    }

    // ------------------------------------------------------------------ rule evaluators (one per kind)

    static final String RULE_ORDER_TOTALS = """
            SELECT 'order' AS subject_type, o.id AS subject_id,
                   'Order ' || o.order_number || ': total ' || o.total_amount || ' but its lines sum to '
                       || COALESCE(s.total, 0) AS title,
                   CAST(jsonb_build_object('orderNumber', o.order_number, 'status', o.status, 'total', o.total_amount,
                        'linesSum', COALESCE(s.total, 0), 'version', o.version) AS text) AS detail
              FROM orders o
              LEFT JOIN (SELECT order_id, sum(quantity * unit_price) AS total
                           FROM order_lines WHERE organization_id = :orgId GROUP BY order_id) s ON s.order_id = o.id
             WHERE o.organization_id = :orgId AND o.status <> 'CANCELLED'
               AND abs(o.total_amount - COALESCE(s.total, 0)) > :tolerance
             ORDER BY o.order_number
             LIMIT :limit
            """;

    static final String RULE_INVOICE_TOTALS = """
            SELECT 'invoice' AS subject_type, i.id AS subject_id,
                   'Invoice ' || i.invoice_number || ': total ' || i.total_amount || ' but its lines sum to '
                       || COALESCE(s.total, 0) AS title,
                   CAST(jsonb_build_object('invoiceNumber', i.invoice_number, 'status', i.status, 'total', i.total_amount,
                        'linesSum', COALESCE(s.total, 0), 'version', i.version) AS text) AS detail
              FROM invoices i
              LEFT JOIN (SELECT invoice_id, sum(quantity * unit_price) AS total
                           FROM invoice_lines WHERE organization_id = :orgId GROUP BY invoice_id) s ON s.invoice_id = i.id
             WHERE i.organization_id = :orgId AND i.status <> 'VOID'
               AND abs(i.total_amount - COALESCE(s.total, 0)) > :tolerance
             ORDER BY i.invoice_number
             LIMIT :limit
            """;

    /**
     * An order line whose current price differs from the value at the source of its latest
     * recorded price provenance: changed without provenance (maybe a legitimate discount,
     * maybe not). Evidence for a person, never a correction.
     */
    static final String RULE_DERIVED_VALUES = """
            WITH latest AS (
                SELECT DISTINCT ON (p.target_entity_id)
                       p.target_entity_id AS line_id, p.source_entity_type, p.source_entity_id,
                       p.source_entity_version, p.source_field, p.target_entity_version
                  FROM provenance_edges p
                 WHERE p.organization_id = :orgId AND p.target_entity_type = 'order_line' AND p.target_field = 'unit_price'
                 ORDER BY p.target_entity_id, p.target_entity_version DESC
            )
            SELECT 'order_line' AS subject_type, l.id AS subject_id,
                   'Order line ' || l.line_no || ' of ' || o.order_number || ': price ' || l.unit_price
                       || ' differs from its recorded source ' || (s.after_state ->> lt.source_field) AS title,
                   CAST(jsonb_build_object('orderNumber', o.order_number, 'current', l.unit_price,
                        'sourceValue', s.after_state -> lt.source_field,
                        'source', lt.source_entity_type || ':' || lt.source_entity_id || '@' || lt.source_entity_version,
                        'derivedAtVersion', lt.target_entity_version, 'currentVersion', l.version) AS text) AS detail
              FROM order_lines l
              JOIN orders o ON o.organization_id = l.organization_id AND o.id = l.order_id
              JOIN latest lt ON lt.line_id = l.id
              JOIN change_events s
                ON s.organization_id = l.organization_id AND s.entity_type = lt.source_entity_type
               AND s.entity_id = lt.source_entity_id AND s.entity_version = lt.source_entity_version
             WHERE l.organization_id = :orgId
               AND jsonb_typeof(s.after_state -> lt.source_field) = 'number'
               AND abs((s.after_state ->> lt.source_field)::numeric - l.unit_price) > :tolerance
             ORDER BY o.order_number, l.line_no
             LIMIT :limit
            """;

    /** Records with at least one version that wrote a value its provenance requirement says must be derived. */
    static final String RULE_REQUIRED_PROVENANCE = """
            WITH req(entity_type, field, zero_allowed) AS (VALUES
                ('order_line', 'unit_price', false), ('order', 'total_amount', true),
                ('invoice_line', 'unit_price', false), ('invoice', 'total_amount', true)),
            gaps AS (
                SELECT c.entity_type, c.entity_id, c.entity_version, r.field
                  FROM change_events c
                  JOIN req r ON r.entity_type = c.entity_type
                 WHERE c.organization_id = :orgId AND c.operation IN ('INSERT', 'UPDATE')
                   AND r.field = ANY (c.changed_fields)
                   AND NOT (r.zero_allowed AND jsonb_typeof(c.after_state -> r.field) = 'number'
                            AND (c.after_state ->> r.field)::numeric = 0)
                   AND NOT EXISTS (
                       SELECT 1 FROM provenance_edges p
                        WHERE p.organization_id = c.organization_id AND p.target_entity_type = c.entity_type
                          AND p.target_entity_id = c.entity_id AND p.target_entity_version = c.entity_version
                          AND p.target_field = r.field)
            )
            SELECT entity_type AS subject_type, entity_id AS subject_id,
                   replace(entity_type, '_', ' ') || ' ' || entity_id || ': ' || count(*)
                       || ' value(s) written without the provenance they require' AS title,
                   CAST(jsonb_build_object('versions', jsonb_agg(entity_version ORDER BY entity_version),
                        'fields', jsonb_agg(DISTINCT field)) AS text) AS detail
              FROM gaps
             GROUP BY entity_type, entity_id
             ORDER BY entity_type, entity_id
             LIMIT :limit
            """;

    static final String RULE_AUDIT_CHAIN = """
            SELECT 'organization' AS subject_type, CAST(:orgId AS uuid) AS subject_id,
                   'Audit chain broken at event #' || b.idx AS title,
                   CAST(jsonb_build_object('firstBrokenIndex', b.idx) AS text) AS detail
              FROM (SELECT verify_audit_chain(:orgId) AS idx) b
             WHERE b.idx IS NOT NULL
            """;

    // ------------------------------------------------------------------ rules

    private static final String RULE_COLUMNS = """
            SELECT id, code, kind, name, severity, enabled, CAST(config AS text) AS config, version, created_by,
                   created_at, updated_at
              FROM integrity_rules
            """;

    static final String LIST_INTEGRITY_RULES = RULE_COLUMNS + """
             WHERE organization_id = :orgId
             ORDER BY code
            """;

    static final String FIND_INTEGRITY_RULE = RULE_COLUMNS + """
             WHERE organization_id = :orgId AND id = :id
            """;

    static final String INSERT_INTEGRITY_RULE = """
            INSERT INTO integrity_rules (organization_id, code, kind, name, severity, enabled, config, created_by)
            VALUES (:orgId, :code, :kind, :name, :severity, :enabled, CAST(:config AS jsonb), :createdBy)
            RETURNING id, code, kind, name, severity, enabled, CAST(config AS text) AS config, version, created_by,
                      created_at, updated_at
            """;

    static final String UPDATE_INTEGRITY_RULE = """
            UPDATE integrity_rules
               SET name = :name, severity = :severity, enabled = :enabled, config = CAST(:config AS jsonb),
                   version = version + 1, updated_at = now()
             WHERE organization_id = :orgId AND id = :id AND version = :expectedVersion
            RETURNING id, code, kind, name, severity, enabled, CAST(config AS text) AS config, version, created_by,
                      created_at, updated_at
            """;

    static final String INSERT_RULE_VERSION = """
            INSERT INTO integrity_rule_versions (organization_id, rule_id, version, kind, name, severity, enabled,
                                                 config, changed_by)
            VALUES (:orgId, :ruleId, :version, :kind, :name, :severity, :enabled, CAST(:config AS jsonb), :changedBy)
            """;

    static final String LIST_RULE_VERSIONS = """
            SELECT v.version, v.kind, v.name, v.severity, v.enabled, CAST(v.config AS text) AS config, v.changed_by,
                   u.display_name AS changed_by_name, v.changed_at
              FROM integrity_rule_versions v
              JOIN users u ON u.id = v.changed_by
             WHERE v.organization_id = :orgId AND v.rule_id = :ruleId
             ORDER BY v.version DESC
            """;

    static final String ORGANIZATIONS_WITH_RULES = """
            SELECT DISTINCT organization_id FROM integrity_rules WHERE enabled ORDER BY organization_id
            """;

    // ------------------------------------------------------------------ runs and incidents

    static final String INSERT_CHECK_RUN = """
            INSERT INTO integrity_check_runs (organization_id, trigger, started_by)
            VALUES (:orgId, :trigger, :startedBy)
            RETURNING id
            """;

    static final String FINISH_CHECK_RUN = """
            UPDATE integrity_check_runs
               SET status = :status, rules_evaluated = :rulesEvaluated, findings = :findings, opened = :opened,
                   cleared = :cleared, failure = :failure, finished_at = now()
             WHERE organization_id = :orgId AND id = :id
            """;

    static final String LIST_CHECK_RUNS = """
            SELECT id, trigger, started_by, status, rules_evaluated, findings, opened, cleared, failure, started_at,
                   finished_at
              FROM integrity_check_runs
             WHERE organization_id = :orgId
             ORDER BY started_at DESC
             LIMIT 50
            """;

    /**
     * Opens an incident, or refreshes the open/acknowledged one for the same rule and subject
     * (the partial unique index makes a duplicate impossible). Returns whether it is new.
     */
    static final String UPSERT_INCIDENT = """
            INSERT INTO integrity_incidents (organization_id, rule_id, rule_version, subject_type, subject_id, severity,
                                             title, detail, first_run_id, last_run_id)
            VALUES (:orgId, :ruleId, :ruleVersion, :subjectType, :subjectId, :severity, :title,
                    CAST(:detail AS jsonb), :runId, :runId)
            ON CONFLICT (organization_id, rule_id, subject_type, subject_id) WHERE status IN ('OPEN', 'ACKNOWLEDGED')
            DO UPDATE SET last_seen_at = now(), last_run_id = EXCLUDED.last_run_id,
                          occurrences = integrity_incidents.occurrences + 1, title = EXCLUDED.title,
                          detail = EXCLUDED.detail, rule_version = EXCLUDED.rule_version, severity = EXCLUDED.severity
            RETURNING id, (xmax = 0) AS inserted
            """;

    /** Incidents of a rule that this run no longer found: the condition is gone. */
    static final String CLEAR_UNSEEN_INCIDENTS = """
            UPDATE integrity_incidents
               SET status = 'CLEARED', closed_at = now(), version = version + 1
             WHERE organization_id = :orgId AND rule_id = :ruleId AND status IN ('OPEN', 'ACKNOWLEDGED')
               AND last_run_id <> :runId
            RETURNING id
            """;

    static final String INSERT_INCIDENT_EVENT = """
            INSERT INTO integrity_incident_events (organization_id, incident_id, from_status, to_status, actor_user_id,
                                                   note, run_id)
            VALUES (:orgId, :incidentId, :fromStatus, :toStatus, :actorUserId, :note, :runId)
            """;

    private static final String INCIDENT_COLUMNS = """
            SELECT i.id, i.rule_id, r.code AS rule_code, r.name AS rule_name, i.rule_version, i.subject_type,
                   i.subject_id, i.severity, i.status, i.title, CAST(i.detail AS text) AS detail, i.occurrences,
                   i.first_seen_at, i.last_seen_at, i.closed_at, i.version
              FROM integrity_incidents i
              JOIN integrity_rules r ON r.organization_id = i.organization_id AND r.id = i.rule_id
            """;

    static final String LIST_INTEGRITY_INCIDENTS = INCIDENT_COLUMNS + """
             WHERE i.organization_id = :orgId
               AND (CAST(:status AS text) IS NULL OR i.status = CAST(:status AS text))
               AND (CAST(:severity AS text) IS NULL OR i.severity = CAST(:severity AS text))
             ORDER BY CASE i.severity WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 ELSE 3 END,
                      i.last_seen_at DESC, i.id
             LIMIT :limit OFFSET :offset
            """;

    static final String COUNT_INTEGRITY_INCIDENTS = """
            SELECT count(*) FROM integrity_incidents
             WHERE organization_id = :orgId
               AND (CAST(:status AS text) IS NULL OR status = CAST(:status AS text))
               AND (CAST(:severity AS text) IS NULL OR severity = CAST(:severity AS text))
            """;

    static final String FIND_INTEGRITY_INCIDENT = INCIDENT_COLUMNS + """
             WHERE i.organization_id = :orgId AND i.id = :id
            """;

    static final String SET_INTEGRITY_INCIDENT_STATUS = """
            UPDATE integrity_incidents
               SET status = :status, version = version + 1,
                   closed_at = CASE WHEN CAST(:status AS text) IN ('RESOLVED', 'CLEARED') THEN now() ELSE NULL END
             WHERE organization_id = :orgId AND id = :id AND version = :expectedVersion
               AND status IN ('OPEN', 'ACKNOWLEDGED')
            """;

    static final String LIST_INCIDENT_EVENTS = """
            SELECT e.from_status, e.to_status, e.actor_user_id, u.display_name AS actor_name, e.note, e.run_id,
                   e.occurred_at
              FROM integrity_incident_events e
              LEFT JOIN users u ON u.id = e.actor_user_id
             WHERE e.organization_id = :orgId AND e.incident_id = :incidentId
             ORDER BY e.occurred_at, e.id
            """;

    static final Map<String, String> EVALUATORS = Map.of(
            "ORDER_TOTAL_MATCHES_LINES", RULE_ORDER_TOTALS,
            "INVOICE_TOTAL_MATCHES_LINES", RULE_INVOICE_TOTALS,
            "DERIVED_VALUE_MATCHES_SOURCE", RULE_DERIVED_VALUES,
            "REQUIRED_PROVENANCE", RULE_REQUIRED_PROVENANCE,
            "AUDIT_CHAIN_INTACT", RULE_AUDIT_CHAIN);

    static final int MAX_FINDINGS_PER_RULE = 500;

    private final JdbcClient jdbc;
    private final Json json;

    IntegrityRepository(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    List<Finding> evaluate(UUID orgId, String kind, BigDecimal tolerance) {
        String sql = EVALUATORS.get(kind);
        var spec = jdbc.sql(sql).param("orgId", orgId);
        if (sql.contains(":tolerance")) {
            spec = spec.param("tolerance", tolerance);
        }
        if (sql.contains(":limit")) {
            spec = spec.param("limit", MAX_FINDINGS_PER_RULE);
        }
        return spec.query((rs, n) -> new Finding(rs.getString("subject_type"), rs.getObject("subject_id", UUID.class),
                rs.getString("title"), rs.getString("detail"))).list();
    }

    List<Rule> rules(UUID orgId) {
        return jdbc.sql(LIST_INTEGRITY_RULES).param("orgId", orgId).query(this::mapRule).list();
    }

    Optional<Rule> rule(UUID orgId, UUID id) {
        return jdbc.sql(FIND_INTEGRITY_RULE).param("orgId", orgId).param("id", id).query(this::mapRule).optional();
    }

    Rule insertRule(UUID orgId, String code, String kind, String name, String severity, boolean enabled, Object config,
                    UUID by) {
        return jdbc.sql(INSERT_INTEGRITY_RULE).param("orgId", orgId).param("code", code).param("kind", kind)
                .param("name", name).param("severity", severity).param("enabled", enabled)
                .param("config", json.write(config)).param("createdBy", by).query(this::mapRule).single();
    }

    Optional<Rule> updateRule(UUID orgId, UUID id, long expectedVersion, String name, String severity, boolean enabled,
                              Object config) {
        return jdbc.sql(UPDATE_INTEGRITY_RULE).param("orgId", orgId).param("id", id).param("expectedVersion", expectedVersion)
                .param("name", name).param("severity", severity).param("enabled", enabled)
                .param("config", json.write(config)).query(this::mapRule).optional();
    }

    void insertVersion(UUID orgId, Rule r, UUID by) {
        jdbc.sql(INSERT_RULE_VERSION).param("orgId", orgId).param("ruleId", r.id()).param("version", r.version())
                .param("kind", r.kind()).param("name", r.name()).param("severity", r.severity())
                .param("enabled", r.enabled()).param("config", json.write(r.config())).param("changedBy", by).update();
    }

    List<RuleVersion> versions(UUID orgId, UUID ruleId) {
        return jdbc.sql(LIST_RULE_VERSIONS).param("orgId", orgId).param("ruleId", ruleId)
                .query((rs, n) -> new RuleVersion(rs.getLong("version"), rs.getString("kind"), rs.getString("name"),
                        rs.getString("severity"), rs.getBoolean("enabled"), json.tree(rs.getString("config")),
                        rs.getObject("changed_by", UUID.class), rs.getString("changed_by_name"),
                        Db.instant(rs, "changed_at")))
                .list();
    }

    List<UUID> organizationsWithRules() {
        return jdbc.sql(ORGANIZATIONS_WITH_RULES).query(UUID.class).list();
    }

    UUID startRun(UUID orgId, String trigger, UUID startedBy) {
        return jdbc.sql(INSERT_CHECK_RUN).param("orgId", orgId).param("trigger", trigger).param("startedBy", startedBy)
                .query(UUID.class).single();
    }

    void finishRun(UUID orgId, UUID id, String status, int rules, int findings, int opened, int cleared, String failure) {
        jdbc.sql(FINISH_CHECK_RUN).param("orgId", orgId).param("id", id).param("status", status)
                .param("rulesEvaluated", rules).param("findings", findings).param("opened", opened)
                .param("cleared", cleared).param("failure", failure).update();
    }

    List<Run> runs(UUID orgId) {
        return jdbc.sql(LIST_CHECK_RUNS).param("orgId", orgId)
                .query((rs, n) -> new Run(rs.getObject("id", UUID.class), rs.getString("trigger"),
                        rs.getObject("started_by", UUID.class), rs.getString("status"), rs.getInt("rules_evaluated"),
                        rs.getInt("findings"), rs.getInt("opened"), rs.getInt("cleared"), rs.getString("failure"),
                        Db.instant(rs, "started_at"), Db.instant(rs, "finished_at")))
                .list();
    }

    /** @return incident id and whether it was newly opened */
    Map.Entry<UUID, Boolean> upsertIncident(UUID orgId, Rule rule, UUID runId, Finding f) {
        return jdbc.sql(UPSERT_INCIDENT).param("orgId", orgId).param("ruleId", rule.id()).param("ruleVersion", rule.version())
                .param("subjectType", f.subjectType()).param("subjectId", f.subjectId()).param("severity", rule.severity())
                .param("title", f.title().length() > 300 ? f.title().substring(0, 300) : f.title())
                .param("detail", f.detail()).param("runId", runId)
                .query((rs, n) -> Map.entry(rs.getObject("id", UUID.class), rs.getBoolean("inserted"))).single();
    }

    List<UUID> clearUnseen(UUID orgId, UUID ruleId, UUID runId) {
        return jdbc.sql(CLEAR_UNSEEN_INCIDENTS).param("orgId", orgId).param("ruleId", ruleId).param("runId", runId)
                .query(UUID.class).list();
    }

    void event(UUID orgId, UUID incidentId, String from, String to, UUID actor, String note, UUID runId) {
        jdbc.sql(INSERT_INCIDENT_EVENT).param("orgId", orgId).param("incidentId", incidentId).param("fromStatus", from)
                .param("toStatus", to).param("actorUserId", actor).param("note", note).param("runId", runId).update();
    }

    List<Incident> incidents(UUID orgId, String status, String severity, int limit, long offset) {
        return jdbc.sql(LIST_INTEGRITY_INCIDENTS).param("orgId", orgId).param("status", status)
                .param("severity", severity).param("limit", limit).param("offset", offset)
                .query(this::mapIncident).list();
    }

    long countIncidents(UUID orgId, String status, String severity) {
        return jdbc.sql(COUNT_INTEGRITY_INCIDENTS).param("orgId", orgId).param("status", status)
                .param("severity", severity).query(Long.class).single();
    }

    Optional<Incident> incident(UUID orgId, UUID id) {
        return jdbc.sql(FIND_INTEGRITY_INCIDENT).param("orgId", orgId).param("id", id).query(this::mapIncident).optional();
    }

    boolean setStatus(UUID orgId, UUID id, long expectedVersion, String status) {
        return jdbc.sql(SET_INTEGRITY_INCIDENT_STATUS).param("orgId", orgId).param("id", id)
                .param("expectedVersion", expectedVersion).param("status", status).update() == 1;
    }

    List<Event> events(UUID orgId, UUID incidentId) {
        return jdbc.sql(LIST_INCIDENT_EVENTS).param("orgId", orgId).param("incidentId", incidentId)
                .query((rs, n) -> new Event(rs.getString("from_status"), rs.getString("to_status"),
                        rs.getObject("actor_user_id", UUID.class), rs.getString("actor_name"), rs.getString("note"),
                        rs.getObject("run_id", UUID.class), Db.instant(rs, "occurred_at")))
                .list();
    }

    private Rule mapRule(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Rule(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("kind"), rs.getString("name"),
                rs.getString("severity"), rs.getBoolean("enabled"), json.tree(rs.getString("config")), rs.getLong("version"),
                rs.getObject("created_by", UUID.class), Db.instant(rs, "created_at"), Db.instant(rs, "updated_at"));
    }

    private Incident mapIncident(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new Incident(rs.getObject("id", UUID.class), rs.getObject("rule_id", UUID.class), rs.getString("rule_code"),
                rs.getString("rule_name"), rs.getLong("rule_version"), rs.getString("subject_type"),
                rs.getObject("subject_id", UUID.class), rs.getString("severity"), rs.getString("status"),
                rs.getString("title"), json.tree(rs.getString("detail")), rs.getInt("occurrences"),
                Db.instant(rs, "first_seen_at"), Db.instant(rs, "last_seen_at"), Db.instant(rs, "closed_at"),
                rs.getLong("version"));
    }
}
