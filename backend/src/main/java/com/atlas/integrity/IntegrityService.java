package com.atlas.integrity;

import com.atlas.common.error.ApiException;
import com.atlas.common.observability.AtlasMetrics;
import com.atlas.common.tenancy.Permissions;
import com.atlas.common.tenancy.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Continuous data-integrity monitoring (ATLAS X, Milestone 6). Evaluates an organization's
 * enabled rules, opens one incident per (rule, subject) and refreshes it while the finding
 * persists, clears it when the finding disappears, and records every status change.
 * It never writes a business record: findings are evidence for a person (and a link into
 * provenance and recovery), not corrections.
 */
@Service
class IntegrityService {

    private static final Logger log = LoggerFactory.getLogger(IntegrityService.class);

    /** A rule kind this application can evaluate, with sensible defaults. */
    record RuleKind(String kind, String defaultCode, String name, String description, String defaultSeverity,
                    Map<String, Object> defaultConfig) {
    }

    static final List<RuleKind> KINDS = List.of(
            new RuleKind("ORDER_TOTAL_MATCHES_LINES", "ORDER_TOTALS", "Order totals match their lines",
                    "Every non-cancelled order's total equals the sum of quantity × unit price of its lines.",
                    "HIGH", Map.of("tolerance", "0.00")),
            new RuleKind("INVOICE_TOTAL_MATCHES_LINES", "INVOICE_TOTALS", "Invoice totals match their lines",
                    "Every invoice that is not void totals its lines exactly.", "CRITICAL", Map.of("tolerance", "0.00")),
            new RuleKind("DERIVED_VALUE_MATCHES_SOURCE", "LINE_PRICE_MATCHES_SOURCE",
                    "Line prices match their recorded source",
                    "An order line's price equals the product price it was derived from; a difference means it was "
                            + "changed without provenance (a discount, or a mistake).", "MEDIUM", Map.of("tolerance", "0.00")),
            new RuleKind("REQUIRED_PROVENANCE", "REQUIRED_PROVENANCE", "Derived values have provenance",
                    "Prices and totals that must be derived from inputs were written with recorded provenance.",
                    "MEDIUM", Map.of()),
            new RuleKind("AUDIT_CHAIN_INTACT", "AUDIT_CHAIN", "Audit chain is intact",
                    "The hash chain over the organization's audit history verifies.", "CRITICAL", Map.of()));

    record CheckResult(UUID runId, String status, int rulesEvaluated, int findings, int opened, int cleared,
                       List<String> failures) {
    }

    record IncidentDetail(IntegrityRepository.Incident incident, List<IntegrityRepository.Event> history) {
    }

    private final IntegrityRepository repo;
    private final TransactionTemplate tx;
    private final AtlasMetrics metrics;

    IntegrityService(IntegrityRepository repo, PlatformTransactionManager transactions, AtlasMetrics metrics) {
        this.repo = repo;
        this.tx = new TransactionTemplate(transactions);
        this.metrics = metrics;
    }

    // ------------------------------------------------------------------ checks

    CheckResult runManual(TenantContext tenant) {
        tenant.require(Permissions.INTEGRITY_MANAGE);
        return run(tenant.organizationId(), "MANUAL", tenant.userId());
    }

