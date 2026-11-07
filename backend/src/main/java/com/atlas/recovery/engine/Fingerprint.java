package com.atlas.recovery.engine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeSet;

/**
 * SHA-256 over "type:id@latestVersion" for every record the analysis read,
 * sorted. Any new version of any of them changes the fingerprint, which is
 * how an approved plan is detected as stale (Phase 6).
 */
public final class Fingerprint {

    private Fingerprint() {
    }

    public static String of(IncidentGraph graph) {
        TreeSet<String> lines = new TreeSet<>();
        for (EntityKey key : graph.entities()) {
            graph.latest(key).ifPresent(v -> lines.add(key + "@" + v.version()));
        }
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (String line : lines) {
                sha.update(line.getBytes(StandardCharsets.UTF_8));
                sha.update((byte) '\n');
            }
            return "sha256:" + HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
