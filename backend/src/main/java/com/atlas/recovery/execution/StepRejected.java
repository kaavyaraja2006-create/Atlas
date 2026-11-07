package com.atlas.recovery.execution;

/**
 * A step's write was refused for a deterministic reason (a constraint or
 * business rule), so retrying will not help: the step is recorded FAILED.
 * Any other exception is treated as transient: the transaction rolls back,
 * nothing is recorded, and resuming the execution retries the step.
 */
public final class StepRejected extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public StepRejected(String message) {
        super(message);
    }
}
