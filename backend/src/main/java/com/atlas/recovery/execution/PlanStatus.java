package com.atlas.recovery.execution;

/** Lifecycle of a stored plan. The database trigger enforces the same transitions. */
public enum PlanStatus {
    PROPOSED, APPROVED, REJECTED, SUPERSEDED, EXECUTING, EXECUTED, FAILED;

    public boolean finishedExecuting() {
        return this == EXECUTED || this == FAILED;
    }
}
