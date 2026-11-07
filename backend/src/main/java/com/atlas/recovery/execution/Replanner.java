package com.atlas.recovery.execution;

import com.atlas.recovery.engine.RecoveryPlan;

/**
 * Runs impact analysis and planning again on the current data, counting
 * credits already issued for the incident. After a complete recovery the
 * result has no automatic steps; the verifier checks exactly that.
 */
public interface Replanner {

    RecoveryPlan replan(PlanRecord plan);
}
