package com.atlas.recovery.execution;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Persistence of plans, decisions, step outcomes and verifications. Called inside a transaction. */
public interface RecoveryLedger {

    /** The plan, with its row locked until the transaction ends (serialises executors). */
    Optional<PlanRecord> lockPlan(UUID planId);

    /** Compare-and-set; fails if the plan is no longer in {@code from}. */
    void setPlanStatus(UUID planId, PlanStatus from, PlanStatus to);

    void recordDecision(Decision decision);

    /** The approving decision, if any. */
    Optional<Decision> approval(UUID planId);

    /** Recorded steps by number. */
    Map<Integer, StepRecord> steps(UUID planId);

    /** Records a step's first outcome; fails if the step already has one (unique per plan and step). */
    void recordStep(UUID planId, StepRecord step);

    /** AWAITING_ACKNOWLEDGEMENT → ACKNOWLEDGED; fails otherwise. */
    void acknowledgeStep(UUID planId, StepRecord step);

    void recordVerification(UUID planId, Verification verification);
}
