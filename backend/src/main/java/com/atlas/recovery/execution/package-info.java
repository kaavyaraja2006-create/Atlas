/**
 * PASCAR execution: approval, step-by-step execution and verification of a
 * {@link com.atlas.recovery.engine.RecoveryPlan}.
 *
 * <p>Framework-free like the engine. The workflow talks to the database only
 * through the small ports in this package ({@link com.atlas.recovery.execution.RecoveryLedger},
 * {@link com.atlas.recovery.execution.RecordStore}, {@link com.atlas.recovery.execution.Transactions},
 * {@link com.atlas.recovery.execution.Fingerprints}, {@link com.atlas.recovery.execution.Replanner}),
 * so its rules (separation of duties, stale-plan detection, per-step
 * idempotency, blocking, crash resume, verification) are unit-tested against
 * an in-memory stand-in. The JDBC adapters live in {@code com.atlas.recovery}.
 * See docs/algorithms/pascar.md, section 4.
 */
package com.atlas.recovery.execution;
