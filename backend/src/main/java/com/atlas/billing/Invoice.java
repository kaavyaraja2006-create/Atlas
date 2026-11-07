package com.atlas.billing;

import com.atlas.common.error.ApiException;
import com.atlas.provenance.VersionRef;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * DRAFT → ISSUED → VOID, or DRAFT → VOID. Once ISSUED the amounts are
 * final (enforced by a database trigger); an over-billing is corrected with
 * a credit note, which is what the recovery engine will do for issued
 * invoices built on a faulty price.
 */
public record Invoice(UUID id, UUID organizationId, String invoiceNumber, UUID orderId, String status,
                      BigDecimal totalAmount, Instant issuedAt, Instant createdAt, Instant updatedAt, long version,
                      List<InvoiceLine> lines) {

    public static final String ENTITY_TYPE = "invoice";
    public static final String DRAFT = "DRAFT";
    public static final String ISSUED = "ISSUED";
    public static final String VOID = "VOID";

    public Invoice {
        lines = List.copyOf(lines);
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("invoice_number", invoiceNumber);
        s.put("order_id", orderId.toString());
        s.put("status", status);
        s.put("total_amount", totalAmount);
        s.put("issued_at", issuedAt == null ? null : issuedAt.toString());
        s.put("line_count", lines.size());
        return s;
    }

    public VersionRef ref() {
        return new VersionRef(ENTITY_TYPE, id, version);
    }

    Invoice withLines(List<InvoiceLine> newLines) {
        return new Invoice(id, organizationId, invoiceNumber, orderId, status, totalAmount, issuedAt, createdAt,
                updatedAt, version, newLines);
    }

    void requireIssuable() {
        if (!DRAFT.equals(status)) {
            throw ApiException.unprocessable("Only DRAFT invoices can be issued; " + invoiceNumber + " is " + status + ".");
        }
        if (lines.isEmpty()) {
            throw ApiException.unprocessable("Invoice " + invoiceNumber + " has no lines.");
        }
    }

    void requireVoidable(long creditNoteCount) {
        if (VOID.equals(status)) {
            throw ApiException.unprocessable("Invoice " + invoiceNumber + " is already VOID.");
        }
        if (creditNoteCount > 0) {
            throw ApiException.unprocessable("Invoice " + invoiceNumber + " has credit notes and cannot be voided.");
        }
    }

    /** A credit note may reduce an ISSUED invoice, never below zero in total. */
    void requireCreditable(BigDecimal alreadyCredited, BigDecimal amount) {
        if (!ISSUED.equals(status)) {
            throw ApiException.unprocessable("Only ISSUED invoices can be credited; " + invoiceNumber + " is " + status + ".");
        }
        if (amount.signum() <= 0) {
            throw ApiException.unprocessable("A credit note amount must be greater than zero.");
        }
        BigDecimal remaining = totalAmount.subtract(alreadyCredited);
        if (amount.compareTo(remaining) > 0) {
            throw ApiException.unprocessable("Credit of " + amount.toPlainString() + " exceeds the "
                    + remaining.toPlainString() + " still creditable on " + invoiceNumber + ".");
        }
    }
}
