package com.atlas.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CustomerTest {

    private final Customer c = new Customer(UUID.randomUUID(), UUID.randomUUID(), "Acme Traders", "ops@acme.test",
            Instant.EPOCH, Instant.EPOCH, 0);

    @Test
    void partialUpdateAndEmailClearing() {
        assertEquals("ops@acme.test", c.withChanges("Acme", null).email());
        assertEquals("Acme", c.withChanges("  Acme ", null).name());
        assertTrue(c.withChanges(null, "  ").email() == null);
        assertEquals("new@acme.test", c.withChanges(null, " new@acme.test ").email());
    }
}
