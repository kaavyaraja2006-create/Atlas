package com.atlas.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChangeDiffTest {

    @Test
    void reportsOnlyChangedFieldsSorted() {
        Map<String, Object> before = Map.of("name", "Widget", "unit_price", new BigDecimal("5000.0000"), "active", true);
        Map<String, Object> after = Map.of("name", "Widget", "unit_price", new BigDecimal("500.0000"), "active", false);
        assertEquals(List.of("active", "unit_price"), ChangeDiff.changedFields(before, after));
    }

    @Test
    void numbersCompareByValueNotScale() {
        Map<String, Object> before = Map.of("unit_price", new BigDecimal("500.0000"));
        Map<String, Object> after = Map.of("unit_price", new BigDecimal("500"));
        assertTrue(ChangeDiff.changedFields(before, after).isEmpty());
        assertTrue(ChangeDiff.changedFields(Map.of("q", 3), Map.of("q", 3L)).isEmpty());
    }

    @Test
    void addedRemovedAndNullValuesCount() {
        Map<String, Object> before = new HashMap<>();
        before.put("email", null);
        before.put("old", "x");
        Map<String, Object> after = new HashMap<>();
        after.put("email", "a@b.c");
        after.put("new", "y");
        assertEquals(List.of("email", "new", "old"), ChangeDiff.changedFields(before, after));
        Map<String, Object> bothNull = new HashMap<>();
        bothNull.put("email", null);
        assertTrue(ChangeDiff.changedFields(bothNull, bothNull).isEmpty());
    }
}
