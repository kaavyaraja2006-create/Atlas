package com.atlas.identity;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/**
 * Short-lived (15 min) HS256 access tokens. They carry identity only, never
 * organization roles: those are loaded per request so a revoked role stops
 * working immediately rather than when the token expires.
 */
@Component
class AccessTokenIssuer {

    static final String ISSUER = "atlas";

    record IssuedToken(String value, Instant expiresAt) {
    }

    private final JwtEncoder encoder;
    private final AuthProperties properties;
    private final Clock clock;

    AccessTokenIssuer(JwtEncoder encoder, AuthProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    IssuedToken issue(UserAccount user) {
        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(properties.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(user.id().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("email", user.email())
                .claim("platform_admin", user.platformAdmin())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(value, expiresAt);
    }
}
