package com.atlas.recovery;

import com.atlas.common.db.Db;
import com.atlas.common.json.Json;
import com.atlas.recovery.engine.EntityKey;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL for approval, execution and verification (V7). Business-record writes
 * are per entity type and touch only the columns a recovery step may change;
 * totals are written only if they equal the sum of their lines in the same
 * statement, and credit notes only if they fit in what remains on the invoice.
 */
@Repository
class ExecutionRepository {

    record PlanForExecution(UUID id, UUID incidentId, UUID createdBy, String status, String planJson,
                            String originType, UUID originId, UUID faultyChangeEventId) {
    }

    record DecisionRow(UUID planId, String decision, String fingerprint, String comment, UUID decidedBy,
                       String decidedByName, Instant decidedAt) {
    }

    record ActionRow(int stepNo, String action, String entityType, UUID entityId, long expectedVersion, String status,
                     String detail, Long resultVersion, UUID resultEntityId, UUID txnId, UUID executedBy,
                     Instant recordedAt, UUID acknowledgedBy, Instant acknowledgedAt, String acknowledgement) {
    }

    record VerificationRow(UUID id, String outcome, int checkCount, int failedCount, int pendingManual, Object checks,
                           UUID verifiedBy, Instant verifiedAt) {
    }

    record LatestState(long version, Map<String, Object> after) {
    }

    /** Entity types a recovery step may write, and their tables. Nothing else is ever interpolated into SQL. */
    static final Map<String, String> TABLES = Map.of(
            "product", "products", "order", "orders", "order_line", "order_lines",
            "invoice", "invoices", "invoice_line", "invoice_lines", "credit_note", "credit_notes");

    // ------------------------------------------------------------------ plans and decisions

    static final String LOCK_PLAN = """
            SELECT p.id, p.incident_id, p.created_by, p.status, CAST(p.plan AS text) AS plan,
                   ce.entity_type AS origin_type, ce.entity_id AS origin_id, i.faulty_change_event_id
              FROM recovery_plans p
              JOIN recovery_incidents i ON i.organization_id = p.organization_id AND i.id = p.incident_id
              JOIN change_events ce ON ce.organization_id = i.organization_id AND ce.id = i.faulty_change_event_id
             WHERE p.organization_id = :orgId AND p.id = :id
               FOR UPDATE OF p
            """;

    /**
     * The per-step lock. Plan content is immutable (V6 trigger), so after the
     * first full read only the status is needed; re-reading the plan JSON on
     * every step made execution quadratic (Phase 7 benchmark: 97 ms vs 0.5 ms
     * per step for a 6,273-step plan).
     */
    static final String LOCK_PLAN_STATUS = """
            SELECT status FROM recovery_plans WHERE organization_id = :orgId AND id = :id FOR UPDATE
            """;

    static final String SET_PLAN_STATUS = """
            UPDATE recovery_plans SET status = :to
             WHERE organization_id = :orgId AND id = :id AND status = :from
            """;

    static final String ANY_PLAN_EXECUTING = """
            SELECT EXISTS (SELECT 1 FROM recovery_plans
                            WHERE organization_id = :orgId AND incident_id = :incidentId AND status = 'EXECUTING')
            """;

    static final String INSERT_DECISION = """
            INSERT INTO recovery_decisions (organization_id, plan_id, decision, fingerprint, comment, decided_by)
            VALUES (:orgId, :planId, :decision, :fingerprint, :comment, :decidedBy)
            """;

    static final String LIST_DECISIONS = """
            SELECT d.plan_id, d.decision, d.fingerprint, d.comment, d.decided_by, u.display_name AS decided_by_name,
                   d.decided_at
              FROM recovery_decisions d
              LEFT JOIN users u ON u.id = d.decided_by
             WHERE d.organization_id = :orgId AND d.plan_id = :planId
             ORDER BY d.decided_at, d.id
            """;

    // ------------------------------------------------------------------ steps

    static final String LIST_ACTIONS = """
            SELECT step_no, action, entity_type, entity_id, expected_version, status, detail, result_version,
                   result_entity_id, txn_id, executed_by, recorded_at, acknowledged_by, acknowledged_at, acknowledgement
              FROM recovery_actions
             WHERE organization_id = :orgId AND plan_id = :planId
             ORDER BY step_no
            """;

