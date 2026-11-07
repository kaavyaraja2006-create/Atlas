package com.atlas.common.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DocumentKindTest {

    @Test
    void formatsZeroPaddedNumbersWithPrefix() {
        assertEquals("SO-000001", DocumentKind.ORDER.format(1));
        assertEquals("INV-000042", DocumentKind.INVOICE.format(42));
        assertEquals("CN-1234567", DocumentKind.CREDIT_NOTE.format(1_234_567));
    }

    @Test
    void numbersStartAtOne() {
        assertThrows(IllegalArgumentException.class, () -> DocumentKind.INVOICE.format(0));
    }
}
