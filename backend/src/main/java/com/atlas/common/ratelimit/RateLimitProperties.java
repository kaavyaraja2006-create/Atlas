package com.atlas.common.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Request limits (see {@link RateLimitPolicy}). Defaults suit one instance; tests raise them. */
@ConfigurationProperties("atlas.rate-limit")
public record RateLimitProperties(
        @DefaultValue("10") int signInPerMinute,
        @DefaultValue("5") int registrationsPerHour,
        @DefaultValue("30") int refreshesPerMinute,
        @DefaultValue("600") int apiPerMinute,
        @DefaultValue("100000") int maxTrackedClients) {
}
