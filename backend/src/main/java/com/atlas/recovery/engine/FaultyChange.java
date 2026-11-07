package com.atlas.recovery.engine;

import com.atlas.provenance.VersionRef;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The change an incident declares wrong: the version it produced, the fields
 * it changed, and their values before (the intended values) and after.
 */
public record FaultyChange(VersionRef ref, Set<String> fields, Map<String, Object> before, Map<String, Object> after) {

    public FaultyChange {
        Objects.requireNonNull(ref, "ref");
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("A faulty change must name at least one changed field");
        }
        fields = java.util.Collections.unmodifiableSet(new TreeSet<>(fields));
        before = Map.copyOf(before == null ? Map.of() : before);
        after = Map.copyOf(after == null ? Map.of() : after);
        for (String f : fields) {
            if (!before.containsKey(f)) {
                throw new IllegalArgumentException("No before-value for faulty field " + f
                        + "; an INSERT cannot be the faulty change in this version of PASCAR");
            }
        }
    }

    public EntityKey key() {
        return EntityKey.of(ref);
    }
}
