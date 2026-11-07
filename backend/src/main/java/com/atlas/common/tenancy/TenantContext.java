package com.atlas.common.tenancy;

import com.atlas.common.error.ApiException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The organization a request acts in, who is acting, and what they may do.
 * Built per request from the database (membership + roles), never from the
 * token, so revoking a role takes effect on the next request. Controllers
 * receive it as a parameter; services call {@link #require} before acting.
 */
public record TenantContext(UUID organizationId, UUID userId, Set<String> roles, Set<String> permissions) {

    public TenantContext {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(userId, "userId");
        roles = Set.copyOf(roles);
        permissions = Set.copyOf(permissions);
    }

    public boolean has(String permission) {
        return permissions.contains(permission);
    }

    public void require(String permission) {
        if (!has(permission)) {
            throw ApiException.forbidden(permission);
        }
    }

    public Actor actor() {
        return Actor.user(userId);
    }
}
