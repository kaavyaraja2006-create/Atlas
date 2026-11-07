package com.atlas.sales;

import com.atlas.common.error.ApiException;
import com.atlas.provenance.VersionRef;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A sales order. Lifecycle: DRAFT (lines can be added) → CONFIRMED (can be
 * invoiced) or DRAFT → CANCELLED. A confirmed order's money is corrected through
 * its invoice (void or credit note), not edited; its delivery note stays editable
 * until it is cancelled (ATLAS X: a legitimate later edit to a different field).
 */
public record Order(UUID id, UUID organizationId, String orderNumber, UUID customerId, String status,
                    BigDecimal totalAmount, UUID createdBy, Instant placedAt, Instant createdAt, Instant updatedAt,
                    long version, String deliveryNote, List<OrderLine> lines) {

    public static final String ENTITY_TYPE = "order";
    public static final String DRAFT = "DRAFT";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String CANCELLED = "CANCELLED";

    public Order {
        lines = List.copyOf(lines);
    }

    /** line_count is included so that adding a zero-priced line is still a visible change. */
    public Map<String, Object> snapshot() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("order_number", orderNumber);
        s.put("customer_id", customerId.toString());
        s.put("status", status);
        s.put("total_amount", totalAmount);
        s.put("placed_at", placedAt == null ? null : placedAt.toString());
        s.put("line_count", lines.size());
        s.put("delivery_note", deliveryNote);
        return s;
    }

    public VersionRef ref() {
        return new VersionRef(ENTITY_TYPE, id, version);
    }

    Order withLines(List<OrderLine> newLines) {
        return new Order(id, organizationId, orderNumber, customerId, status, totalAmount, createdBy, placedAt,
                createdAt, updatedAt, version, deliveryNote, newLines);
    }

    /** The invariant the database total must satisfy: Σ quantity × unit_price. */
    public static BigDecimal totalOf(List<OrderLine> lines) {
        return lines.stream()
                .map(l -> l.unitPrice().multiply(BigDecimal.valueOf(l.quantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    void requireDraft(String action) {
        if (!DRAFT.equals(status)) {
            throw ApiException.unprocessable("Only DRAFT orders can " + action + "; order " + orderNumber
                    + " is " + status + ".");
        }
    }

    void requireConfirmable() {
        requireDraft("be confirmed");
        if (lines.isEmpty()) {
            throw ApiException.unprocessable("Order " + orderNumber + " has no lines; add at least one before confirming.");
        }
    }

    void requireInvoiceable() {
        if (!CONFIRMED.equals(status)) {
            throw ApiException.unprocessable("Only CONFIRMED orders can be invoiced; order " + orderNumber
                    + " is " + status + ".");
        }
    }
}
