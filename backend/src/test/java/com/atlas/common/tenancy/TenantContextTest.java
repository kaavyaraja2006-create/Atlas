package com.atlas.common.tenancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atlas.common.error.ApiException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenantContextTest {

    private final UUID org = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();

    @Test
    void requireThrows403ForMissingPermission() {
        TenantContext ctx = new TenantContext(org, user, Set.of("EMPLOYEE"), Set.of(Permissions.CATALOGUE_READ));
        ctx.require(Permissions.CATALOGUE_READ);
        ApiException e = assertThrows(ApiException.class, () -> ctx.require(Permissions.CATALOGUE_WRITE));
        assertEquals(403, e.status());
    }

    @Test
    void permissionsAreDefensivelyCopied() {
        Set<String> perms = new HashSet<>(Set.of(Permissions.CATALOGUE_READ));
        TenantContext ctx = new TenantContext(org, user, Set.of(), perms);
        perms.add(Permissions.CATALOGUE_WRITE);
        assertThrows(ApiException.class, () -> ctx.require(Permissions.CATALOGUE_WRITE));
    }

    @Test
    void actorIsTheSignedInUser() {
        TenantContext ctx = new TenantContext(org, user, Set.of(), Set.of());
        assertEquals(Actor.USER, ctx.actor().kind());
        assertEquals(user, ctx.actor().userId());
    }

    @Test
    void userActorNeedsAnId() {
        assertThrows(IllegalArgumentException.class, () -> new Actor(Actor.USER, null));
        assertThrows(IllegalArgumentException.class, () -> new Actor("ROBOT", null));
        assertTrue(Actor.system().userId() == null);
    }
}
