package com.atlas.recovery.engine;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One recorded version of a record, from change_events: who wrote it, which
 * fields changed, and the full state after the change (numbers as BigDecimal).
 */
public record VersionInfo(long version, String operation, String actorKind, Set<String> changedFields,
                          Map<String, Object> after) {

    public static final String RECOVERY_ACTOR = "RECOVERY";

    public VersionInfo {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(actorKind, "actorKind");
        changedFields = Set.copyOf(changedFields);
        after = after == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(after));
    }

    public boolean byRecovery() {
        return RECOVERY_ACTOR.equals(actorKind);
    }
}
