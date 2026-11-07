package com.atlas.audit.anchor;

import java.security.PublicKey;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Checks an organization's chain against its signed anchors, segment by segment,
 * recomputing from content. Stops at the first problem and names the segment.
 */
public final class AnchorVerifier {

    static final String GENESIS = "0".repeat(64);

    private final ChainSegments chain;
    private final Map<String, PublicKey> trustedKeys;

    public AnchorVerifier(ChainSegments chain, Map<String, PublicKey> trustedKeys) {
        this.chain = Objects.requireNonNull(chain);
        this.trustedKeys = Map.copyOf(trustedKeys);
    }

    public AnchorReport verify(UUID org, AnchorLog log) {
        long head = chain.headIndex(org);
        List<ChainAnchor> anchors;
        try {
            anchors = log.read(org);
        } catch (AnchorLog.UnreadableAnchorLog e) {
            return report(AnchorReport.Status.BAD_ANCHOR_LOG, 0, 0, head, 0, 0, e.getMessage());
        }
        if (anchors.isEmpty()) {
            return report(AnchorReport.Status.NO_ANCHORS, 0, 0, head, 0, 0,
                    "No anchors yet: history can only be checked for internal consistency");
        }
        long prevIndex = 0;
        String prevHash = GENESIS;
        ChainAnchor previous = null;
        int checked = 0;
        for (ChainAnchor a : anchors) {
            if (!a.organizationId().equals(org)) {
                return report(AnchorReport.Status.BAD_ANCHOR_LOG, checked, prevIndex, head, 0, 0,
                        "Anchor log contains another organization's anchor");
            }
            if (!AnchorSigner.verify(a, trustedKeys)) {
                return report(AnchorReport.Status.BAD_SIGNATURE, checked, prevIndex, head, a.chainIndex(), a.chainIndex(),
                        "Anchor at #" + a.chainIndex() + " is not signed by a trusted key (" + a.keyId() + ")");
            }
            if (previous != null && a.chainIndex() == previous.chainIndex() && a.hash().equals(previous.hash())) {
                continue;   // the same head anchored twice (e.g. the local record failed and the job retried)
            }
            if (a.chainIndex() <= prevIndex || (previous != null && a.anchoredAt().isBefore(previous.anchoredAt()))) {
                return report(AnchorReport.Status.BAD_ANCHOR_LOG, checked, prevIndex, head, a.chainIndex(), a.chainIndex(),
                        "Anchors out of order at #" + a.chainIndex());
            }
            ChainSegments.Recomputed r = chain.recompute(org, prevIndex, prevHash, a.chainIndex());
            if (r.lastIndex() < a.chainIndex()) {
                return report(AnchorReport.Status.MISSING_EVENTS, checked, prevIndex, head, r.lastIndex() + 1,
                        a.chainIndex(), "Event #" + (r.lastIndex() + 1) + " is missing (anchored up to #"
                                + a.chainIndex() + " at " + a.anchoredAt() + ")");
            }
            if (!r.lastHash().equals(a.hash())) {
                return report(AnchorReport.Status.REWRITTEN, checked, prevIndex, head, prevIndex + 1, a.chainIndex(),
                        "Events #" + (prevIndex + 1) + " to #" + a.chainIndex() + " were changed after they were anchored at "
                                + a.anchoredAt());
            }
            checked++;
            prevIndex = a.chainIndex();
            prevHash = a.hash();
            previous = a;
        }
        ChainSegments.Recomputed tail = chain.recompute(org, prevIndex, prevHash, head);
        if (tail.lastIndex() < head) {
            return report(AnchorReport.Status.MISSING_EVENTS, checked, prevIndex, head, tail.lastIndex() + 1, head,
                    "Event #" + (tail.lastIndex() + 1) + " after the last anchor is missing");
        }
        if (tail.firstMismatch() != null) {
            return report(AnchorReport.Status.REWRITTEN, checked, prevIndex, head, tail.firstMismatch(), head,
                    "The stored chain no longer continues from the anchor at #" + prevIndex + " (first difference at #"
                            + tail.firstMismatch() + ")");
        }
        return report(AnchorReport.Status.INTACT, checked, prevIndex, head, 0, 0,
                checked + " anchor(s) verified; " + (head - prevIndex) + " event(s) after the latest anchor");
    }

    private static AnchorReport report(AnchorReport.Status s, int checked, long last, long head, long from, long to,
                                       String detail) {
        return new AnchorReport(s, checked, last, head, from, to, detail);
    }
}
