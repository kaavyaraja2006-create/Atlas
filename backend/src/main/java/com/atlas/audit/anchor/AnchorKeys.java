package com.atlas.audit.anchor;

import java.security.KeyPair;

/**
 * Prints a new Ed25519 key pair for anchoring:
 * {@code java -cp atlas.jar com.atlas.audit.anchor.AnchorKeys <keyId>}.
 * Put the private key in the application's secret store (ATLAS_ANCHOR_PRIVATE_KEY),
 * the public key in ATLAS_ANCHOR_PUBLIC_KEYS and with whoever verifies anchors.
 */
public final class AnchorKeys {

    private AnchorKeys() {
    }

    public static void main(String[] args) {
        String keyId = args.length > 0 ? args[0] : "anchor-1";
        KeyPair pair = AnchorSigner.generate();
        System.out.println("ATLAS_ANCHOR_KEY_ID=" + keyId);
        System.out.println("ATLAS_ANCHOR_PRIVATE_KEY=" + AnchorSigner.encode(pair.getPrivate()));
        System.out.println("ATLAS_ANCHOR_PUBLIC_KEYS=" + keyId + "=" + AnchorSigner.encode(pair.getPublic()));
    }
}
