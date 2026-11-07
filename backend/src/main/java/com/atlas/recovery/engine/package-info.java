/**
 * PASCAR — Provenance-Aware Selective Change And Recovery: impact analysis and
 * recovery planning.
 *
 * <p>Deliberately framework-free (no Spring, no JDBC): it takes an
 * {@link com.atlas.recovery.engine.IncidentGraph} already loaded from the
 * database and returns plain values. That keeps the algorithm deterministic
 * and unit-testable, and lets the experiment harness drive it with synthetic
 * data at scale. See docs/algorithms/pascar.md.
 */
package com.atlas.recovery.engine;
