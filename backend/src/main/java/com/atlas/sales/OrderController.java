package com.atlas.sales;

import com.atlas.common.idempotency.Idempotency;
import com.atlas.common.idempotency.IdempotencyKeys;
import com.atlas.common.tenancy.TenantContext;
import com.atlas.common.web.ETags;
import com.atlas.common.web.PageQuery;
import com.atlas.common.web.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
class OrderController {

    private static final Map<String, String> SORTS = Map.of(
            "orderNumber", "order_number", "createdAt", "created_at", "totalAmount", "total_amount");

    record CreateOrderRequest(@NotNull UUID customerId) {
    }

    record UpdateOrderRequest(@Size(max = 500) String deliveryNote) {
    }

    record AddLineRequest(@NotNull UUID productId, @NotNull @Min(1) @Max(1_000_000) Integer quantity) {
    }

    private final OrderService service;
    private final Idempotency idempotency;

    OrderController(OrderService service, Idempotency idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    @PostMapping
    ResponseEntity<Object> create(TenantContext tenant, @Valid @RequestBody CreateOrderRequest request,
                                  @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String key,
                                  HttpServletRequest http) {
        return idempotency.execute(tenant, key, "POST", http.getRequestURI(), request, () -> {
            Order order = service.create(tenant, request.customerId());
            return ResponseEntity.created(URI.create("/api/v1/orders/" + order.id()))
                    .eTag(ETags.of(order.version())).body(order);
        });
    }

    @GetMapping
    PageResponse<Order> list(TenantContext tenant, @RequestParam(required = false) String status,
                             @RequestParam(required = false) UUID customerId,
                             @RequestParam(required = false) Integer page,
                             @RequestParam(required = false) Integer size,
                             @RequestParam(required = false) String sort) {
        return service.list(tenant, status, customerId, PageQuery.of(page, size, sort, SORTS, "createdAt,desc"));
    }

    @GetMapping("/{id}")
    ResponseEntity<Order> get(TenantContext tenant, @PathVariable UUID id) {
        Order order = service.get(tenant, id);
        return ResponseEntity.ok().eTag(ETags.of(order.version())).body(order);
    }

    /** Appending a line does not need If-Match: lines are serialised by the order row lock. */
    @PostMapping("/{id}/lines")
    ResponseEntity<Order> addLine(TenantContext tenant, @PathVariable UUID id,
                                  @Valid @RequestBody AddLineRequest request) {
        Order order = service.addLine(tenant, id, request.productId(), request.quantity());
        return ResponseEntity.ok().eTag(ETags.of(order.version())).body(order);
    }

    /** ATLAS X: edit the delivery note (the only person-editable field after confirmation). */
    @PatchMapping("/{id}")
    ResponseEntity<Order> update(TenantContext tenant, @PathVariable UUID id,
                                 @RequestHeader(name = "If-Match", required = false) String ifMatch,
                                 @Valid @RequestBody UpdateOrderRequest request) {
        Order order = service.updateDeliveryNote(tenant, id, ETags.requireVersion(ifMatch), request.deliveryNote());
        return ResponseEntity.ok().eTag(ETags.of(order.version())).body(order);
    }

    @PostMapping("/{id}/confirm")
    ResponseEntity<Order> confirm(TenantContext tenant, @PathVariable UUID id,
                                  @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        Order order = service.confirm(tenant, id, ETags.requireVersion(ifMatch));
        return ResponseEntity.ok().eTag(ETags.of(order.version())).body(order);
    }

    @PostMapping("/{id}/cancel")
    ResponseEntity<Order> cancel(TenantContext tenant, @PathVariable UUID id,
                                 @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        Order order = service.cancel(tenant, id, ETags.requireVersion(ifMatch));
        return ResponseEntity.ok().eTag(ETags.of(order.version())).body(order);
    }
}
