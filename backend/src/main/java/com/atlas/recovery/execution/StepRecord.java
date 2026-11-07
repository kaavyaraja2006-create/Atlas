package com.atlas.recovery.execution;

import java.util.Objects;
import java.util.UUID;

/**
 * What happened to one step.
 *
 * @param resultVersion the record version the step wrote (DONE field updates)
 * @param resultId      the credit note it issued (DONE credit notes)
 * @param actorId       the executor, or the person who acknowledged a manual step
 */
public record StepRecord(int stepNo, StepStatus status, String detail, Long resultVersion, UUID resultId,
                         UUID actorId) {

    public StepRecord {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(detail, "detail");
    }
}
