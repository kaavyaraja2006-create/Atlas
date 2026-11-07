package com.atlas.common.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * The application's own metrics (Micrometer, scraped at /actuator/prometheus).
 * Tags are small fixed sets (outcomes, limit names): never user input, ids or
 * tenant names, so cardinality stays bounded.
 */
@Component
public class AtlasMetrics {

    private final MeterRegistry registry;

    public AtlasMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** One sign-in attempt: SUCCESS, BAD_CREDENTIALS, LOCKED or DISABLED. */
    public void login(String outcome) {
        registry.counter("atlas.auth.logins", "outcome", outcome).increment();
    }

    /** A request refused by a rate limit (sign-in, registration, refresh, api). */
    public void rateLimited(String limit) {
        registry.counter("atlas.ratelimit.rejected", "limit", limit).increment();
    }

    /** Step outcomes of a recovery execution (DONE, FAILED, BLOCKED, ...). */
    public void recoverySteps(String status, long count) {
        registry.counter("atlas.recovery.steps", "status", status).increment(count);
    }

    /** An anchoring attempt: taken or failed. */
    public void anchor(String result) {
        registry.counter("atlas.audit.anchors", "result", result).increment();
    }

    /** The outcome of an anchor verification (INTACT, REWRITTEN, ...). */
    public void anchorVerification(String status) {
        registry.counter("atlas.audit.anchor_verifications", "status", status).increment();
    }

    /** ATLAS X: integrity incidents opened or cleared, per rule kind and severity (alerting). */
    public void integrity(String kind, String severity, String event, long count) {
        registry.counter("atlas.integrity.incidents", "kind", kind, "severity", severity, "event", event).increment(count);
    }
}
