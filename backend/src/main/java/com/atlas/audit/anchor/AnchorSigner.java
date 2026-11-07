package com.atlas.audit.anchor;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Ed25519 signing and verification of anchors (JDK built-in, no dependencies).
 * The private key comes from the environment of the application, never from
 * the database; verifiers need only the public keys.
 */
public final class AnchorSigner {

    private final String keyId;
    private final PrivateKey privateKey;

    public AnchorSigner(String keyId, PrivateKey privateKey) {
        this.keyId = Objects.requireNonNull(keyId);
        this.privateKey = Objects.requireNonNull(privateKey);
        if (!"EdDSA".equals(privateKey.getAlgorithm()) && !"Ed25519".equals(privateKey.getAlgorithm())) {
            throw new IllegalArgumentException("anchor signing key must be Ed25519, not " + privateKey.getAlgorithm());
        }
    }

    public String keyId() {
        return keyId;
    }

    public ChainAnchor sign(UUID org, long index, String hash, Instant at) {
        Instant millis = Instant.ofEpochMilli(at.toEpochMilli());   // the payload carries millisecond precision
        byte[] payload = ChainAnchor.payload(org, index, hash, millis, keyId);
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(privateKey);
            s.update(payload);
            return new ChainAnchor(org, index, hash, millis, keyId, Base64.getEncoder().encodeToString(s.sign()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("could not sign anchor", e);
        }
    }

    /** Whether the anchor's signature is valid under the trusted key it names. Unknown key ids are not trusted. */
    public static boolean verify(ChainAnchor a, Map<String, PublicKey> trusted) {
        PublicKey key = trusted.get(a.keyId());
        if (key == null || a.signature() == null) {
            return false;
        }
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(key);
            s.update(a.payload());
            return s.verify(Base64.getDecoder().decode(a.signature()));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ key handling

    public static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A private key from base64 PKCS#8 (what {@code AnchorKeys} prints). */
    public static PrivateKey privateKey(String base64Pkcs8) {
        try {
            return KeyFactory.getInstance("Ed25519")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64Pkcs8.strip())));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("not a base64 PKCS#8 Ed25519 private key", e);
        }
    }

    /** A public key from base64 X.509 SubjectPublicKeyInfo. */
    public static PublicKey publicKey(String base64X509) {
        try {
            return KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64X509.strip())));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("not a base64 X.509 Ed25519 public key", e);
        }
    }

    public static String encode(java.security.Key key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }
}
