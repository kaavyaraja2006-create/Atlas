package com.atlas.recovery.engine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Which fields must have recorded provenance when they are written (the same rules as the
 * {@code /provenance/gaps} query, {@code ProvenanceQueries.PROVENANCE_GAPS}; a test fails if the
 * two lists drift apart). A value written without its required input is a GAP: nothing recorded
 * says where it came from, so it could have come from a faulty version.
 *
 * <p>Used to decide whether a record the impact traversal did NOT reach can be said to be
 * outside the fault's reach: only when none of its versions within the analysed scope has a gap.
 */
public final class ProvenanceRequirements {

    /** @param zeroAllowed a written value of 0 needs no input (an empty order or invoice total) */
    public record Requirement(String entityType, String field, boolean zeroAllowed, String description) {
    }

    /** A required input that was never recorded: {@code field} of {@code key} at {@code version}. */
    public record Gap(EntityKey key, long version, String field, String requirement) {
        @Override
        public String toString() {
            return field + "@v" + version;
        }
    }

    public static final List<Requirement> ALL = List.of(
            new Requirement("order_line", "unit_price", false, "priced from a product version (order.add_line)"),
            new Requirement("order", "total_amount", true, "summed from its lines (order.add_line)"),
            new Requirement("invoice_line", "unit_price", false, "copied from an order line (invoice.create_from_order)"),
            new Requirement("invoice", "total_amount", true, "summed from its lines (invoice.create_from_order)"));

    private static final Set<String> WRITES = Set.of("INSERT", "UPDATE");

    private ProvenanceRequirements() {
    }

    /**
     * Gaps of one record: versions that wrote a required field without an incoming edge to it.
     * When the graph was given the gaps by its loader ({@link IncidentGraph#withGaps}), those are
     * used; otherwise they are computed from the graph's edges, which is only complete when the
     * graph holds every incoming edge of the record (as the in-memory histories do). A loader that
     * forgot its gaps errs towards "incomplete", never towards "complete".
     */
    public static List<Gap> gaps(EntityKey key, IncidentGraph graph) {
        if (graph.suppliedGaps().isPresent()) {
            return graph.suppliedGaps().get().stream().filter(g -> g.key().equals(key)).toList();
        }
        List<Gap> out = new ArrayList<>();
        for (Requirement r : ALL) {
            if (!r.entityType().equals(key.entityType())) {
                continue;
            }
            for (VersionInfo v : graph.history(key)) {
                if (!WRITES.contains(v.operation()) || !v.changedFields().contains(r.field())) {
                    continue;
                }
                Object value = v.after().get(r.field());
                if (r.zeroAllowed() && value != null && Values.decimal(value).compareTo(BigDecimal.ZERO) == 0) {
                    continue;
                }
                boolean recorded = graph.incoming(key.at(v.version())).stream()
                        .anyMatch(e -> r.field().equals(e.targetField()));
                if (!recorded) {
                    out.add(new Gap(key, v.version(), r.field(), r.description()));
                }
            }
        }
        return out;
    }
}
