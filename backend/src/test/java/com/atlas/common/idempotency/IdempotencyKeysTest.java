package com.atlas.common.idempotency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atlas.common.error.ApiException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class IdempotencyKeysTest {

    @Test
    void acceptsUuidsAndReadableKeys() {
        assertEquals("order-create-0001", IdempotencyKeys.validate("order-create-0001"));
        IdempotencyKeys.validate("3f1c2b9e-0d7a-4b55-9a1e-3b2c6f7d8e90");
        IdempotencyKeys.validate("client:42.retry_1");
    }

    @Test
    void rejectsShortLongOrUnsafeKeys() {
        for (String bad : new String[] {null, "", "short", "has space key", "x".repeat(129), "semi;colon-key"}) {
            assertEquals(400, assertThrows(ApiException.class, () -> IdempotencyKeys.validate(bad)).status());
        }
    }

    @Test
    void requestHashSeparatesMethodPathAndBody() {
        byte[] base = IdempotencyKeys.requestHash("POST", "/api/v1/orders", "{\"a\":1}");
        assertEquals(32, base.length);
        assertTrue(Arrays.equals(base, IdempotencyKeys.requestHash("POST", "/api/v1/orders", "{\"a\":1}")));
        assertFalse(Arrays.equals(base, IdempotencyKeys.requestHash("PUT", "/api/v1/orders", "{\"a\":1}")));
        assertFalse(Arrays.equals(base, IdempotencyKeys.requestHash("POST", "/api/v1/order", "{\"a\":1}")));
        assertFalse(Arrays.equals(base, IdempotencyKeys.requestHash("POST", "/api/v1/orders", "{\"a\":2}")));
        // Field boundaries matter: ("ab","c") must not collide with ("a","bc").
        assertFalse(Arrays.equals(IdempotencyKeys.requestHash("POST", "ab", "c"),
                IdempotencyKeys.requestHash("POST", "a", "bc")));
    }
}
