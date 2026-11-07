package com.atlas.catalogue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** A catalogue product. The SKU is its business identifier and never changes. */
public record Product(UUID id, UUID organizationId, String sku, String name, BigDecimal unitPrice,
                      BigDecimal unitCost, boolean active, Instant createdAt, Instant updatedAt, long version) {

    /** What the audit log stores for each version; keys are column names. */
    public Map<String, Object> snapshot() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("sku", sku);
        s.put("name", name);
        s.put("unit_price", unitPrice);
        s.put("unit_cost", unitCost);
        s.put("active", active);
        return s;
    }

    /** Applies a partial update; null means "leave unchanged". */
    Product withChanges(String newName, BigDecimal newPrice, BigDecimal newCost, Boolean newActive) {
        return new Product(id, organizationId, sku,
                newName != null ? newName.trim() : name,
                newPrice != null ? newPrice : unitPrice,
                newCost != null ? newCost : unitCost,
                newActive != null ? newActive : active,
                createdAt, updatedAt, version);
    }
}
