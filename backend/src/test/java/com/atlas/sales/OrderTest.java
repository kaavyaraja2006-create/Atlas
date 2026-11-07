package com.atlas.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.atlas.audit.ChangeDiff;
import com.atlas.common.error.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderTest {

    private static final UUID ORDER_ID = UUID.randomUUID();
    private static final UUID ORG_ID = UUID.randomUUID();
    private static final UUID CUSTOMER_ID = UUID.randomUUID();
    private static final UUID CREATED_BY = UUID.randomUUID();

    private static OrderLine line(int no, int qty, String price) {
        BigDecimal p = new BigDecimal(price);
        return new OrderLine(UUID.randomUUID(), ORDER_ID, no, UUID.randomUUID(), "item " + no, qty, p,
                p.multiply(BigDecimal.valueOf(qty)), 0);
    }

    private static Order order(String status, List<OrderLine> lines) {
        return new Order(ORDER_ID, ORG_ID, "SO-000001", CUSTOMER_ID, status,
                Order.totalOf(lines), CREATED_BY, null, Instant.EPOCH, Instant.EPOCH, 3, null, lines);
    }

    @Test
    void totalIsSumOfQuantityTimesUnitPrice() {
        assertEquals(0, BigDecimal.ZERO.compareTo(Order.totalOf(List.of())));
        assertEquals(0, new BigDecimal("16500.0000").compareTo(
                Order.totalOf(List.of(line(1, 3, "5000.0000"), line(2, 1, "1500.0000")))));
    }

    @Test
    void addingAZeroPricedLineIsStillAVisibleChange() {
        Order before = order(Order.DRAFT, List.of(line(1, 1, "100")));
        Order after = order(Order.DRAFT, List.of(line(1, 1, "100"), line(2, 5, "0")));
        assertEquals(List.of("line_count"), ChangeDiff.changedFields(before.snapshot(), after.snapshot()));
    }

    @Test
    void lifecycleRules() {
        Order emptyDraft = order(Order.DRAFT, List.of());
        assertEquals(422, assertThrows(ApiException.class, emptyDraft::requireConfirmable).status());
        order(Order.DRAFT, List.of(line(1, 1, "1"))).requireConfirmable();

        Order confirmed = order(Order.CONFIRMED, List.of(line(1, 1, "1")));
        assertEquals(422, assertThrows(ApiException.class, () -> confirmed.requireDraft("take new lines")).status());
        confirmed.requireInvoiceable();
        assertEquals(422, assertThrows(ApiException.class, emptyDraft::requireInvoiceable).status());
        assertEquals(422, assertThrows(ApiException.class,
                () -> order(Order.CANCELLED, List.of()).requireDraft("be cancelled")).status());
    }

    @Test
    void linesAreImmutableAndRefIsVersioned() {
        List<OrderLine> mutable = new ArrayList<>(List.of(line(1, 1, "1")));
        Order o = order(Order.DRAFT, mutable);
        mutable.add(line(2, 1, "1"));
        assertEquals(1, o.lines().size());
        assertThrows(UnsupportedOperationException.class, () -> o.lines().add(line(3, 1, "1")));
        assertEquals("order:" + ORDER_ID + "@3", o.ref().toString());
    }
}