    static final String INSERT_ACTION = """
            INSERT INTO recovery_actions (organization_id, plan_id, step_no, action, entity_type, entity_id,
                                          expected_version, status, detail, result_version, result_entity_id,
                                          txn_id, executed_by)
            VALUES (:orgId, :planId, :stepNo, :action, :entityType, :entityId, :expectedVersion, :status, :detail,
                    :resultVersion, :resultEntityId, :txnId, :executedBy)
            """;

    static final String ACKNOWLEDGE_ACTION = """
            UPDATE recovery_actions
               SET status = 'ACKNOWLEDGED', acknowledged_by = :userId, acknowledged_at = now(), acknowledgement = :note
             WHERE organization_id = :orgId AND plan_id = :planId AND step_no = :stepNo
               AND status = 'AWAITING_ACKNOWLEDGEMENT'
            """;

    static final String CREDITED_BY_INCIDENT = """
            SELECT a.entity_id AS invoice_id, sum(cn.amount) AS credited
              FROM recovery_actions a
              JOIN recovery_plans p ON p.organization_id = a.organization_id AND p.id = a.plan_id
              JOIN credit_notes cn ON cn.organization_id = a.organization_id AND cn.id = a.result_entity_id
             WHERE a.organization_id = :orgId AND p.incident_id = :incidentId
               AND a.action = 'ISSUE_CREDIT_NOTE' AND a.status = 'DONE'
             GROUP BY a.entity_id
            """;

    // ------------------------------------------------------------------ verifications

    static final String INSERT_VERIFICATION = """
            INSERT INTO recovery_verifications (organization_id, plan_id, outcome, check_count, failed_count,
                                                pending_manual, checks, verified_by)
            VALUES (:orgId, :planId, :outcome, :checkCount, :failedCount, :pendingManual, CAST(:checks AS jsonb),
                    :verifiedBy)
            """;

    static final String LIST_VERIFICATIONS = """
            SELECT id, outcome, check_count, failed_count, pending_manual, CAST(checks AS text) AS checks,
                   verified_by, verified_at
              FROM recovery_verifications
             WHERE organization_id = :orgId AND plan_id = :planId
             ORDER BY verified_at DESC, id
            """;

    // ------------------------------------------------------------------ records

    /** %s is a table name from {@link #TABLES} only. Locks the row a step is about to change. */
    static final String LOCK_RECORD = """
            SELECT id FROM %s WHERE organization_id = :orgId AND id = :id FOR UPDATE
            """;

    /** The version history knows; recovery checks plans against it (rows of some tables carry no version). */
    static final String LATEST_STATE = """
            SELECT entity_version, CAST(after_state AS text) AS after_state
              FROM change_events
             WHERE organization_id = :orgId AND entity_type = :entityType AND entity_id = :entityId
             ORDER BY entity_version DESC
             LIMIT 1
            """;

    /** %s is a table name from {@link #TABLES} only. The record as stored, keyed by column name. */
    static final String CURRENT_ROW = """
            SELECT CAST(to_jsonb(t) AS text) FROM %s t WHERE t.organization_id = :orgId AND t.id = :id
            """;

    static final String RECOVER_PRODUCT = """
            UPDATE products
               SET name = :name, unit_price = :unitPrice, unit_cost = :unitCost, active = :active,
                   updated_at = now(), version = version + 1
             WHERE organization_id = :orgId AND id = :id
            RETURNING version
            """;

    /** line_total is generated from quantity × unit_price; it is returned so the caller can check it. */
    static final String RECOVER_ORDER_LINE = """
            UPDATE order_lines SET unit_price = :unitPrice, version = version + 1
             WHERE organization_id = :orgId AND id = :id
            RETURNING version, line_total
            """;

    static final String RECOVER_ORDER_TOTAL = """
            UPDATE orders SET total_amount = :total, updated_at = now(), version = version + 1
             WHERE organization_id = :orgId AND id = :id
               AND CAST(:total AS numeric) = (SELECT COALESCE(sum(line_total), 0) FROM order_lines
                                               WHERE organization_id = :orgId AND order_id = :id)
            RETURNING version
            """;

    /** The V2 trigger refuses this unless the invoice is still a DRAFT. */
    static final String RECOVER_INVOICE_LINE = """
            UPDATE invoice_lines SET unit_price = :unitPrice
             WHERE organization_id = :orgId AND id = :id
            RETURNING line_total
            """;

