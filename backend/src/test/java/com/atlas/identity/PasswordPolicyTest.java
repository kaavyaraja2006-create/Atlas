package com.atlas.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    @Test
    void acceptsLongPassphrase() {
        assertTrue(PasswordPolicy.violations("correct horse battery", "kaavya@example.com").isEmpty());
    }

    @Test
    void rejectsShortCommonAndEmailBasedPasswords() {
        assertEquals(List.of("password: must be at least 12 characters"),
                PasswordPolicy.violations("short1!", "x@y.z"));
        assertEquals(List.of("password: is too common"),
                PasswordPolicy.violations("Password1234", "x@y.z"));
        assertEquals(List.of("password: must not contain your email name"),
                PasswordPolicy.violations("kaavya-rocks-2026", "Kaavya@example.com"));
        assertEquals(List.of("password: must not be empty"), PasswordPolicy.violations("", "x@y.z"));
        assertEquals(List.of("password: must not be empty"), PasswordPolicy.violations(null, "x@y.z"));
    }

    @Test
    void capsAtBcryptsSeventyTwoBytes() {
        assertTrue(PasswordPolicy.violations("a".repeat(72), "x@y.z").isEmpty());
        assertEquals(List.of("password: must be at most 72 bytes"),
                PasswordPolicy.violations("a".repeat(73), "x@y.z"));
        // 25 three-byte characters = 75 bytes but only 25 characters
        assertEquals(List.of("password: must be at most 72 bytes"),
                PasswordPolicy.violations("\u20ac".repeat(25), "x@y.z"));
    }

    @Test
    void countsCharactersNotUtf16Units() {
        // 11 emoji are 22 UTF-16 units but only 11 characters: too short
        assertEquals(List.of("password: must be at least 12 characters"),
                PasswordPolicy.violations("\uD83D\uDE00".repeat(11), "x@y.z"));
    }
}
