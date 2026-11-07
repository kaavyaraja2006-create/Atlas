package com.atlas.billing;

import com.atlas.provenance.VersionRef;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Invoice lines never change after creation, so every line exists only at version 0. */
public record InvoiceLine(UUID id, UUID invoiceId, int lineNo, UUID orderLineId, String description, int quantity,
                          BigDecimal unitPrice, BigDecimal lineTotal) {

    public static final String ENTITY_TYPE = "invoice_line";

    public Map<String, Object> snapshot() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("invoice_id", invoiceId.toString());
        s.put("line_no", lineNo);
        s.put("order_line_id", orderLineId.toString());
        s.put("description", description);
        s.put("quantity", quantity);
        s.put("unit_price", unitPrice);
        s.put("line_total", lineTotal);
        return s;
    }

    public VersionRef ref() {
        return new VersionRef(ENTITY_TYPE, id, 0);
    }
}
