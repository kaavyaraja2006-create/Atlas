package com.atlas.catalogue;

import com.atlas.common.tenancy.TenantContext;
import com.atlas.common.web.ETags;
import com.atlas.common.web.PageQuery;
import com.atlas.common.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
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
@RequestMapping("/api/v1/products")
class ProductController {

    private static final Map<String, String> SORTS = Map.of(
            "sku", "sku", "name", "name", "unitPrice", "unit_price", "createdAt", "created_at");

    record CreateProductRequest(
            @NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9._-]+$",
                    message = "may contain letters, digits, '.', '_' and '-'") String sku,
            @NotBlank @Size(max = 200) String name,
            @NotNull @DecimalMin("0") @Digits(integer = 15, fraction = 4) BigDecimal unitPrice,
            @NotNull @DecimalMin("0") @Digits(integer = 15, fraction = 4) BigDecimal unitCost,
            Boolean active) {
    }

    record UpdateProductRequest(
            @Size(min = 1, max = 200) String name,
            @DecimalMin("0") @Digits(integer = 15, fraction = 4) BigDecimal unitPrice,
            @DecimalMin("0") @Digits(integer = 15, fraction = 4) BigDecimal unitCost,
            Boolean active,
            @Size(max = 500) String reason) {
    }

    private final ProductService service;

    ProductController(ProductService service) {
        this.service = service;
    }

    @GetMapping
    PageResponse<Product> list(TenantContext tenant,
                               @RequestParam(required = false) String q,
                               @RequestParam(required = false) Boolean active,
                               @RequestParam(required = false) Integer page,
                               @RequestParam(required = false) Integer size,
                               @RequestParam(required = false) String sort) {
        return service.list(tenant, q, active, PageQuery.of(page, size, sort, SORTS, "createdAt,desc"));
    }

    @GetMapping("/{id}")
    ResponseEntity<Product> get(TenantContext tenant, @PathVariable UUID id) {
        Product product = service.get(tenant, id);
        return ResponseEntity.ok().eTag(ETags.of(product.version())).body(product);
    }

    @PostMapping
    ResponseEntity<Product> create(TenantContext tenant, @Valid @RequestBody CreateProductRequest request) {
        Product product = service.create(tenant, request.sku(), request.name(), request.unitPrice(),
                request.unitCost(), request.active());
        return ResponseEntity.created(URI.create("/api/v1/products/" + product.id()))
                .eTag(ETags.of(product.version()))
                .body(product);
    }

    @PatchMapping("/{id}")
    ResponseEntity<Product> update(TenantContext tenant, @PathVariable UUID id,
                                   @RequestHeader(name = "If-Match", required = false) String ifMatch,
                                   @Valid @RequestBody UpdateProductRequest request) {
        long expected = ETags.requireVersion(ifMatch);
        Product product = service.update(tenant, id, expected, request.name(), request.unitPrice(),
                request.unitCost(), request.active(), request.reason());
        return ResponseEntity.ok().eTag(ETags.of(product.version())).body(product);
    }
}
