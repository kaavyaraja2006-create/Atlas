package com.atlas.billing;

import com.atlas.common.idempotency.Idempotency;
import com.atlas.common.idempotency.IdempotencyKeys;
import com.atlas.common.tenancy.TenantContext;
import com.atlas.common.web.ETags;
import com.atlas.common.web.PageQuery;
import com.atlas.common.web.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
class InvoiceController {

    private static final Map<String, String> SORTS = Map.of(
            "invoiceNumber", "invoice_number", "createdAt", "created_at", "totalAmount", "total_amount");

    record VoidRequest(@Size(max = 500) String reason) {
    }

    record CreditRequest(@NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 15, fraction = 4)
                         BigDecimal amount,
                         @NotBlank @Size(max = 500) String reason) {
    }

    private final BillingService service;
    private final Idempotency idempotency;

    InvoiceController(BillingService service, Idempotency idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    @PostMapping("/orders/{orderId}/invoice")
    ResponseEntity<Object> createFromOrder(TenantContext tenant, @PathVariable UUID orderId,
                                           @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String key,
                                           HttpServletRequest http) {
        return idempotency.execute(tenant, key, "POST", http.getRequestURI(), Map.of(), () -> {
            Invoice invoice = service.createFromOrder(tenant, orderId);
            return ResponseEntity.created(URI.create("/api/v1/invoices/" + invoice.id()))
                    .eTag(ETags.of(invoice.version())).body(invoice);
        });
    }

    @GetMapping("/invoices")
    PageResponse<Invoice> list(TenantContext tenant, @RequestParam(required = false) String status,
                               @RequestParam(required = false) UUID orderId,
                               @RequestParam(required = false) Integer page,
                               @RequestParam(required = false) Integer size,
                               @RequestParam(required = false) String sort) {
        return service.list(tenant, status, orderId, PageQuery.of(page, size, sort, SORTS, "createdAt,desc"));
    }

    @GetMapping("/invoices/{id}")
    ResponseEntity<Invoice> get(TenantContext tenant, @PathVariable UUID id) {
        Invoice invoice = service.get(tenant, id);
        return ResponseEntity.ok().eTag(ETags.of(invoice.version())).body(invoice);
    }

    @PostMapping("/invoices/{id}/issue")
    ResponseEntity<Invoice> issue(TenantContext tenant, @PathVariable UUID id,
                                  @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        Invoice invoice = service.issue(tenant, id, ETags.requireVersion(ifMatch));
        return ResponseEntity.ok().eTag(ETags.of(invoice.version())).body(invoice);
    }

    @PostMapping("/invoices/{id}/void")
    ResponseEntity<Invoice> voidInvoice(TenantContext tenant, @PathVariable UUID id,
                                        @RequestHeader(name = "If-Match", required = false) String ifMatch,
                                        @Valid @RequestBody(required = false) VoidRequest request) {
        Invoice invoice = service.voidInvoice(tenant, id, ETags.requireVersion(ifMatch),
                request == null ? null : request.reason());
        return ResponseEntity.ok().eTag(ETags.of(invoice.version())).body(invoice);
    }

    @GetMapping("/invoices/{id}/credit-notes")
    List<CreditNote> creditNotes(TenantContext tenant, @PathVariable UUID id) {
        return service.creditNotes(tenant, id);
    }

    @PostMapping("/invoices/{id}/credit-notes")
    ResponseEntity<CreditNote> credit(TenantContext tenant, @PathVariable UUID id,
                                      @Valid @RequestBody CreditRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.credit(tenant, id, request.amount(), request.reason()));
    }
}
