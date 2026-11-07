package com.atlas.identity;

import java.time.Duration;
import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Authentication settings. The JWT signing secret has no default: startup
 * fails unless ATLAS_JWT_SECRET provides at least 256 bits, base64-encoded.
 */
@ConfigurationProperties("atlas.auth")
public record AuthProperties(
        String jwtSecret,
        @DefaultValue("PT15M") Duration accessTokenTtl,
        @DefaultValue("P14D") Duration refreshTokenTtl,
        @DefaultValue("true") boolean cookieSecure,
        @DefaultValue("5") int maxFailedLogins,
        @DefaultValue("PT15M") Duration lockDuration) {

    public AuthProperties {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException("atlas.auth.jwt-secret (env ATLAS_JWT_SECRET) must be set");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(jwtSecret);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("atlas.auth.jwt-secret must be base64", e);
        }
        if (key.length < 32) {
            throw new IllegalStateException("atlas.auth.jwt-secret must decode to at least 32 bytes");
        }
        if (maxFailedLogins < 2) {
            throw new IllegalStateException("atlas.auth.max-failed-logins must be at least 2");
        }
    }

    public byte[] jwtKeyBytes() {
        return Base64.getDecoder().decode(jwtSecret);
    }
}