    /** The V2 trigger refuses this for an ISSUED or VOID invoice. */
    static final String RECOVER_INVOICE_TOTAL = """
            UPDATE invoices SET total_amount = :total, updated_at = now(), version = version + 1
             WHERE organization_id = :orgId AND id = :id
               AND CAST(:total AS numeric) = (SELECT COALESCE(sum(line_total), 0) FROM invoice_lines
                                               WHERE organization_id = :orgId AND invoice_id = :id)
            RETURNING version
            """;

    /**
     * Only against an ISSUED invoice, and only within what is not yet credited
     * (the invoice row is locked by the caller, as BillingService does).
     */
    static final String RECOVERY_CREDIT_NOTE = """
            INSERT INTO credit_notes (organization_id, credit_note_number, invoice_id, amount, reason, status, issued_at)
            SELECT :orgId, :creditNoteNumber, i.id, :amount, :reason, 'ISSUED', now()
              FROM invoices i
             WHERE i.organization_id = :orgId AND i.id = :invoiceId AND i.status = 'ISSUED'
               AND CAST(:amount AS numeric) > 0
               AND CAST(:amount AS numeric) <= i.total_amount - (SELECT COALESCE(sum(amount), 0) FROM credit_notes
                                                                  WHERE organization_id = :orgId AND invoice_id = i.id)
            RETURNING id, invoice_id, credit_note_number, amount, reason, status, issued_at, created_at, version
            """;

    static final String SUM_ORDER_LINES = """
            SELECT COALESCE(sum(line_total), 0) FROM order_lines WHERE organization_id = :orgId AND order_id = :id
            """;

    static final String SUM_INVOICE_LINES = """
            SELECT COALESCE(sum(line_total), 0) FROM invoice_lines WHERE organization_id = :orgId AND invoice_id = :id
            """;

    static final String CREDIT_NOTE_AMOUNT = """
            SELECT amount FROM credit_notes WHERE organization_id = :orgId AND id = :id
            """;

    /** Records changed by recovery inside this plan's step transactions. */
    static final String WRITTEN_BY_PLAN = """
            SELECT DISTINCT ce.entity_type, ce.entity_id
              FROM change_events ce
             WHERE ce.organization_id = :orgId AND ce.actor_kind = 'RECOVERY'
               AND ce.txn_id IN (SELECT txn_id FROM recovery_actions WHERE organization_id = :orgId AND plan_id = :planId)
            """;

    private final JdbcClient jdbc;
    private final Json json;

