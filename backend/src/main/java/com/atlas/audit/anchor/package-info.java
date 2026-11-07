/**
 * External anchoring of the audit hash chain (framework-free).
 *
 * <p>The database's chain proves internal consistency only: whoever can bypass
 * its triggers can rewrite an event and recompute every later hash. Here the
 * application signs the chain head with an Ed25519 key the database never sees
 * ({@link AnchorSigner}) and appends it to a store outside the database
 * ({@link AnchorLog}). {@link AnchorVerifier} recomputes the chain from event
 * content between consecutive anchors ({@link ChainSegments}) and reports the
 * first segment whose content no longer produces the signed hash.
 *
 * <p>What it does not protect against: someone holding the signing key (the
 * application host), deletion of the anchor store unless that store is
 * write-once, and changes made after the latest anchor (exposure = the
 * anchoring interval). See docs/security/hardening.md.
 */
package com.atlas.audit.anchor;