    /** One check run over every enabled rule; each rule in its own transaction, so one failure does not stop the rest. */
    CheckResult run(UUID orgId, String trigger, UUID startedBy) {
        UUID runId = tx.execute(s -> repo.startRun(orgId, trigger, startedBy));
        int evaluated = 0;
        int findings = 0;
        int opened = 0;
        int cleared = 0;
        List<String> failures = new ArrayList<>();
        for (IntegrityRepository.Rule rule : repo.rules(orgId)) {
            if (!rule.enabled()) {
                continue;
            }
            try {
                int[] r = tx.execute(s -> evaluate(orgId, runId, rule));
                evaluated++;
                findings += r[0];
                opened += r[1];
                cleared += r[2];
            } catch (RuntimeException e) {
                log.warn("Integrity rule {} failed for organization {}", rule.code(), orgId, e);
                failures.add(rule.code() + ": " + e.getClass().getSimpleName());
            }
        }
        String status = failures.isEmpty() ? "COMPLETED" : "FAILED";
        String failure = failures.isEmpty() ? null : String.join("; ", failures);
        int fEvaluated = evaluated;
        int fFindings = findings;
        int fOpened = opened;
        int fCleared = cleared;
        tx.executeWithoutResult(s -> repo.finishRun(orgId, runId, status, fEvaluated, fFindings, fOpened, fCleared,
                failure == null ? null : failure.substring(0, Math.min(1000, failure.length()))));
        return new CheckResult(runId, status, evaluated, findings, opened, cleared, failures);
    }

    private int[] evaluate(UUID orgId, UUID runId, IntegrityRepository.Rule rule) {
        BigDecimal tolerance = tolerance(rule.config());
        List<IntegrityRepository.Finding> found = repo.evaluate(orgId, rule.kind(), tolerance);
        int opened = 0;
        for (IntegrityRepository.Finding f : found) {
            Map.Entry<UUID, Boolean> r = repo.upsertIncident(orgId, rule, runId, f);
            if (r.getValue()) {
                opened++;
                repo.event(orgId, r.getKey(), null, "OPEN", null, "found by " + rule.code(), runId);
            }
        }
        List<UUID> gone = repo.clearUnseen(orgId, rule.id(), runId);
        for (UUID id : gone) {
            repo.event(orgId, id, null, "CLEARED", null, "no longer found by " + rule.code(), runId);
        }
        if (opened > 0) {
            metrics.integrity(rule.kind(), rule.severity(), "opened", opened);
            log.warn("Integrity rule {} opened {} incident(s) in organization {}", rule.code(), opened, orgId);
        }
        if (!gone.isEmpty()) {
            metrics.integrity(rule.kind(), rule.severity(), "cleared", gone.size());
        }
        return new int[] {found.size(), opened, gone.size()};
    }

    private static BigDecimal tolerance(Object config) {
        if (config instanceof JsonNode node && node.hasNonNull("tolerance")) {
            BigDecimal t = new BigDecimal(node.get("tolerance").asText());
            if (t.signum() < 0) {
                throw new IllegalStateException("tolerance must not be negative");
            }
            return t;
        }
        return BigDecimal.ZERO;
    }

    List<IntegrityRepository.Run> runs(TenantContext tenant) {
        tenant.require(Permissions.INTEGRITY_READ);
        return repo.runs(tenant.organizationId());
    }

    // ------------------------------------------------------------------ rules

    List<RuleKind> kinds(TenantContext tenant) {
        tenant.require(Permissions.INTEGRITY_READ);
        return KINDS;
    }

    List<IntegrityRepository.Rule> rules(TenantContext tenant) {
        tenant.require(Permissions.INTEGRITY_READ);
        return repo.rules(tenant.organizationId());
    }

    IntegrityRepository.Rule create(TenantContext tenant, String kind, String code, String name, String severity,
                                    Boolean enabled, Map<String, Object> config) {
        tenant.require(Permissions.INTEGRITY_MANAGE);
        RuleKind k = KINDS.stream().filter(x -> x.kind().equals(kind)).findFirst()
                .orElseThrow(() -> ApiException.badRequest("Unknown rule kind: " + kind));
        Map<String, Object> cfg = new LinkedHashMap<>(k.defaultConfig());
        if (config != null) {
            cfg.putAll(config);
        }
        validateConfig(cfg);
        String c = code == null || code.isBlank() ? k.defaultCode() : code;
        if (repo.rules(tenant.organizationId()).stream().anyMatch(r -> r.code().equals(c))) {
            throw ApiException.conflict("A rule with code " + c + " already exists.");
        }
        return tx.execute(s -> {
            IntegrityRepository.Rule r = repo.insertRule(tenant.organizationId(), c, kind,
                    name == null || name.isBlank() ? k.name() : name.strip(),
                    severity == null ? k.defaultSeverity() : severity, enabled == null || enabled, cfg, tenant.userId());
            repo.insertVersion(tenant.organizationId(), r, tenant.userId());
            return r;
        });
    }

