package com.atlas.audit.anchor;

/**
 * The outcome of checking the chain against its anchors.
 *
 * @param anchorsChecked  anchors whose signature and segment were checked
 * @param lastAnchorIndex chain index of the latest anchor (0 if none)
 * @param headIndex       current chain head
 * @param fromIndex       first index of the segment found wrong (0 if intact)
 * @param toIndex         last index of that segment (0 if intact)
 */
public record AnchorReport(Status status, int anchorsChecked, long lastAnchorIndex, long headIndex, long fromIndex,
                           long toIndex, String detail) {

    public enum Status {
        /** Every anchored segment reproduces its signed hash and the chain continues from the last anchor. */
        INTACT,
        /** Nothing anchored yet: only internal consistency can be checked. */
        NO_ANCHORS,
        /** Content in [fromIndex, toIndex] no longer produces the signed hash. */
        REWRITTEN,
        /** Events in the segment are missing. */
        MISSING_EVENTS,
        /** An anchor's signature does not verify under a trusted key: the anchor store was altered. */
        BAD_SIGNATURE,
        /** The anchor store cannot be read, or its entries are out of order. */
        BAD_ANCHOR_LOG
    }

    public long unanchoredEvents() {
        return Math.max(0, headIndex - lastAnchorIndex);
    }
}
