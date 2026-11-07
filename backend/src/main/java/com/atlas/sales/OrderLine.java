package com.atlas.sales;

import com.atlas.provenance.VersionRef;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** A priced order line. unit_price is a snapshot of the product version recorded in provenance. */
public record OrderLine(UUID id, UUID orderId, int lineNo, UUID productId, String description, int quantity,
                        BigDecimal unitPrice, BigDecimal lineTotal, long version) {

    public static final String ENTITY_TYPE = "order_line";

    public Map<String, Object> snapshot() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("order_id", orderId.toString());
        s.put("line_no", lineNo);
        s.put("product_id", productId.toString());
        s.put("description", description);
        s.put("quantity", quantity);
        s.put("unit_price", unitPrice);
        s.put("line_total", lineTotal);
        return s;
    }

    public VersionRef ref() {
        return new VersionRef(ENTITY_TYPE, id, version);
    }
}
