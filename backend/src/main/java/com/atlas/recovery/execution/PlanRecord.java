package com.atlas.recovery.execution;

import com.atlas.recovery.engine.EntityKey;
import com.atlas.recovery.engine.RecoveryPlan;
import java.util.Objects;
import java.util.UUID;

/**
 * A stored plan as the workflow needs it.
 *
 * @param origin the record the faulty change wrote (the incident's subject)
 */
public record PlanRecord(UUID id, UUID incidentId, UUID createdBy, PlanStatus status, EntityKey origin,
                         RecoveryPlan plan) {

    public PlanRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(plan, "plan");
    }
}
