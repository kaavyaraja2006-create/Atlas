package com.atlas.recovery.engine;

import com.atlas.provenance.VersionRef;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

/** A record independent of version: (entity type, id). */
public record EntityKey(String entityType, UUID entityId) implements Comparable<EntityKey> {

    private static final Comparator<EntityKey> ORDER =
            Comparator.comparing(EntityKey::entityType).thenComparing(EntityKey::entityId);

    public EntityKey {
        Objects.requireNonNull(entityType, "entityType");
        Objects.requireNonNull(entityId, "entityId");
    }

    public static EntityKey of(VersionRef ref) {
        return new EntityKey(ref.entityType(), ref.entityId());
    }

    public VersionRef at(long version) {
        return new VersionRef(entityType, entityId, version);
    }

    @Override
    public int compareTo(EntityKey other) {
        return ORDER.compare(this, other);
    }

    @Override
    public String toString() {
        return entityType + ":" + entityId;
    }
}
