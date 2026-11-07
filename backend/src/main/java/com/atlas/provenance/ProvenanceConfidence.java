package com.atlas.provenance;

/**
 * How much a lineage edge is worth as evidence (ATLAS X). Computed, never asserted:
 * see {@link ProvenanceQueries} for the SQL that decides it.
 */
public enum ProvenanceConfidence {
    /**
     * Recorded at write time by the operation that computed the value, AND the recorded
     * values agree with it: the target equals the source (DERIVED_VALUE, COPIED_INTO) or
     * the sum of all its recorded inputs (AGGREGATED_INTO), at exactly those versions.
     * This shows the declared derivation is consistent with the data; it does not prove
     * which bytes the code read.
     */
    VERIFIED,
    /** Recorded at write time by the operation, but the values do not (or cannot) confirm it. */
    DECLARED,
    /** Added afterwards by a backfill heuristic. Never used for automatic recovery. */
    INFERRED,
    /**
     * No edge where one is required: a value that a provenance requirement says must be
     * derived from inputs was written without any. Reported as a gap; never invented.
     */
    UNKNOWN
}
