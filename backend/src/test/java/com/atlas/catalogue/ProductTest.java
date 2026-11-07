package com.atlas.catalogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atlas.audit.ChangeDiff;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductTest {

    private final Product widget = new Product(UUID.randomUUID(), UUID.randomUUID(), "P100", "Widget",
            new BigDecimal("5000.0000"), new BigDecimal("3000.0000"), true, Instant.EPOCH, Instant.EPOCH, 4);

    @Test
    void partialUpdateKeepsUnspecifiedFieldsAndSku() {
        Product changed = widget.withChanges(null, new BigDecimal("500"), null, null);
        assertEquals("P100", changed.sku());
        assertEquals("Widget", changed.name());
        assertEquals(new BigDecimal("3000.0000"), changed.unitCost());
        assertEquals(4, changed.version());
        assertEquals(List.of("unit_price"), ChangeDiff.changedFields(widget.snapshot(), changed.snapshot()));
    }

    @Test
    void sameValueWithDifferentScaleIsNoOp() {
        Product same = widget.withChanges("  Widget ", new BigDecimal("5000"), null, true);
        assertTrue(ChangeDiff.changedFields(widget.snapshot(), same.snapshot()).isEmpty());
    }

    @Test
    void snapshotUsesColumnNamesAndExcludesBookkeeping() {
        assertEquals(List.of("sku", "name", "unit_price", "unit_cost", "active"),
                List.copyOf(widget.snapshot().keySet()));
    }
}
