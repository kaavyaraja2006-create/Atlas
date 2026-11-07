package com.atlas.common.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.atlas.common.error.ApiException;
import org.junit.jupiter.api.Test;

class ETagsTest {

    @Test
    void formatsVersionAsStrongEtag() {
        assertEquals("\"7\"", ETags.of(7));
    }

    @Test
    void parsesQuotedVersion() {
        assertEquals(7L, ETags.requireVersion("\"7\""));
        assertEquals(0L, ETags.requireVersion("  \"0\"  "));
    }

    @Test
    void missingHeaderIs428() {
        assertEquals(428, assertThrows(ApiException.class, () -> ETags.requireVersion(null)).status());
        assertEquals(428, assertThrows(ApiException.class, () -> ETags.requireVersion(" ")).status());
    }

    @Test
    void malformedOrWeakEtagIs400() {
        for (String bad : new String[] {"7", "W/\"7\"", "\"\"", "\"-1\"", "\"abc\"", "\"7", "\"99999999999999999999\""}) {
            assertEquals(400, assertThrows(ApiException.class, () -> ETags.requireVersion(bad)).status(), bad);
        }
    }
}
