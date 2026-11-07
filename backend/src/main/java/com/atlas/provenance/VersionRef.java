package com.atlas.provenance;

import java.util.Objects;
import java.util.UUID;

/** One specific version of one record, e.g. product P100 @ v7. */
public record VersionRef(String entityType, UUID entityId, long version) {

    public VersionRef {
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(entityId, "entityId");
        if (version < 0) {
            throw new IllegalArgumentException("version must be >= 0");
        }
    }

    @Override
    public String toString() {
        return entityType + ":" + entityId + "@" + version;
    }
}
