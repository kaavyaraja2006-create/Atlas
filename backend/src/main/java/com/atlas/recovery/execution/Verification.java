package com.atlas.recovery.execution;

import com.atlas.recovery.engine.EntityKey;
import java.util.List;

/** The verifier's result. PASSED only if every check passed and no manual step is outstanding. */
public record Verification(Outcome outcome, List<Check> checks, int pendingManualSteps) {

    public enum Outcome {
        PASSED,
        /** Every check passed, but manual steps still await acknowledgement. */
        INCOMPLETE,
        FAILED
    }

    /** One check: an invariant from the plan, a step that had to complete, or the re-plan. */
    public record Check(String kind, EntityKey key, String description, boolean passed, String actual) {
    }

    public Verification {
        checks = List.copyOf(checks);
    }

    public long failures() {
        return checks.stream().filter(c -> !c.passed()).count();
    }
}
