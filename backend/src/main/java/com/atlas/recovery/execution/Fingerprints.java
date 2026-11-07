package com.atlas.recovery.execution;

/** Recomputes a plan's fingerprint from the current data (reloads the incident graph). */
public interface Fingerprints {

    String current(PlanRecord plan);
}
