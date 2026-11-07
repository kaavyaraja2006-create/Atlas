package com.atlas.recovery.engine;

import java.util.List;

/**
 * Record-level outcome of one simulated strategy (Graph Intelligence, step 2).
 *
 * <p>The counts are EXACT and computed over every record. {@code stored} holds at most the
 * simulator's detail limit (5,000 by default), chosen by priority: records the strategy acts
 * on first, then conflicting or unresolved ones, then records whose later changes it keeps,
 * then records it only inspected; ties in key order. When {@code truncated} is true the list
 * is a PARTIAL view and must be presented as such.
 *
 * @param total                  records with at least one of the categories below
 * @param inspected              records the strategy examined
 * @param proposed               records it proposes to change (including steps that wait for a person)
 * @param modified               records whose simulated end state differs from the snapshot
 * @param preserved              records holding a later legitimate change that the strategy keeps intact
 * @param conflicting            records with a conflict that needs a person's decision
 * @param unresolved             records still carrying the faulty value (or awaiting review) afterwards
 * @param preservedLaterChanges  later legitimate FIELD values kept (the snapshot's total minus those lost)
 * @param unresolvedDependencies automatic steps that wait for a person's decision on a step they depend on
 */
public record StrategyRecords(RecoveryStrategy strategy, int total, int inspected, int proposed, int modified, int preserved,
                              int conflicting, int unresolved, int preservedLaterChanges, int unresolvedDependencies,
                              List<RecordDetail> stored, boolean truncated) {

    public StrategyRecords {
        stored = List.copyOf(stored);
        if (truncated != (stored.size() < total)) {
            throw new IllegalArgumentException("truncated must say whether details were omitted");
        }
    }

    public int omitted() {
        return total - stored.size();
    }

    static StrategyRecords none(RecoveryStrategy s) {
        return new StrategyRecords(s, 0, 0, 0, 0, 0, 0, 0, 0, 0, List.of(), false);
    }

    /**
     * One record's part in a strategy. Holds identifiers and field NAMES only, never values.
     *
     * @param action         DELETE, MODIFY, COMPENSATE (credit note or supplementary charge), REVIEW (a
     *                       person decides), HELD (an automatic step waiting for a review) or NONE
     * @param fields         fields it changes or proposes to change
     * @param provenancePath the recorded path from the faulty change, as "type:id@v" (empty if not reached)
     * @param conflictKind   the kind of an open conflict on this record, else null
     */
    public record RecordDetail(EntityKey key, String action, boolean inspected, boolean proposed, boolean modified,
                               boolean preserved, boolean conflicting, boolean unresolved, List<String> fields,
                               List<String> provenancePath, String conflictKind) {
        public RecordDetail {
            fields = List.copyOf(fields);
            provenancePath = List.copyOf(provenancePath);
        }

        /** Lower sorts first when the detail list has to be cut. */
        int priority() {
            if (!action.equals("NONE")) {
                return 0;
            }
            if (conflicting || unresolved) {
                return 1;
            }
            return preserved ? 2 : 3;
        }
    }
}
