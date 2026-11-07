package com.atlas.audit;

import com.atlas.audit.anchor.AnchorLog;
import com.atlas.audit.anchor.AnchorReport;
import com.atlas.audit.anchor.AnchorService;
import com.atlas.audit.anchor.AnchorSigner;
import com.atlas.audit.anchor.AnchorVerifier;
import com.atlas.audit.anchor.ChainAnchor;
import com.atlas.audit.anchor.FileAnchorLog;
import com.atlas.common.observability.AtlasMetrics;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Wires anchoring from {@link AnchorProperties}. When disabled, it says so: the
 * verification endpoint reports "not configured" rather than implying integrity.
 * When enabled, a misconfiguration (missing directory, key that does not match
 * its public key) stops startup.
 */
@Component
class Anchoring {

    private static final Logger log = LoggerFactory.getLogger(Anchoring.class);

    record Verification(boolean configured, String note, AnchorReport report) {
    }

    private final AnchorRepository repo;
    private final AnchorService service;
    private final AnchorVerifier verifier;
    private final AnchorLog anchorLog;
    private final AtlasMetrics metrics;

    Anchoring(AnchorProperties p, AnchorRepository repo, AtlasMetrics metrics) {
        this.repo = repo;
        this.metrics = metrics;
        if (!p.enabled()) {
            service = null;
            verifier = null;
            anchorLog = null;
            return;
        }
        require(p.directory(), "atlas.audit.anchor.directory");
        require(p.keyId(), "atlas.audit.anchor.key-id");
        require(p.privateKey(), "atlas.audit.anchor.private-key (env ATLAS_ANCHOR_PRIVATE_KEY)");
        require(p.publicKeys(), "atlas.audit.anchor.public-keys");
        AnchorSigner signer = new AnchorSigner(p.keyId(), AnchorSigner.privateKey(p.privateKey()));
        Map<String, PublicKey> trusted = parseKeys(p.publicKeys());
        // Fail fast if the signing key and its listed public key do not belong together.
        ChainAnchor probe = signer.sign(new UUID(0, 0), 1, "0".repeat(64), Instant.EPOCH);
        if (!AnchorSigner.verify(probe, trusted)) {
            throw new IllegalStateException("atlas.audit.anchor.public-keys has no key '" + p.keyId()
                    + "' matching the private key");
        }
        anchorLog = new FileAnchorLog(Path.of(p.directory()));
        verifier = new AnchorVerifier(repo, trusted);
        service = new AnchorService(new AnchorService.Heads() {
            @Override
            public Optional<AnchorService.Head> head(UUID org) {
                return repo.head(org).map(h -> new AnchorService.Head(h.index(), h.hash()));
            }

            @Override
            public long latestAnchorIndex(UUID org) {
                return repo.latestAnchorIndex(org);
            }

            @Override
            public void recordLocally(ChainAnchor anchor, String sink) {
                repo.record(anchor, sink);
            }
        }, anchorLog, signer, Clock.systemUTC());
        log.info("Audit-chain anchoring enabled: key {}, store {}, every {}", p.keyId(), anchorLog.describe(),
                p.interval());
    }

    boolean enabled() {
        return service != null;
    }

    /** Anchors every organization whose chain moved. Failures are per organization and logged. */
    int anchorAll() {
        if (!enabled()) {
            return 0;
        }
        int anchored = 0;
        for (UUID org : repo.organizationsWithChains()) {
            try {
                if (service.anchor(org).isPresent()) {
                    anchored++;
                    metrics.anchor("taken");
                }
            } catch (RuntimeException e) {
                metrics.anchor("failed");
                log.error("Anchoring failed for organization {}", org, e);
            }
        }
        return anchored;
    }

    Verification verify(UUID org) {
        if (!enabled()) {
            return new Verification(false, "Anchoring is not configured: only internal consistency can be checked "
                    + "(POST /api/v1/audit/verify).", null);
        }
        AnchorReport report = verifier.verify(org, anchorLog);
        metrics.anchorVerification(report.status().name());
        return new Verification(true, null, report);
    }

    static Map<String, PublicKey> parseKeys(String spec) {
        Map<String, PublicKey> out = new LinkedHashMap<>();
        for (String part : spec.split(",")) {
            String p = part.strip();
            if (p.isEmpty()) {
                continue;
            }
            int eq = p.indexOf('=');
            if (eq <= 0) {
                throw new IllegalStateException("atlas.audit.anchor.public-keys entries look like keyId=base64");
            }
            out.put(p.substring(0, eq).strip(), AnchorSigner.publicKey(p.substring(eq + 1)));
        }
        return out;
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set when anchoring is enabled");
        }
    }
}
