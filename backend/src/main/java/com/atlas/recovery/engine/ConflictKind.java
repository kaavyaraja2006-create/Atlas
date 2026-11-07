package com.atlas.recovery.engine;

/**
 * What the evidence says about a field that carried a faulty value (ATLAS X, Milestone 3).
 * Decided from versions, actors and provenance only; never from a guess about intent.
 */
public enum ConflictKind {
    /** The faulty value is still there and nothing else touched the record since: safe to correct. */
    NO_CONFLICT,
    /** A person changed THIS field after it carried the faulty value: correcting it would overwrite them. */
    SAME_FIELD_CONFLICT,
    /** Someone changed OTHER fields of the record later: a field-level correction keeps their change. */
    DIFFERENT_FIELD_UPDATE,
    /** The field was later re-derived from a different source version (recorded provenance says so). */
    SOURCE_VERSION_MISMATCH,
    /** The record changed after the plan was made: the planned write no longer matches what is there. */
    CONCURRENT_MODIFICATION,
    /** The field was changed later by a non-person actor (system, import) with no recorded provenance. */
    UNKNOWN_CHANGE_ORIGIN,
    /** Provenance is incomplete or circular here: the evidence cannot decide. */
    REQUIRES_MANUAL_REVIEW
}
