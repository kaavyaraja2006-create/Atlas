package com.atlas.common.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RateLimitTest {

    /** A clock the test moves by hand. */
    static final class ManualClock extends Clock {
        Instant now = Instant.parse("2026-11-06T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    @Test
    void aBurstUpToCapacityIsAllowedThenRejectedWithRetryAfter() {
        ManualClock clock = new ManualClock();
        TokenBucketLimiter l = new TokenBucketLimiter(10, Duration.ofMinutes(1), 100, clock);
        for (int i = 0; i < 10; i++) {
            assertTrue(l.tryAcquire("ip-1").allowed(), "request " + (i + 1));
        }
        TokenBucketLimiter.Decision d = l.tryAcquire("ip-1");
        assertFalse(d.allowed());
        assertEquals(6, d.retryAfterSeconds(), "one token every 6 s at 10 per minute");
        assertTrue(l.tryAcquire("ip-2").allowed(), "other keys are not affected");
    }

    @Test
    void tokensRefillContinuouslyButNeverAboveCapacity() {
        ManualClock clock = new ManualClock();
        TokenBucketLimiter l = new TokenBucketLimiter(10, Duration.ofMinutes(1), 100, clock);
        for (int i = 0; i < 10; i++) {
            l.tryAcquire("k");
        }
        clock.advance(Duration.ofSeconds(5));
        assertFalse(l.tryAcquire("k").allowed(), "5 s refills less than one token");
        clock.advance(Duration.ofSeconds(1));
        assertTrue(l.tryAcquire("k").allowed(), "6 s refills one");
        TokenBucketLimiter.Decision wait = l.tryAcquire("k");
        clock.advance(Duration.ofSeconds(wait.retryAfterSeconds()));
        assertTrue(l.tryAcquire("k").allowed(), "a client that waits exactly Retry-After is allowed");
        clock.advance(Duration.ofHours(1));
        int allowed = 0;
        while (l.tryAcquire("k").allowed()) {
            allowed++;
        }
        assertEquals(10, allowed, "an idle hour refills to capacity, not beyond");
    }

    @Test
    void memoryIsBoundedByEvictingTheLeastRecentlyUsedKey() {
        ManualClock clock = new ManualClock();
        TokenBucketLimiter l = new TokenBucketLimiter(1, Duration.ofMinutes(1), 3, clock);
        l.tryAcquire("a");
        l.tryAcquire("b");
        l.tryAcquire("c");
        assertFalse(l.tryAcquire("a").allowed());   // touches a: now b is the oldest
        l.tryAcquire("d");                           // evicts b
        assertEquals(3, l.size());
        assertTrue(l.tryAcquire("b").allowed(), "an evicted key starts again with a full bucket");
        assertFalse(l.tryAcquire("a").allowed(), "a was kept: still empty");
    }

    @Test
    void concurrentRequestsNeverExceedCapacity() throws InterruptedException {
        TokenBucketLimiter l = new TokenBucketLimiter(100, Duration.ofDays(1), 10, Clock.systemUTC());
        AtomicInteger allowed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (int i = 0; i < 1000; i++) {
            pool.submit(() -> {
                if (l.tryAcquire("shared").allowed()) {
                    allowed.incrementAndGet();
                }
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(100, allowed.get());
    }

    @Test
    void invalidSettingsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketLimiter(0, Duration.ofMinutes(1), 1, Clock.systemUTC()));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketLimiter(1, Duration.ZERO, 1, Clock.systemUTC()));
    }

    @Test
    void signInIsLimitedPerAddressSeparatelyFromTheApi() {
        ManualClock clock = new ManualClock();
        RateLimitPolicy p = new RateLimitPolicy(clock, 1000);
        for (int i = 0; i < 10; i++) {
            assertTrue(p.check("POST", "/api/v1/auth/login", "203.0.113.5", null).orElseThrow().decision().allowed());
        }
        RateLimitPolicy.Outcome o = p.check("POST", "/api/v1/auth/login", "203.0.113.5", null).orElseThrow();
        assertEquals("sign-in", o.limit());
        assertFalse(o.decision().allowed());
        assertTrue(p.check("POST", "/api/v1/auth/login", "198.51.100.7", null).orElseThrow().decision().allowed(),
                "another address may still sign in");
        assertTrue(p.check("GET", "/api/v1/products", "203.0.113.5", "user-1").orElseThrow().decision().allowed(),
                "the general API limit is separate");
    }

    @Test
    void registrationIsTheStrictestAndRefreshHasItsOwnLimit() {
        ManualClock clock = new ManualClock();
        RateLimitPolicy p = new RateLimitPolicy(clock, 1000);
        for (int i = 0; i < 5; i++) {
            assertTrue(p.check("POST", "/api/v1/auth/register", "203.0.113.5", null).orElseThrow().decision().allowed());
        }
        RateLimitPolicy.Outcome o = p.check("POST", "/api/v1/auth/register", "203.0.113.5", null).orElseThrow();
        assertFalse(o.decision().allowed());
        assertEquals(720, o.decision().retryAfterSeconds(), "5 per hour: one every 12 minutes");
        assertEquals("refresh", p.check("POST", "/api/v1/auth/refresh", "203.0.113.5", null).orElseThrow().limit());
    }

    @Test
    void theApiLimitFollowsTheUserNotTheAddress() {
        ManualClock clock = new ManualClock();
        RateLimitPolicy p = new RateLimitPolicy(clock, 1000);
        for (int i = 0; i < 600; i++) {
            p.check("GET", "/api/v1/orders", "10.0.0." + (i % 50), "user-1");
        }
        assertFalse(p.check("GET", "/api/v1/orders", "10.0.0.99", "user-1").orElseThrow().decision().allowed(),
                "changing address does not reset a user's limit");
        assertTrue(p.check("GET", "/api/v1/orders", "10.0.0.99", "user-2").orElseThrow().decision().allowed());
        assertTrue(p.check("GET", "/actuator/health", "10.0.0.99", null).isEmpty(), "health checks are not limited");
    }

    @Test
    void waitingExactlyRetryAfterIsEnoughForEveryConfiguredLimit() {
        for (RateLimitPolicy.Limit limit : new RateLimitPolicy.Limit[] {
                RateLimitPolicy.SIGN_IN, RateLimitPolicy.REGISTRATION, RateLimitPolicy.REFRESH, RateLimitPolicy.API}) {
            ManualClock clock = new ManualClock();
            TokenBucketLimiter l = new TokenBucketLimiter(limit.capacity(), limit.period(), 10, clock);
            for (int round = 0; round < 50; round++) {
                TokenBucketLimiter.Decision d;
                while ((d = l.tryAcquire("k")).allowed()) {
                    // drain
                }
                clock.advance(Duration.ofSeconds(d.retryAfterSeconds()));
                assertTrue(l.tryAcquire("k").allowed(), limit.name() + " round " + round + " after " + d.retryAfterSeconds() + " s");
            }
        }
    }
}
