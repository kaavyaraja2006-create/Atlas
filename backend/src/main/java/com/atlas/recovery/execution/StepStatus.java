package com.atlas.recovery.execution;

/** Outcome of one plan step. A step is recorded once; only AWAITING_ACKNOWLEDGEMENT may change later. */
public enum StepStatus {
    /** Applied: the record was rewritten or the credit note issued. */
    DONE,
    /** Not applied: the record changed since planning, or the write was rejected. */
    FAILED,
    /** Not attempted: a step it depends on did not complete. */
    BLOCKED,
    /** Nothing to do (e.g. a void invoice). */
    SKIPPED,
    /** A person must act (conflict, manual review, supplementary charge). */
    AWAITING_ACKNOWLEDGEMENT,
    /** A person recorded what they did about a manual step. */
    ACKNOWLEDGED;

    /** Whether a dependent step may run after this one. */
    public boolean satisfies() {
        return this == DONE || this == SKIPPED;
    }
}
