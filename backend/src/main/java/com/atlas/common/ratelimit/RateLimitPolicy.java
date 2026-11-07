package com.atlas.common.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * Which limit applies to a request, and the limiters behind them (framework-free;
 * the servlet filter only extracts method, path, client address and user).
 *
 * <ul>
 *   <li><b>sign-in</b> (POST /api/v1/auth/login): per client address. Complements the
 *       per-account lockout: lockout stops guessing one account's password, this
 *       stops one address trying many accounts.</li>
 *   <li><b>registration</b> (POST /api/v1/auth/register): per client address. Also slows
 *       probing which emails have accounts (registration says so; see hardening.md).</li>
 *   <li><b>token refresh</b>: per client address.</li>
 *   <li><b>api</b>: everything else under /api/, per authenticated user (per address if
 *       anonymous).</li>
 * </ul>
 */
public final class RateLimitPolicy {

    public record Limit(String name, int capacity, Duration period) {
    }

    /** The outcome for one request: which limit, and the limiter's decision. */
    public record Outcome(String limit, TokenBucketLimiter.Decision decision) {
    }

    /** The production defaults (also in {@code RateLimitProperties}). */
    public static final Limit SIGN_IN = new Limit("sign-in", 10, Duration.ofMinutes(1));
    public static final Limit REGISTRATION = new Limit("registration", 5, Duration.ofHours(1));
    public static final Limit REFRESH = new Limit("refresh", 30, Duration.ofMinutes(1));
    public static final Limit API = new Limit("api", 600, Duration.ofMinutes(1));

    private final Limit signInLimit;
    private final Limit registrationLimit;
    private final Limit refreshLimit;
    private final Limit apiLimit;

    private final TokenBucketLimiter signIn;
    private final TokenBucketLimiter registration;
    private final TokenBucketLimiter refresh;
    private final TokenBucketLimiter api;

    public RateLimitPolicy(Clock clock, int maxKeys) {
        this(clock, maxKeys, SIGN_IN, REGISTRATION, REFRESH, API);
    }

    public RateLimitPolicy(Clock clock, int maxKeys, Limit signIn, Limit registration, Limit refresh, Limit api) {
        this.signInLimit = signIn;
        this.registrationLimit = registration;
        this.refreshLimit = refresh;
        this.apiLimit = api;
        this.signIn = limiter(signIn, maxKeys, clock);
        this.registration = limiter(registration, maxKeys, clock);
        this.refresh = limiter(refresh, maxKeys, clock);
        this.api = limiter(api, maxKeys, clock);
    }

    public static RateLimitPolicy from(RateLimitProperties p, Clock clock) {
        return new RateLimitPolicy(clock, p.maxTrackedClients(),
                new Limit("sign-in", p.signInPerMinute(), Duration.ofMinutes(1)),
                new Limit("registration", p.registrationsPerHour(), Duration.ofHours(1)),
                new Limit("refresh", p.refreshesPerMinute(), Duration.ofMinutes(1)),
                new Limit("api", p.apiPerMinute(), Duration.ofMinutes(1)));
    }

    private static TokenBucketLimiter limiter(Limit l, int maxKeys, Clock clock) {
        return new TokenBucketLimiter(l.capacity(), l.period(), maxKeys, clock);
    }

    /**
     * @param user the authenticated user id, if any
     * @return empty for requests no limit applies to (health checks, static files)
     */
    public Optional<Outcome> check(String method, String path, String clientAddress, String user) {
        if (!path.startsWith("/api/")) {
            return Optional.empty();
        }
        String address = clientAddress == null || clientAddress.isBlank() ? "unknown" : clientAddress;
        if ("POST".equals(method)) {
            switch (path) {
                case "/api/v1/auth/login" -> {
                    return Optional.of(new Outcome(signInLimit.name(), signIn.tryAcquire(address)));
                }
                case "/api/v1/auth/register" -> {
                    return Optional.of(new Outcome(registrationLimit.name(), registration.tryAcquire(address)));
                }
                case "/api/v1/auth/refresh" -> {
                    return Optional.of(new Outcome(refreshLimit.name(), refresh.tryAcquire(address)));
                }
                default -> {
                    // fall through to the general limit
                }
            }
        }
        String key = user != null && !user.isBlank() ? "user:" + user : "addr:" + address;
        return Optional.of(new Outcome(apiLimit.name(), api.tryAcquire(key)));
    }
}
