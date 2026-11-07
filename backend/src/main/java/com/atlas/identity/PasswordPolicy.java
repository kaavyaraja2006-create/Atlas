package com.atlas.identity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Length-based policy in the spirit of NIST SP 800-63B: long passphrases,
 * no composition rules, reject the obviously guessable. The 72-byte cap
 * exists because BCrypt ignores everything after 72 bytes, so a longer
 * password would give a false sense of strength.
 */
public final class PasswordPolicy {

    public static final int MIN_CHARS = 12;
    public static final int MAX_BYTES = 72;

    private static final Set<String> COMMON = Set.of(
            "password1234", "password12345", "123456789012", "qwertyuiop12", "passwordpassword",
            "administrator", "letmein12345", "welcome12345", "iloveyou1234", "changeme1234");

    private PasswordPolicy() {
    }

    public static List<String> violations(String password, String email) {
        if (password == null || password.isEmpty()) {
            return List.of("password: must not be empty");
        }
        List<String> problems = new ArrayList<>();
        String lower = password.toLowerCase(Locale.ROOT);
        if (password.codePointCount(0, password.length()) < MIN_CHARS) {
            problems.add("password: must be at least " + MIN_CHARS + " characters");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            problems.add("password: must be at most " + MAX_BYTES + " bytes");
        }
        if (COMMON.contains(lower)) {
            problems.add("password: is too common");
        }
        if (email != null && email.contains("@")) {
            String localPart = email.substring(0, email.indexOf('@')).toLowerCase(Locale.ROOT);
            if (localPart.length() >= 4 && lower.contains(localPart)) {
                problems.add("password: must not contain your email name");
            }
        }
        return List.copyOf(problems);
    }
}
