package com.atlas.sales;

import com.atlas.audit.ChangeRecorder;
import com.atlas.catalogue.Product;
import com.atlas.catalogue.ProductService;
import com.atlas.common.db.DocumentKind;
import com.atlas.common.db.DocumentNumbers;
import com.atlas.common.error.ApiException;
import com.atlas.common.tenancy.Permissions;
import com.atlas.common.tenancy.TenantContext;
import com.atlas.common.web.PageQuery;
import com.atlas.common.web.PageResponse;
import com.atlas.provenance.ProvenanceRecorder;
import com.atlas.provenance.VersionRef;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orders and their lines. Adding a line is where derived data is born, so
 * it records, in the same transaction:
 * <ol>
 *   <li>the new line (change event, v0),</li>
 *   <li>product@vN.unit_price --DERIVED_VALUE--> order_line@v0.unit_price,</li>
 *   <li>the new order version with its recomputed total (change event),</li>
 *   <li>order_line@v.line_total --AGGREGATED_INTO--> order@vNew.total_amount
 *       for <em>every</em> line, because each order version's total depends
 *       on all of its lines, not just the newest.</li>
 * </ol>
 */
@Service
public class OrderService {

    private final OrderRepository orders;
    private final CustomerRepository customers;
    private final ProductService products;
    private final DocumentNumbers numbers;
    private final ChangeRecorder changes;
    private final ProvenanceRecorder provenance;

    OrderService(OrderRepository orders, CustomerRepository customers, ProductService products,
                 DocumentNumbers numbers, ChangeRecorder changes, ProvenanceRecorder provenance) {
        this.orders = orders;
        this.customers = customers;
        this.products = products;
        this.numbers = numbers;
        this.changes = changes;
        this.provenance = provenance;
    }

    @Transactional
    Order create(TenantContext tenant, UUID customerId) {
        tenant.require(Permissions.ORDER_WRITE);
        if (customers.find(tenant.organizationId(), customerId).isEmpty()) {
            throw ApiException.unprocessable("customerId does not refer to a customer in this organization.");
        }
        String number = numbers.next(tenant.organizationId(), DocumentKind.ORDER);
        Order order = orders.insert(tenant.organizationId(), number, customerId, tenant.userId());
        changes.recordInsert(tenant.organizationId(), tenant.actor(), Order.ENTITY_TYPE, order.id(),
                order.snapshot(), "order created");
        return order;
    }

    @Transactional(readOnly = true)
    public Order get(TenantContext tenant, UUID id) {
        tenant.require(Permissions.ORDER_READ);
        return orders.find(tenant.organizationId(), id).orElseThrow(() -> ApiException.notFound("Order"));
    }

    @Transactional(readOnly = true)
    PageResponse<Order> list(TenantContext tenant, String status, UUID customerId, PageQuery page) {
        tenant.require(Permissions.ORDER_READ);
        return PageResponse.of(orders.list(tenant.organizationId(), status, customerId, page), page,
                orders.count(tenant.organizationId(), status, customerId));
    }

    @Transactional
    Order addLine(TenantContext tenant, UUID orderId, UUID productId, int quantity) {
        tenant.require(Permissions.ORDER_WRITE);
        UUID org = tenant.organizationId();
        Order before = orders.findForUpdate(org, orderId).orElseThrow(() -> ApiException.notFound("Order"));
        before.requireDraft("take new lines");

        Product product;
        try {
            product = products.get(tenant, productId);
        } catch (ApiException e) {
            if (e.status() == 404) {
                throw ApiException.unprocessable("productId does not refer to a product in this organization.");
            }
            throw e;
        }
        if (!product.active()) {
            throw ApiException.unprocessable("Product " + product.sku() + " is inactive and cannot be ordered.");
        }

        OrderLine line = orders.insertLine(org, orderId, product.id(), product.sku() + " - " + product.name(),
                quantity, product.unitPrice());
        changes.recordInsert(org, tenant.actor(), OrderLine.ENTITY_TYPE, line.id(), line.snapshot(),
                "line " + line.lineNo() + " added to " + before.orderNumber());
        provenance.record(org, new VersionRef(ProductService.ENTITY_TYPE, product.id(), product.version()),
                "unit_price", line.ref(), "unit_price", ProvenanceRecorder.DERIVED_VALUE,
                ProvenanceRecorder.OP_ORDER_ADD_LINE);

        Order after = orders.recomputeTotal(org, orderId);
        changes.recordUpdate(org, tenant.actor(), Order.ENTITY_TYPE, orderId, after.version(),
                before.snapshot(), after.snapshot(), "line " + line.lineNo() + " added");
        for (OrderLine each : after.lines()) {
            provenance.record(org, each.ref(), "line_total", after.ref(), "total_amount",
                    ProvenanceRecorder.AGGREGATED_INTO, ProvenanceRecorder.OP_ORDER_ADD_LINE);
        }
        return after;
    }

