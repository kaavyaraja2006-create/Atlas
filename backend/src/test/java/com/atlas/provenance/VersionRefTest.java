package com.atlas.provenance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class VersionRefTest {

    @Test
    void identifiesOneVersionOfOneRecord() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000007");
        assertEquals("product:" + id + "@7", new VersionRef("product", id, 7).toString());
        assertEquals(new VersionRef("product", id, 7), new VersionRef("product", id, 7));
        assertThrows(IllegalArgumentException.class, () -> new VersionRef("product", id, -1));
        assertThrows(NullPointerException.class, () -> new VersionRef(null, id, 0));
    }
}
