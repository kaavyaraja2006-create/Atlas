package com.atlas.common.idempotency;

import com.atlas.common.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

/** Framework-free rules for Idempotency-Key headers. */
public final class IdempotencyKeys {

    public static final String HEADER = "Idempotency-Key";

    /** Must match the CHECK constraint on idempotency_keys.idempotency_key. */
    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9._:-]{8,128}$");

    private IdempotencyKeys() {
    }

    public static String validate(String key) {
        if (key == null || !VALID.matcher(key).matches()) {
            throw ApiException.badRequest(HEADER + " must be 8-128 characters of letters, digits, '.', '_', ':' or '-'.");
        }
        return key;
    }

    /**
     * Fingerprint of what the client asked for. The same key with a
     * different method, path or body is a client bug and is refused.
     */
    public static byte[] requestHash(String method, String path, String canonicalBody) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(method.getBytes(StandardCharsets.UTF_8));
            sha.update((byte) 0);
            sha.update(path.getBytes(StandardCharsets.UTF_8));
            sha.update((byte) 0);
            sha.update(canonicalBody.getBytes(StandardCharsets.UTF_8));
            return sha.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
