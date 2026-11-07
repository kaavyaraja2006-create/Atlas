package com.atlas.recovery.execution;

import java.util.Objects;
import java.util.UUID;

/** An approver's decision, bound to the plan and to the fingerprint they saw. */
public record Decision(UUID planId, UUID userId, boolean approved, String fingerprint, String comment) {

    public Decision {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(fingerprint, "fingerprint");
    }
}
