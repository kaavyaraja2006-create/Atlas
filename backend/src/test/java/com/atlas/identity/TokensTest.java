package com.atlas.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TokensTest {

    @Test
    void tokensAreUrlSafeAndUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String t = Tokens.newOpaqueToken();
            assertEquals(43, t.length());                 // 32 bytes, base64url, no padding
            assertTrue(t.matches("^[A-Za-z0-9_-]+$"));
            assertTrue(seen.add(t));
        }
    }

    @Test
    void hashIsSha256AndDeterministic() {
        byte[] h = Tokens.sha256("token-1");
        assertEquals(32, h.length);
        assertTrue(Arrays.equals(h, Tokens.sha256("token-1")));
        assertFalse(Arrays.equals(h, Tokens.sha256("token-2")));
        // Known answer: sha256("abc")
        StringBuilder hex = new StringBuilder();
        for (byte b : Tokens.sha256("abc")) {
            hex.append(String.format("%02x", b));
        }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex.toString());
    }
}
