package com.atlas.audit.anchor;

import java.util.UUID;

/** Recomputation of the chain from event content (V9 {@code recompute_audit_chain}). */
public interface ChainSegments {

    /**
     * @param lastIndex     last contiguous index reached (below {@code upto} means events are missing)
     * @param lastHash      hash recomputed from content at lastIndex, hex
     * @param firstMismatch first index whose stored hashes disagree with the recomputation, or null
     */
    record Recomputed(long lastIndex, String lastHash, Long firstMismatch) {
    }

    /** Recomputes from the trusted point (after, prevHash) up to upto. */
    Recomputed recompute(UUID organizationId, long after, String prevHash, long upto);

    long headIndex(UUID organizationId);
}