    @Transactional
    Order confirm(TenantContext tenant, UUID id, long expectedVersion) {
        tenant.require(Permissions.ORDER_WRITE);
        Order before = lockAtVersion(tenant, id, expectedVersion);
        before.requireConfirmable();
        return transition(tenant, before, Order.CONFIRMED, expectedVersion, "order confirmed");
    }

    @Transactional
    Order cancel(TenantContext tenant, UUID id, long expectedVersion) {
        tenant.require(Permissions.ORDER_WRITE);
        Order before = lockAtVersion(tenant, id, expectedVersion);
        before.requireDraft("be cancelled");
        return transition(tenant, before, Order.CANCELLED, expectedVersion, "order cancelled");
    }

    /** Changes the delivery note of a DRAFT or CONFIRMED order (ATLAS X). Optimistic: If-Match required. */
    @Transactional
    Order updateDeliveryNote(TenantContext tenant, UUID id, long expectedVersion, String note) {
        tenant.require(Permissions.ORDER_WRITE);
        Order before = lockAtVersion(tenant, id, expectedVersion);
        if (Order.CANCELLED.equals(before.status())) {
            throw ApiException.unprocessable("Order " + before.orderNumber() + " is cancelled; its delivery note is final.");
        }
        String clean = note == null || note.isBlank() ? null : note.strip();
        Order after = orders.setDeliveryNote(tenant.organizationId(), id, clean, expectedVersion)
                .orElseThrow(() -> new IllegalStateException("Locked order row disappeared during update"));
        changes.recordUpdate(tenant.organizationId(), tenant.actor(), Order.ENTITY_TYPE, after.id(), after.version(),
                before.snapshot(), after.snapshot(), "delivery note changed");
        return after;
    }

    /**
     * For billing: locks a CONFIRMED order for the rest of the caller's
     * transaction, so it cannot change while its invoice is created.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Order lockConfirmedForInvoicing(TenantContext tenant, UUID id) {
        tenant.require(Permissions.ORDER_READ);
        Order order = orders.findForUpdate(tenant.organizationId(), id)
                .orElseThrow(() -> ApiException.notFound("Order"));
        order.requireInvoiceable();
        return order;
    }

    private Order lockAtVersion(TenantContext tenant, UUID id, long expectedVersion) {
        Order order = orders.findForUpdate(tenant.organizationId(), id)
                .orElseThrow(() -> ApiException.notFound("Order"));
        if (order.version() != expectedVersion) {
            throw ApiException.conflict("Order " + order.orderNumber() + " was changed by someone else (now at version "
                    + order.version() + "). Reload it and try again.");
        }
        return order;
    }

    private Order transition(TenantContext tenant, Order before, String status, long expectedVersion, String reason) {
        Order after = orders.setStatus(tenant.organizationId(), before.id(), status, expectedVersion)
                .orElseThrow(() -> new IllegalStateException("Locked order row disappeared during update"));
        changes.recordUpdate(tenant.organizationId(), tenant.actor(), Order.ENTITY_TYPE, after.id(), after.version(),
                before.snapshot(), after.snapshot(), reason);
        return after;
    }
}
