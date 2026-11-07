package com.atlas.common.tenancy;

import java.util.Objects;
import java.util.UUID;

/** Who caused a change: a signed-in user, the system, or the recovery engine. */
public record Actor(String kind, UUID userId) {

    public static final String USER = "USER";
    public static final String SYSTEM = "SYSTEM";
    public static final String RECOVERY = "RECOVERY";

    public Actor {
        Objects.requireNonNull(kind, "kind");
        if (!kind.equals(USER) && !kind.equals(SYSTEM) && !kind.equals(RECOVERY)) {
            throw new IllegalArgumentException("Unknown actor kind: " + kind);
        }
        if (kind.equals(USER) && userId == null) {
            throw new IllegalArgumentException("A USER actor needs a user id");
        }
    }

    public static Actor user(UUID userId) {
        return new Actor(USER, userId);
    }

    public static Actor system() {
        return new Actor(SYSTEM, null);
    }

    /** A recovery step's write, carried out on behalf of the user who ran the execution. */
    public static Actor recovery(UUID executedBy) {
        return new Actor(RECOVERY, Objects.requireNonNull(executedBy, "executedBy"));
    }
}
