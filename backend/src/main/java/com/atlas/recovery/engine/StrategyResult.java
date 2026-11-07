package com.atlas.recovery.engine;

import java.util.List;

/**
 * What one candidate strategy would do to the snapshot, measured (ATLAS X, Milestone 4).
 * Counts are of records unless named otherwise. "Faulty" and "unaffected" are judged by
 * RECORDED provenance; "legitimate later updates" by the audit history (a person's change
 * after the fault with no provenance into that field). In experiments with a known truth,
 * these are cross-checked against an oracle that never reads provenance.
 *
 * @param applicable            false when the strategy cannot express this incident (reason says why)
 * @param recordsInspected      records the strategy had to examine
 * @param recordsModified       records it would change (fields or whole rows)
 * @param recordsDeleted        records it would remove (restores of records created after the fault)
 * @param faultyRecords         records carrying the faulty value now (the repair target)
 * @param repaired              of those, how many it would leave correct (or fully compensated)
 * @param collateralModified    records changed that carry no faulty value
 * @param legitimateOverwritten later legitimate field updates it would overwrite or delete
 * @param conflictsSurfaced     conflicts it brings to a person
 * @param conflictsIgnored      conflicts needing a person that it neither surfaces nor resolves
 * @param invariantViolations   broken business rules in the resulting state (see {@code violations})
 * @param fieldWrites           single-field writes (whole-row writes count each changed field)
 * @param compensations         credit notes and supplementary charges it would raise (compensation of final records)
 * @param manualSteps           steps left for a person
 * @param simulationMicros      time to simulate (not reproducible; excluded from comparisons of runs)
 */
public record StrategyResult(RecoveryStrategy strategy, boolean applicable, String notApplicableReason,
                             int recordsInspected, int recordsModified, int recordsDeleted, int faultyRecords,
                             int repaired, int collateralModified, int legitimateOverwritten, int conflictsSurfaced,
                             int conflictsIgnored, int invariantViolations, int fieldWrites, int compensations,
                             int manualSteps, long simulationMicros, List<String> violations, List<String> notes) {

    public StrategyResult {
        violations = List.copyOf(violations);
        notes = List.copyOf(notes);
    }

    static StrategyResult notApplicable(RecoveryStrategy s, String why) {
        return new StrategyResult(s, false, why, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, List.of(), List.of());
    }

    /** Same result without the timing, for reproducibility checks. */
    public StrategyResult withoutTiming() {
        return new StrategyResult(strategy, applicable, notApplicableReason, recordsInspected, recordsModified,
                recordsDeleted, faultyRecords, repaired, collateralModified, legitimateOverwritten, conflictsSurfaced,
                conflictsIgnored, invariantViolations, fieldWrites, compensations, manualSteps, 0, violations, notes);
    }

    public int unrepaired() {
        return faultyRecords - repaired;
    }
}
