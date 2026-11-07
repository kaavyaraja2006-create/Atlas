package com.atlas.recovery.engine;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Domain knowledge the engine needs, in one place:
 * <ul>
 *   <li>fields derived inside one record (line_total = quantity × unit_price),</li>
 *   <li>which records are legally final and must be compensated, not edited,</li>
 *   <li>which record types the executor knows how to update.</li>
 * </ul>
 * Values crossing records are not here: they follow the recorded edges
 * (copy for DERIVED_VALUE / COPIED_INTO, sum for AGGREGATED_INTO).
 */
public final class RepairRules {

    private static final Set<String> LINE_TYPES = Set.of("order_line", "invoice_line");
    private static final Set<String> UPDATABLE = Set.of("product", "order_line", "order", "invoice_line", "invoice");

    private RepairRules() {
    }

    /** Fields of the same record that are computed from {@code field}. */
    public static List<String> derivedFields(String entityType, String field) {
        if (LINE_TYPES.contains(entityType) && (field.equals("unit_price") || field.equals("quantity"))) {
            return List.of("line_total");
        }
        return List.of();
    }

    /** Recomputes intra-record derived fields after cross-record fields were repaired. */
    public static Map<String, Object> applyFormulas(String entityType, Map<String, Object> state) {
        Map<String, Object> out = new LinkedHashMap<>(state);
        if (LINE_TYPES.contains(entityType) && out.containsKey("unit_price") && out.containsKey("quantity")) {
            out.put("line_total", Values.decimal(out.get("unit_price")).multiply(Values.decimal(out.get("quantity"))));
        }
        return out;
    }

    public enum Finality { EDITABLE, COMPENSATE, NO_ACTION }

    /**
     * Whether a record can still be edited. An ISSUED invoice (and its lines)
     * must be compensated; a VOID one is no longer payable and needs nothing.
     */
    public static Finality finality(String entityType, Map<String, Object> current,
                                    Function<EntityKey, Map<String, Object>> lookup) {
        return switch (entityType) {
            case "invoice" -> invoiceFinality(current.get("status"));
            case "invoice_line" -> {
                Object invoiceId = current.get("invoice_id");
                yield invoiceId == null ? Finality.COMPENSATE
                        : invoiceFinality(lookup.apply(new EntityKey("invoice", java.util.UUID.fromString(invoiceId.toString())))
                        .get("status"));
            }
            case "credit_note" -> Finality.COMPENSATE;
            default -> Finality.EDITABLE;
        };
    }

    private static Finality invoiceFinality(Object status) {
        if ("VOID".equals(status)) {
            return Finality.NO_ACTION;
        }
        return "DRAFT".equals(status) ? Finality.EDITABLE : Finality.COMPENSATE;
    }

    public static boolean updatable(String entityType) {
        return UPDATABLE.contains(entityType);
    }

    public static BigDecimal zero() {
        return BigDecimal.ZERO;
    }
}
