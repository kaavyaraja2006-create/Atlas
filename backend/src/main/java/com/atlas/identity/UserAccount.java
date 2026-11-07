package com.atlas.identity;

import java.time.Instant;
import java.util.UUID;

record UserAccount(UUID id, String email, String displayName, String passwordHash, boolean platformAdmin,
                   String status, int failedLoginCount, Instant lockedUntil) {

    boolean lockedAt(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    boolean active() {
        return "ACTIVE".equals(status);
    }
}
