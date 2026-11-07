package com.atlas.common.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Token buckets keyed by string (an IP address, a user id), in memory.
 *
 * <p>Each key may burst up to {@code capacity} requests, refilled continuously at
 * {@code capacity} per {@code period}. Memory is bounded: at most {@code maxKeys}
 * buckets, the least recently used evicted first (an evicted key simply starts
 * with a full bucket again, which errs towards allowing).
 *
 * <p>Per instance: with several application instances each enforces its own
 * limit. A shared store (Redis) is the next step if a measured need appears.
 */
public final class TokenBucketLimiter {

    /** @param retryAfterSeconds when rejected, seconds until one token is available (>= 1) */
    public record Decision(boolean allowed, long remaining, long retryAfterSeconds) {
    }

    /**
     * Exact integer arithmetic: one token is {@code periodNanos} credits, and each
     * elapsed nanosecond adds {@code capacity} credits. (A floating-point refill
     * left 0.99999999 tokens after exactly the advertised Retry-After, so a client
     * that waited as told was refused again.)
     */
    private static final class Bucket {
        long credits;
        long updatedNanos;
    }

    private final int capacity;
    private final long periodNanos;
    private final long fullCredits;
    private final Clock clock;
    private final Map<String, Bucket> buckets;

    public TokenBucketLimiter(int capacity, Duration period, int maxKeys, Clock clock) {
        if (capacity < 1 || period.isZero() || period.isNegative() || maxKeys < 1) {
            throw new IllegalArgumentException("capacity, period and maxKeys must be positive");
        }
        this.capacity = capacity;
        this.periodNanos = period.toNanos();
        this.fullCredits = Math.multiplyExact((long) capacity, periodNanos);
        this.clock = Objects.requireNonNull(clock);
        this.buckets = new LinkedHashMap<>(16, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Bucket> eldest) {
                return size() > maxKeys;
            }
        };
    }

    public synchronized Decision tryAcquire(String key) {
        long now = nanos();
        Bucket b = buckets.get(key);
        if (b == null) {
            b = new Bucket();
            b.credits = fullCredits;
            b.updatedNanos = now;
            buckets.put(key, b);
        } else {
            // Elapsed time beyond one period refills fully anyway; clamping avoids overflow.
            long elapsed = Math.max(0, Math.min(now - b.updatedNanos, periodNanos));
            b.credits = Math.min(fullCredits, b.credits + elapsed * capacity);
            b.updatedNanos = now;
        }
        if (b.credits >= periodNanos) {
            b.credits -= periodNanos;
            return new Decision(true, b.credits / periodNanos, 0);
        }
        long waitNanos = ceilDiv(periodNanos - b.credits, capacity);
        return new Decision(false, 0, Math.max(1, ceilDiv(waitNanos, 1_000_000_000L)));
    }

    private static long ceilDiv(long a, long b) {
        return -Math.floorDiv(-a, b);
    }

    synchronized int size() {
        return buckets.size();
    }

    private long nanos() {
        java.time.Instant i = clock.instant();
        return i.getEpochSecond() * 1_000_000_000L + i.getNano();
    }
}
