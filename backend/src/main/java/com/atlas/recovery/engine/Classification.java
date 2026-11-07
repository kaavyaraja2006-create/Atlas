package com.atlas.recovery.engine;

/**
 * How a record relates to an incident.
 *
 * <p>Stored plans written before the Graph Intelligence change used {@code UNAFFECTED} for both
 * {@link #NO_LONGER_AFFECTED} and the two NOT_REACHED classes; those stored values are only read
 * back as JSON text (the UI labels them as legacy) and are never parsed into this enum.
 */
public enum Classification {
    /** The record whose change was declared faulty. */
    ORIGIN,
    /** Consumed the faulty value through one recorded edge. */
    DIRECTLY_AFFECTED,
    /** Consumed it through a chain of recorded edges. */
    INDIRECTLY_AFFECTED,
    /**
     * Reached by the traversal, but no current field carries the faulty value any more (it was
     * overwritten by an earlier recovery or carried no further). Never changed by the plan.
     */
    NO_LONGER_AFFECTED,
    /**
     * Not reached by the traversal (listed because it is summed into an affected total), and every
     * required input of its versions WITHIN THE ANALYSED SCOPE was recorded. This rules out impact
     * through recorded provenance in that scope; it is not a universal proof of no impact.
     */
    NOT_REACHED_PROVENANCE_COMPLETE,
    /**
     * Not reached by the traversal, but at least one required input of the record was never
     * recorded, so missing provenance prevents ruling out impact.
     */
    NOT_REACHED_PROVENANCE_INCOMPLETE,
    /** A tainted field was later changed by someone: a person must decide. */
    CONFLICTED,
    /** Reached only through unconfirmed (backfilled) edges, a cycle, or a type with no repair rule. */
    REQUIRES_MANUAL_REVIEW,
    /** Legally final (e.g. an issued invoice): compensated, never edited. */
    NON_REVERSIBLE;

    /** Listed for context; the plan never changes these records. */
    public boolean context() {
        return this == NO_LONGER_AFFECTED || this == NOT_REACHED_PROVENANCE_COMPLETE || this == NOT_REACHED_PROVENANCE_INCOMPLETE;
    }
}
