package com.atlas.billing;

import com.atlas.audit.ChangeRecorder;
import com.atlas.common.db.DocumentKind;
import com.atlas.common.db.DocumentNumbers;
import com.atlas.common.error.ApiException;
import com.atlas.common.tenancy.Permissions;
import com.atlas.common.tenancy.TenantContext;
import com.atlas.common.web.PageQuery;
import com.atlas.common.web.PageResponse;
import com.atlas.provenance.ProvenanceRecorder;
import com.atlas.sales.Order;
import com.atlas.sales.OrderLine;
import com.atlas.sales.OrderService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Invoicing copies each order line into an invoice line and records:
 * order_line@v.unit_price --COPIED_INTO--> invoice_line@0.unit_price and
 * invoice_line@0.line_total --AGGREGATED_INTO--> invoice@0.total_amount.
 * With the pricing edges from OrderService, a faulty product price can be
 * traced all the way to the invoices built on it.
 */
@Service
public class BillingService {

    private final InvoiceRepository invoices;
    private final OrderService orders;
    private final DocumentNumbers numbers;
    private final ChangeRecorder changes;
    private final ProvenanceRecorder provenance;

    BillingService(InvoiceRepository invoices, OrderService orders, DocumentNumbers numbers,
                   ChangeRecorder changes, ProvenanceRecorder provenance) {
        this.invoices = invoices;
        this.orders = orders;
        this.numbers = numbers;
        this.changes = changes;
        this.provenance = provenance;
    }

    @Transactional
    Invoice createFromOrder(TenantContext tenant, UUID orderId) {
        tenant.require(Permissions.INVOICE_WRITE);
        UUID org = tenant.organizationId();
        Order order = orders.lockConfirmedForInvoicing(tenant, orderId);
        if (invoices.activeExistsForOrder(org, orderId)) {
            throw ApiException.conflict("Order " + order.orderNumber() + " already has an active invoice.");
        }

        String number = numbers.next(org, DocumentKind.INVOICE);
        Invoice header = invoices.insert(org, number, orderId, Order.totalOf(order.lines()));
        List<InvoiceLine> lines = new ArrayList<>();
        for (OrderLine source : order.lines()) {
            InvoiceLine line = invoices.insertLine(org, header.id(), source.lineNo(), source.id(),
                    source.description(), source.quantity(), source.unitPrice());
            changes.recordInsert(org, tenant.actor(), InvoiceLine.ENTITY_TYPE, line.id(), line.snapshot(),
                    "invoiced from " + order.orderNumber() + " line " + source.lineNo());
            provenance.record(org, source.ref(), "unit_price", line.ref(), "unit_price",
                    ProvenanceRecorder.COPIED_INTO, ProvenanceRecorder.OP_INVOICE_FROM_ORDER);
            lines.add(line);
        }
        Invoice invoice = header.withLines(lines);
        changes.recordInsert(org, tenant.actor(), Invoice.ENTITY_TYPE, invoice.id(), invoice.snapshot(),
                "invoice created for " + order.orderNumber());
        for (InvoiceLine line : lines) {
            provenance.record(org, line.ref(), "line_total", invoice.ref(), "total_amount",
                    ProvenanceRecorder.AGGREGATED_INTO, ProvenanceRecorder.OP_INVOICE_FROM_ORDER);
        }
        return invoice;
    }

    @Transactional(readOnly = true)
    Invoice get(TenantContext tenant, UUID id) {
        tenant.require(Permissions.INVOICE_READ);
        return invoices.find(tenant.organizationId(), id).orElseThrow(() -> ApiException.notFound("Invoice"));
    }

    @Transactional(readOnly = true)
    PageResponse<Invoice> list(TenantContext tenant, String status, UUID orderId, PageQuery page) {
        tenant.require(Permissions.INVOICE_READ);
        return PageResponse.of(invoices.list(tenant.organizationId(), status, orderId, page), page,
                invoices.count(tenant.organizationId(), status, orderId));
    }

    @Transactional
    Invoice issue(TenantContext tenant, UUID id, long expectedVersion) {
        tenant.require(Permissions.INVOICE_WRITE);
        Invoice before = lockAtVersion(tenant, id, expectedVersion);
        before.requireIssuable();
        return transition(tenant, before, Invoice.ISSUED, expectedVersion, "invoice issued");
    }

    @Transactional
    Invoice voidInvoice(TenantContext tenant, UUID id, long expectedVersion, String reason) {
        tenant.require(Permissions.INVOICE_WRITE);
        Invoice before = lockAtVersion(tenant, id, expectedVersion);
        before.requireVoidable(invoices.credited(tenant.organizationId(), id).notes());
        return transition(tenant, before, Invoice.VOID, expectedVersion,
                reason == null || reason.isBlank() ? "invoice voided" : reason);
    }

    /** The invoice row lock serialises credit notes, so two credits cannot together exceed the total. */
    @Transactional
    CreditNote credit(TenantContext tenant, UUID invoiceId, BigDecimal amount, String reason) {
        tenant.require(Permissions.INVOICE_WRITE);
        UUID org = tenant.organizationId();
        Invoice invoice = invoices.findForUpdate(org, invoiceId).orElseThrow(() -> ApiException.notFound("Invoice"));
        invoice.requireCreditable(invoices.credited(org, invoiceId).amount(), amount);
        String number = numbers.next(org, DocumentKind.CREDIT_NOTE);
        CreditNote note = invoices.insertCreditNote(org, number, invoiceId, amount, reason.trim());
        changes.recordInsert(org, tenant.actor(), CreditNote.ENTITY_TYPE, note.id(), note.snapshot(),
                "credit against " + invoice.invoiceNumber());
        return note;
    }

    @Transactional(readOnly = true)
    List<CreditNote> creditNotes(TenantContext tenant, UUID invoiceId) {
        tenant.require(Permissions.INVOICE_READ);
        get(tenant, invoiceId);   // 404 for another tenant's invoice
        return invoices.creditNotes(tenant.organizationId(), invoiceId);
    }

    private Invoice lockAtVersion(TenantContext tenant, UUID id, long expectedVersion) {
        Invoice invoice = invoices.findForUpdate(tenant.organizationId(), id)
                .orElseThrow(() -> ApiException.notFound("Invoice"));
        if (invoice.version() != expectedVersion) {
            throw ApiException.conflict("Invoice " + invoice.invoiceNumber() + " was changed by someone else (now at version "
                    + invoice.version() + "). Reload it and try again.");
        }
        return invoice;
    }

    private Invoice transition(TenantContext tenant, Invoice before, String status, long expectedVersion, String reason) {
        Invoice after = invoices.setStatus(tenant.organizationId(), before.id(), status, expectedVersion)
                .orElseThrow(() -> new IllegalStateException("Locked invoice row disappeared during update"));
        changes.recordUpdate(tenant.organizationId(), tenant.actor(), Invoice.ENTITY_TYPE, after.id(), after.version(),
                before.snapshot(), after.snapshot(), reason);
        return after;
    }
}