    IntegrityRepository.Rule update(TenantContext tenant, UUID id, long expectedVersion, String name, String severity,
                                    boolean enabled, Map<String, Object> config) {
        tenant.require(Permissions.INTEGRITY_MANAGE);
        validateConfig(config == null ? Map.of() : config);
        return tx.execute(s -> {
            IntegrityRepository.Rule r = repo.updateRule(tenant.organizationId(), id, expectedVersion, name.strip(),
                    severity, enabled, config == null ? Map.of() : config).orElseThrow(() -> {
                        if (repo.rule(tenant.organizationId(), id).isEmpty()) {
                            return ApiException.notFound("Integrity rule");
                        }
                        return ApiException.conflict("The rule changed since you loaded it (version " + expectedVersion
                                + "). Reload and try again.");
                    });
            repo.insertVersion(tenant.organizationId(), r, tenant.userId());
            return r;
        });
    }

    List<IntegrityRepository.RuleVersion> versions(TenantContext tenant, UUID ruleId) {
        tenant.require(Permissions.INTEGRITY_READ);
        repo.rule(tenant.organizationId(), ruleId).orElseThrow(() -> ApiException.notFound("Integrity rule"));
        return repo.versions(tenant.organizationId(), ruleId);
    }

    private static void validateConfig(Map<String, Object> cfg) {
        for (String key : cfg.keySet()) {
            if (!key.equals("tolerance")) {
                throw ApiException.badRequest("Unknown configuration key: " + key + " (only 'tolerance' is supported)");
            }
        }
        Object t = cfg.get("tolerance");
        if (t != null) {
            try {
                if (new BigDecimal(t.toString()).signum() < 0) {
                    throw ApiException.badRequest("tolerance must not be negative");
                }
            } catch (NumberFormatException e) {
                throw ApiException.badRequest("tolerance must be a number");
            }
        }
    }

    // ------------------------------------------------------------------ incidents

    List<IntegrityRepository.Incident> incidents(TenantContext tenant, String status, String severity, int limit,
                                                 long offset) {
        tenant.require(Permissions.INTEGRITY_READ);
        return repo.incidents(tenant.organizationId(), status, severity, limit, offset);
    }

    long count(TenantContext tenant, String status, String severity) {
        return repo.countIncidents(tenant.organizationId(), status, severity);
    }

    IncidentDetail incident(TenantContext tenant, UUID id) {
        tenant.require(Permissions.INTEGRITY_READ);
        IntegrityRepository.Incident i = repo.incident(tenant.organizationId(), id)
                .orElseThrow(() -> ApiException.notFound("Integrity incident"));
        return new IncidentDetail(i, repo.events(tenant.organizationId(), id));
    }

    /** ACKNOWLEDGED (someone is on it) or RESOLVED (closed by a person, with a note). Never touches business data. */
    IncidentDetail transition(TenantContext tenant, UUID id, long expectedVersion, String to, String note) {
        tenant.require(Permissions.INTEGRITY_MANAGE);
        tx.executeWithoutResult(s -> {
            IntegrityRepository.Incident i = repo.incident(tenant.organizationId(), id)
                    .orElseThrow(() -> ApiException.notFound("Integrity incident"));
            if (!repo.setStatus(tenant.organizationId(), id, expectedVersion, to)) {
                throw ApiException.conflict("Incident " + i.title() + " is " + i.status() + " at version " + i.version()
                        + "; it cannot move to " + to + " from what you saw (version " + expectedVersion + ").");
            }
            repo.event(tenant.organizationId(), id, i.status(), to, tenant.userId(), note, null);
        });
        return incident(tenant, id);
    }
}
