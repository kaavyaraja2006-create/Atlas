package com.atlas.recovery.execution;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Where an execution ended and what happened to each step. */
public record ExecutionReport(PlanStatus status, List<StepRecord> steps) {

    public ExecutionReport {
        steps = List.copyOf(steps);
    }

    public Map<StepStatus, Long> counts() {
        Map<StepStatus, Long> out = new TreeMap<>();
        steps.forEach(s -> out.merge(s.status(), 1L, Long::sum));
        return out;
    }
}
