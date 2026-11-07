package com.atlas.billing;

import com.atlas.common.db.Db;
import com.atlas.common.web.PageQuery;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class InvoiceRepository {

    record Credited(BigDecimal amount, long notes) {
    }

    static final String INSERT_INVOICE = """
            INSERT INTO invoices (organization_id, invoice_number, order_id, total_amount)
            VALUES (:orgId, :invoiceNumber, :orderId, :totalAmount)
            RETURNING id, organization_id, invoice_number, order_id, status, total_amount, issued_at,
                      created_at, updated_at, version
            """;

    static final String INSERT_INVOICE_LINE = """
            INSERT INTO invoice_lines (organization_id, invoice_id, line_no, order_line_id, description, quantity, unit_price)
            VALUES (:orgId, :invoiceId, :lineNo, :orderLineId, :description, :quantity, :unitPrice)
            RETURNING id, invoice_id, line_no, order_line_id, description, quantity, unit_price, line_total
            """;

    static final String FIND_INVOICE = """
            SELECT id, organization_id, invoice_number, order_id, status, total_amount, issued_at,
                   created_at, updated_at, version
              FROM invoices
             WHERE organization_id = :orgId AND id = :id
            """;

    static final String FIND_INVOICE_FOR_UPDATE = """
            SELECT id, organization_id, invoice_number, order_id, status, total_amount, issued_at,
                   created_at, updated_at, version
              FROM invoices
             WHERE organization_id = :orgId AND id = :id
               FOR UPDATE
            """;

    static final String LIST_INVOICE_LINES = """
            SELECT id, invoice_id, line_no, order_line_id, description, quantity, unit_price, line_total
              FROM invoice_lines
             WHERE organization_id = :orgId AND invoice_id = :invoiceId
             ORDER BY line_no
            """;

    static final String ACTIVE_INVOICE_EXISTS = """
            SELECT EXISTS (SELECT 1 FROM invoices
                            WHERE organization_id = :orgId AND order_id = :orderId AND status <> 'VOID')
            """;

    /** ISSUED stamps issued_at once; VOID keeps it (the database trigger requires that). */
    static final String SET_INVOICE_STATUS = """
            UPDATE invoices
               SET status = CAST(:status AS varchar),
                   issued_at = CASE WHEN CAST(:status AS varchar) = 'ISSUED' THEN now() ELSE issued_at END,
                   updated_at = now(), version = version + 1
             WHERE organization_id = :orgId AND id = :id AND version = :expectedVersion
            RETURNING id, organization_id, invoice_number, order_id, status, total_amount, issued_at,
                      created_at, updated_at, version
            """;

    static final String LIST_INVOICES = """
            SELECT id, organization_id, invoice_number, order_id, status, total_amount, issued_at,
                   created_at, updated_at, version
              FROM invoices
             WHERE organization_id = :orgId
               AND (CAST(:status AS varchar) IS NULL OR status = CAST(:status AS varchar))
               AND (CAST(:orderId AS uuid) IS NULL OR order_id = CAST(:orderId AS uuid))
             ORDER BY %s, id
             LIMIT :limit OFFSET :offset
            """;

    static final String COUNT_INVOICES = """
            SELECT count(*)
              FROM invoices
             WHERE organization_id = :orgId
               AND (CAST(:status AS varchar) IS NULL OR status = CAST(:status AS varchar))
               AND (CAST(:orderId AS uuid) IS NULL OR order_id = CAST(:orderId AS uuid))
            """;

    static final String SUM_CREDITED = """
            SELECT COALESCE(sum(amount), 0) AS credited, count(*) AS notes
              FROM credit_notes
             WHERE organization_id = :orgId AND invoice_id = :invoiceId
            """;

    static final String INSERT_CREDIT_NOTE = """
            INSERT INTO credit_notes (organization_id, credit_note_number, invoice_id, amount, reason, status, issued_at)
            VALUES (:orgId, :creditNoteNumber, :invoiceId, :amount, :reason, 'ISSUED', now())
            RETURNING id, invoice_id, credit_note_number, amount, reason, status, issued_at, created_at, version
            """;

    static final String LIST_CREDIT_NOTES = """
            SELECT id, invoice_id, credit_note_number, amount, reason, status, issued_at, created_at, version
              FROM credit_notes
             WHERE organization_id = :orgId AND invoice_id = :invoiceId
             ORDER BY created_at, credit_note_number
            """;

    private final JdbcClient jdbc;

    InvoiceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Invoice insert(UUID orgId, String invoiceNumber, UUID orderId, BigDecimal totalAmount) {
        return jdbc.sql(INSERT_INVOICE).param("orgId", orgId).param("invoiceNumber", invoiceNumber)
                .param("orderId", orderId).param("totalAmount", totalAmount)
                .query(InvoiceRepository::mapHeader).single();
    }

    InvoiceLine insertLine(UUID orgId, UUID invoiceId, int lineNo, UUID orderLineId, String description,
                           int quantity, BigDecimal unitPrice) {
        return jdbc.sql(INSERT_INVOICE_LINE).param("orgId", orgId).param("invoiceId", invoiceId)
                .param("lineNo", lineNo).param("orderLineId", orderLineId).param("description", description)
                .param("quantity", quantity).param("unitPrice", unitPrice)
                .query(InvoiceRepository::mapLine).single();
    }

    Optional<Invoice> find(UUID orgId, UUID id) {
        return jdbc.sql(FIND_INVOICE).param("orgId", orgId).param("id", id)
                .query(InvoiceRepository::mapHeader).optional().map(i -> i.withLines(lines(orgId, id)));
    }

    Optional<Invoice> findForUpdate(UUID orgId, UUID id) {
        return jdbc.sql(FIND_INVOICE_FOR_UPDATE).param("orgId", orgId).param("id", id)
                .query(InvoiceRepository::mapHeader).optional().map(i -> i.withLines(lines(orgId, id)));
    }

    List<InvoiceLine> lines(UUID orgId, UUID invoiceId) {
        return jdbc.sql(LIST_INVOICE_LINES).param("orgId", orgId).param("invoiceId", invoiceId)
                .query(InvoiceRepository::mapLine).list();
    }

    boolean activeExistsForOrder(UUID orgId, UUID orderId) {
        return Boolean.TRUE.equals(jdbc.sql(ACTIVE_INVOICE_EXISTS).param("orgId", orgId).param("orderId", orderId)
                .query(Boolean.class).single());
    }

    Optional<Invoice> setStatus(UUID orgId, UUID id, String status, long expectedVersion) {
        return jdbc.sql(SET_INVOICE_STATUS).param("orgId", orgId).param("id", id).param("status", status)
                .param("expectedVersion", expectedVersion)
                .query(InvoiceRepository::mapHeader).optional().map(i -> i.withLines(lines(orgId, id)));
    }

    List<Invoice> list(UUID orgId, String status, UUID orderId, PageQuery page) {
        return jdbc.sql(LIST_INVOICES.formatted(page.orderBy()))
                .param("orgId", orgId).param("status", status).param("orderId", orderId)
                .param("limit", page.size()).param("offset", page.offset())
                .query(InvoiceRepository::mapHeader).list();
    }

    long count(UUID orgId, String status, UUID orderId) {
        return jdbc.sql(COUNT_INVOICES).param("orgId", orgId).param("status", status).param("orderId", orderId)
                .query(Long.class).single();
    }

    Credited credited(UUID orgId, UUID invoiceId) {
        return jdbc.sql(SUM_CREDITED).param("orgId", orgId).param("invoiceId", invoiceId)
                .query((rs, n) -> new Credited(rs.getBigDecimal("credited"), rs.getLong("notes"))).single();
    }

    CreditNote insertCreditNote(UUID orgId, String number, UUID invoiceId, BigDecimal amount, String reason) {
        return jdbc.sql(INSERT_CREDIT_NOTE).param("orgId", orgId).param("creditNoteNumber", number)
                .param("invoiceId", invoiceId).param("amount", amount).param("reason", reason)
                .query(InvoiceRepository::mapCreditNote).single();
    }

    List<CreditNote> creditNotes(UUID orgId, UUID invoiceId) {
        return jdbc.sql(LIST_CREDIT_NOTES).param("orgId", orgId).param("invoiceId", invoiceId)
                .query(InvoiceRepository::mapCreditNote).list();
    }

    private static Invoice mapHeader(ResultSet rs, int n) throws SQLException {
        return new Invoice(rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class),
                rs.getString("invoice_number"), rs.getObject("order_id", UUID.class), rs.getString("status"),
                rs.getBigDecimal("total_amount"), Db.instant(rs, "issued_at"), Db.instant(rs, "created_at"),
                Db.instant(rs, "updated_at"), rs.getLong("version"), List.of());
    }

    private static InvoiceLine mapLine(ResultSet rs, int n) throws SQLException {
        return new InvoiceLine(rs.getObject("id", UUID.class), rs.getObject("invoice_id", UUID.class),
                rs.getInt("line_no"), rs.getObject("order_line_id", UUID.class), rs.getString("description"),
                rs.getInt("quantity"), rs.getBigDecimal("unit_price"), rs.getBigDecimal("line_total"));
    }

    private static CreditNote mapCreditNote(ResultSet rs, int n) throws SQLException {
        return new CreditNote(rs.getObject("id", UUID.class), rs.getObject("invoice_id", UUID.class),
                rs.getString("credit_note_number"), rs.getBigDecimal("amount"), rs.getString("reason"),
                rs.getString("status"), Db.instant(rs, "issued_at"), Db.instant(rs, "created_at"),
                rs.getLong("version"));
    }
}
