package com.atlas.audit.anchor;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A signed statement: "organization X's audit chain had hash H at index N, at time T".
 *
 * @param hash      64 lowercase hex characters (SHA-256)
 * @param signature base64 Ed25519 signature over {@link #payload()}
 */
public record ChainAnchor(UUID organizationId, long chainIndex, String hash, Instant anchoredAt, String keyId,
                          String signature) {

    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    public ChainAnchor {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(anchoredAt, "anchoredAt");
        if (chainIndex <= 0) {
            throw new IllegalArgumentException("chainIndex must be positive");
        }
        if (hash == null || !HEX64.matcher(hash).matches()) {
            throw new IllegalArgumentException("hash must be 64 lowercase hex characters");
        }
        if (keyId == null || !KEY_ID.matcher(keyId).matches()) {
            throw new IllegalArgumentException("keyId must be 1-64 of [A-Za-z0-9._-]");
        }
    }

    /** The exact bytes signed. Versioned so the format can change without ambiguity. */
    public byte[] payload() {
        return payload(organizationId, chainIndex, hash, anchoredAt, keyId);
    }

    static byte[] payload(UUID org, long index, String hash, Instant at, String keyId) {
        return ("atlas-audit-anchor-v1|" + org + "|" + index + "|" + hash + "|" + at.toEpochMilli() + "|" + keyId)
                .getBytes(StandardCharsets.UTF_8);
    }
}
