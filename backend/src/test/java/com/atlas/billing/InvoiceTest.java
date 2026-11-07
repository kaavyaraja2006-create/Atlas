package com.atlas.billing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.atlas.common.error.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InvoiceTest {

    private static Invoice invoice(String status, String total, int lineCount) {
        UUID id = UUID.randomUUID();
        List<InvoiceLine> lines = java.util.stream.IntStream.rangeClosed(1, lineCount)
                .mapToObj(i -> new InvoiceLine(UUID.randomUUID(), id, i, UUID.randomUUID(), "x", 1,
                        BigDecimal.ONE, BigDecimal.ONE))
                .toList();
        return new Invoice(id, UUID.randomUUID(), "INV-000001", UUID.randomUUID(), status, new BigDecimal(total),
                Invoice.ISSUED.equals(status) ? Instant.EPOCH : null, Instant.EPOCH, Instant.EPOCH, 1, lines);
    }

    private static int status(Runnable r) {
        return assertThrows(ApiException.class, r::run).status();
    }

    @Test
    void creditNotesCanReduceAnIssuedInvoiceExactlyToZeroButNotBelow() {
        Invoice inv = invoice(Invoice.ISSUED, "15000.0000", 1);
        inv.requireCreditable(BigDecimal.ZERO, new BigDecimal("13500"));
        inv.requireCreditable(new BigDecimal("13500"), new BigDecimal("1500.0000"));   // exactly the remainder
        assertEquals(422, status(() -> inv.requireCreditable(new BigDecimal("13500"), new BigDecimal("1500.0001"))));
        assertEquals(422, status(() -> inv.requireCreditable(BigDecimal.ZERO, BigDecimal.ZERO)));
        assertEquals(422, status(() -> inv.requireCreditable(BigDecimal.ZERO, new BigDecimal("-1"))));
    }

    @Test
    void onlyIssuedInvoicesCanBeCredited() {
        assertEquals(422, status(() -> invoice(Invoice.DRAFT, "10", 1).requireCreditable(BigDecimal.ZERO, BigDecimal.ONE)));
        assertEquals(422, status(() -> invoice(Invoice.VOID, "10", 1).requireCreditable(BigDecimal.ZERO, BigDecimal.ONE)));
    }

    @Test
    void issueAndVoidRules() {
        invoice(Invoice.DRAFT, "10", 1).requireIssuable();
        assertEquals(422, status(() -> invoice(Invoice.DRAFT, "0", 0).requireIssuable()));
        assertEquals(422, status(() -> invoice(Invoice.ISSUED, "10", 1).requireIssuable()));

        invoice(Invoice.DRAFT, "10", 1).requireVoidable(0);          // discard a mistaken draft
        invoice(Invoice.ISSUED, "10", 1).requireVoidable(0);
        assertEquals(422, status(() -> invoice(Invoice.ISSUED, "10", 1).requireVoidable(1)));
        assertEquals(422, status(() -> invoice(Invoice.VOID, "10", 1).requireVoidable(0)));
    }

    @Test
    void invoiceLinesAreAlwaysVersionZero() {
        InvoiceLine line = invoice(Invoice.DRAFT, "1", 1).lines().get(0);
        assertEquals(0L, line.ref().version());
        assertEquals("invoice_line", line.ref().entityType());
    }
}
