package com.atlas.audit.anchor;

import java.util.List;
import java.util.UUID;

/**
 * Where anchors are kept, outside the database. In production this must be
 * write-once storage the database administrators cannot change (for example an
 * object store with retention lock); {@link FileAnchorLog} is the reference
 * implementation for a mounted directory.
 */
public interface AnchorLog {

    void append(ChainAnchor anchor);

    /**
     * Every anchor of the organization, in the order appended.
     *
     * @throws UnreadableAnchorLog if any entry cannot be parsed (treated as tampering, never skipped)
     */
    List<ChainAnchor> read(UUID organizationId);

    /** A short description for the local record, e.g. {@code file:/var/lib/atlas/anchors}. */
    String describe();

    final class UnreadableAnchorLog extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public UnreadableAnchorLog(String message) {
            super(message);
        }
    }
}
