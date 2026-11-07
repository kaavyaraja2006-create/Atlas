package com.atlas.recovery.engine;

import com.atlas.provenance.VersionRef;
import java.util.Objects;

/** One recorded provenance edge: source@v.field → target@v.field. */
public record Edge(VersionRef source, String sourceField, VersionRef target, String targetField,
                   Relation relation, boolean recordedAtWrite) {

    public enum Relation { DERIVED_VALUE, AGGREGATED_INTO, COPIED_INTO }

    public Edge {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(relation, "relation");
    }

    /** An edge with no source field carries every field of the source version. */
    public boolean carries(String field) {
        return sourceField == null || sourceField.equals(field);
    }

    public String targetFieldFor(String field) {
        return targetField != null ? targetField : field;
    }
}
