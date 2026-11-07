package com.atlas.audit;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Audit-chain anchoring. Off unless enabled; when enabled, every value is
 * required and checked at startup (see {@link Anchoring}).
 *
 * @param directory  where the anchor log is written; must be storage the database
 *                   administrators cannot rewrite (a write-once mount or synced bucket)
 * @param privateKey base64 PKCS#8 Ed25519 key (env ATLAS_ANCHOR_PRIVATE_KEY); never stored in the database
 * @param publicKeys trusted verification keys, "keyId=base64X509,keyId2=..." (old keys stay
 *                   listed after a rotation so older anchors still verify)
 */
@ConfigurationProperties("atlas.audit.anchor")
public record AnchorProperties(
        @DefaultValue("false") boolean enabled,
        String directory,
        String keyId,
        String privateKey,
        String publicKeys,
        @DefaultValue("PT5M") Duration interval) {
}
