package com.atlas.recovery.engine;

import java.util.Objects;
import java.util.Set;

/**
 * One (record, field) that carried the faulty value, classified with its evidence.
 *
 * @param taintedFrom    first version of the record whose field carried the faulty value
 * @param taintedTo      last such version
 * @param laterVersion   the later version that decided the classification, if any
 * @param laterActorKind who wrote {@code laterVersion} (USER, SYSTEM, RECOVERY, ...)
 * @param laterFields    fields that {@code laterVersion} (or, for DIFFERENT_FIELD_UPDATE, all later versions) changed
 */
public record FieldConflict(EntityKey key, String field, ConflictKind kind, long taintedFrom, long taintedTo,
                            Long laterVersion, String laterActorKind, Set<String> laterFields,
                            Recommendation recommendation, Severity severity, String explanation) {

    public enum Recommendation {
        /** Recovery may correct this field automatically (with its version check). */
        CORRECT_AUTOMATICALLY,
        /** Keep the later value; recovery must not write this field. */
        PRESERVE_LATER_CHANGE,
        /** A person must decide before anything is written. */
        HUMAN_REVIEW,
        /** Nothing to do (already repaired). */
        NONE
    }

    public enum Severity { LOW, MEDIUM, HIGH }

    public FieldConflict {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(kind, "kind");
        laterFields = laterFields == null ? Set.of() : Set.copyOf(laterFields);
    }

    public boolean needsReview() {
        return recommendation == Recommendation.HUMAN_REVIEW;
    }
}
