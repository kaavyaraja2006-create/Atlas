package com.atlas.audit.anchor;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Takes an anchor of an organization's chain head if it moved since the last one.
 * The external append happens first: if recording it locally fails afterwards, the
 * external copy (the one that counts) is already safe and the next run re-anchors.
 */
public final class AnchorService {

    /** What the service needs from the database. */
    public interface Heads {
        /** Current head (index, hex hash), or empty if the organization has no events. */
        Optional<Head> head(UUID org);

        long latestAnchorIndex(UUID org);

        void recordLocally(ChainAnchor anchor, String sink);
    }

    public record Head(long index, String hash) {
    }

    private final Heads heads;
    private final AnchorLog log;
    private final AnchorSigner signer;
    private final Clock clock;

    public AnchorService(Heads heads, AnchorLog log, AnchorSigner signer, Clock clock) {
        this.heads = Objects.requireNonNull(heads);
        this.log = Objects.requireNonNull(log);
        this.signer = Objects.requireNonNull(signer);
        this.clock = Objects.requireNonNull(clock);
    }

    /** @return the new anchor, or empty if nothing changed since the last one */
    public Optional<ChainAnchor> anchor(UUID org) {
        Optional<Head> head = heads.head(org);
        if (head.isEmpty() || head.get().index() == 0 || head.get().index() <= heads.latestAnchorIndex(org)) {
            return Optional.empty();
        }
        ChainAnchor a = signer.sign(org, head.get().index(), head.get().hash(), clock.instant());
        log.append(a);
        heads.recordLocally(a, log.describe());
        return Optional.of(a);
    }
}