    ExecutionRepository(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    static String table(String entityType) {
        String t = TABLES.get(entityType);
        if (t == null) {
            throw new IllegalArgumentException("Recovery does not write " + entityType);
        }
        return t;
    }

    Optional<PlanForExecution> lockPlan(UUID orgId, UUID planId) {
        return jdbc.sql(LOCK_PLAN).param("orgId", orgId).param("id", planId)
                .query((rs, n) -> new PlanForExecution(rs.getObject("id", UUID.class),
                        rs.getObject("incident_id", UUID.class), rs.getObject("created_by", UUID.class),
                        rs.getString("status"), rs.getString("plan"), rs.getString("origin_type"),
                        rs.getObject("origin_id", UUID.class), rs.getObject("faulty_change_event_id", UUID.class)))
                .optional();
    }

    Optional<String> lockPlanStatus(UUID orgId, UUID planId) {
        return jdbc.sql(LOCK_PLAN_STATUS).param("orgId", orgId).param("id", planId).query(String.class).optional();
    }

    boolean setPlanStatus(UUID orgId, UUID planId, String from, String to) {
        return jdbc.sql(SET_PLAN_STATUS).param("orgId", orgId).param("id", planId).param("from", from).param("to", to)
                .update() == 1;
    }

    boolean anyExecuting(UUID orgId, UUID incidentId) {
        return jdbc.sql(ANY_PLAN_EXECUTING).param("orgId", orgId).param("incidentId", incidentId)
                .query(Boolean.class).single();
    }

    void insertDecision(UUID orgId, UUID planId, String decision, String fingerprint, String comment, UUID by) {
        jdbc.sql(INSERT_DECISION).param("orgId", orgId).param("planId", planId).param("decision", decision)
                .param("fingerprint", fingerprint).param("comment", comment).param("decidedBy", by).update();
    }

    List<DecisionRow> decisions(UUID orgId, UUID planId) {
        return jdbc.sql(LIST_DECISIONS).param("orgId", orgId).param("planId", planId)
                .query((rs, n) -> new DecisionRow(rs.getObject("plan_id", UUID.class), rs.getString("decision"),
                        rs.getString("fingerprint"), rs.getString("comment"), rs.getObject("decided_by", UUID.class),
                        rs.getString("decided_by_name"), Db.instant(rs, "decided_at")))
                .list();
    }

    List<ActionRow> actions(UUID orgId, UUID planId) {
        return jdbc.sql(LIST_ACTIONS).param("orgId", orgId).param("planId", planId)
                .query((rs, n) -> mapAction(rs)).list();
    }

    void insertAction(UUID orgId, UUID planId, int stepNo, String action, EntityKey key, long expectedVersion,
                      String status, String detail, Long resultVersion, UUID resultId, UUID txnId, UUID by) {
        jdbc.sql(INSERT_ACTION).param("orgId", orgId).param("planId", planId).param("stepNo", stepNo)
                .param("action", action).param("entityType", key.entityType()).param("entityId", key.entityId())
                .param("expectedVersion", expectedVersion).param("status", status).param("detail", detail)
                .param("resultVersion", resultVersion).param("resultEntityId", resultId).param("txnId", txnId)
                .param("executedBy", by).update();
    }

    boolean acknowledge(UUID orgId, UUID planId, int stepNo, UUID userId, String note) {
        return jdbc.sql(ACKNOWLEDGE_ACTION).param("orgId", orgId).param("planId", planId).param("stepNo", stepNo)
                .param("userId", userId).param("note", note).update() == 1;
    }

    Map<EntityKey, BigDecimal> creditedByIncident(UUID orgId, UUID incidentId) {
        Map<EntityKey, BigDecimal> out = new LinkedHashMap<>();
        jdbc.sql(CREDITED_BY_INCIDENT).param("orgId", orgId).param("incidentId", incidentId)
                .query((ResultSet rs) -> {
                    while (rs.next()) {
                        out.put(new EntityKey("invoice", rs.getObject("invoice_id", UUID.class)),
                                rs.getBigDecimal("credited"));
                    }
                    return null;
                });
        return out;
    }

    void insertVerification(UUID orgId, UUID planId, String outcome, int checks, int failed, int pending,
                            String checksJson, UUID by) {
        jdbc.sql(INSERT_VERIFICATION).param("orgId", orgId).param("planId", planId).param("outcome", outcome)
                .param("checkCount", checks).param("failedCount", failed).param("pendingManual", pending)
                .param("checks", checksJson).param("verifiedBy", by).update();
    }

    List<VerificationRow> verifications(UUID orgId, UUID planId) {
        return jdbc.sql(LIST_VERIFICATIONS).param("orgId", orgId).param("planId", planId)
                .query((rs, n) -> new VerificationRow(rs.getObject("id", UUID.class), rs.getString("outcome"),
                        rs.getInt("check_count"), rs.getInt("failed_count"), rs.getInt("pending_manual"),
                        json.tree(rs.getString("checks")), rs.getObject("verified_by", UUID.class),
                        Db.instant(rs, "verified_at")))
                .list();
    }

    boolean lockRecord(UUID orgId, EntityKey key) {
        return jdbc.sql(LOCK_RECORD.formatted(table(key.entityType()))).param("orgId", orgId).param("id", key.entityId())
                .query(UUID.class).optional().isPresent();
    }

    Optional<LatestState> latestState(UUID orgId, EntityKey key) {
        return jdbc.sql(LATEST_STATE).param("orgId", orgId).param("entityType", key.entityType())
                .param("entityId", key.entityId())
                .query((rs, n) -> new LatestState(rs.getLong("entity_version"), json.exactObject(rs.getString("after_state"))))
                .optional();
    }

    Optional<Map<String, Object>> currentRow(UUID orgId, EntityKey key) {
        return jdbc.sql(CURRENT_ROW.formatted(table(key.entityType()))).param("orgId", orgId).param("id", key.entityId())
                .query(String.class).optional().map(json::exactObject);
    }

    Optional<Long> recoverProduct(UUID orgId, UUID id, Map<String, Object> s) {
        return jdbc.sql(RECOVER_PRODUCT).param("orgId", orgId).param("id", id).param("name", s.get("name"))
                .param("unitPrice", s.get("unit_price")).param("unitCost", s.get("unit_cost"))
                .param("active", s.get("active")).query(Long.class).optional();
    }

    record LineResult(Long version, BigDecimal lineTotal) {
    }

    Optional<LineResult> recoverOrderLine(UUID orgId, UUID id, Object unitPrice) {
        return jdbc.sql(RECOVER_ORDER_LINE).param("orgId", orgId).param("id", id).param("unitPrice", unitPrice)
                .query((rs, n) -> new LineResult(rs.getLong("version"), rs.getBigDecimal("line_total"))).optional();
    }

    Optional<Long> recoverOrderTotal(UUID orgId, UUID id, Object total) {
        return jdbc.sql(RECOVER_ORDER_TOTAL).param("orgId", orgId).param("id", id).param("total", total)
                .query(Long.class).optional();
    }

    Optional<BigDecimal> recoverInvoiceLine(UUID orgId, UUID id, Object unitPrice) {
        return jdbc.sql(RECOVER_INVOICE_LINE).param("orgId", orgId).param("id", id).param("unitPrice", unitPrice)
                .query(BigDecimal.class).optional();
    }

    Optional<Long> recoverInvoiceTotal(UUID orgId, UUID id, Object total) {
        return jdbc.sql(RECOVER_INVOICE_TOTAL).param("orgId", orgId).param("id", id).param("total", total)
                .query(Long.class).optional();
    }

    record CreditNoteRow(UUID id, UUID invoiceId, String number, BigDecimal amount, String reason, String status,
                         Instant issuedAt, Instant createdAt, long version) {
    }

    Optional<CreditNoteRow> recoveryCreditNote(UUID orgId, String number, UUID invoiceId, BigDecimal amount,
                                               String reason) {
        return jdbc.sql(RECOVERY_CREDIT_NOTE).param("orgId", orgId).param("creditNoteNumber", number)
                .param("invoiceId", invoiceId).param("amount", amount).param("reason", reason)
                .query((rs, n) -> new CreditNoteRow(rs.getObject("id", UUID.class), rs.getObject("invoice_id", UUID.class),
                        rs.getString("credit_note_number"), rs.getBigDecimal("amount"), rs.getString("reason"),
                        rs.getString("status"), Db.instant(rs, "issued_at"), Db.instant(rs, "created_at"),
                        rs.getLong("version")))
                .optional();
    }

    BigDecimal sumOfLines(UUID orgId, EntityKey key) {
        String sql = switch (key.entityType()) {
            case "order" -> SUM_ORDER_LINES;
            case "invoice" -> SUM_INVOICE_LINES;
            default -> throw new IllegalArgumentException(key.entityType() + " has no lines");
        };
        return jdbc.sql(sql).param("orgId", orgId).param("id", key.entityId()).query(BigDecimal.class).single();
    }

    Optional<BigDecimal> creditNoteAmount(UUID orgId, UUID id) {
        return jdbc.sql(CREDIT_NOTE_AMOUNT).param("orgId", orgId).param("id", id).query(BigDecimal.class).optional();
    }

    List<EntityKey> writtenByPlan(UUID orgId, UUID planId) {
        return jdbc.sql(WRITTEN_BY_PLAN).param("orgId", orgId).param("planId", planId)
                .query((rs, n) -> new EntityKey(rs.getString("entity_type"), rs.getObject("entity_id", UUID.class)))
                .list();
    }

    private static ActionRow mapAction(ResultSet rs) throws SQLException {
        long rv = rs.getLong("result_version");
        Long resultVersion = rs.wasNull() ? null : rv;
        return new ActionRow(rs.getInt("step_no"), rs.getString("action"), rs.getString("entity_type"),
                rs.getObject("entity_id", UUID.class), rs.getLong("expected_version"), rs.getString("status"),
                rs.getString("detail"), resultVersion, rs.getObject("result_entity_id", UUID.class),
                rs.getObject("txn_id", UUID.class), rs.getObject("executed_by", UUID.class),
                Db.instant(rs, "recorded_at"), rs.getObject("acknowledged_by", UUID.class),
                Db.instant(rs, "acknowledged_at"), rs.getString("acknowledgement"));
    }
}
