package com.atlas.sales;

import com.atlas.common.tenancy.TenantContext;
import com.atlas.common.web.ETags;
import com.atlas.common.web.PageQuery;
import com.atlas.common.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
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
@RequestMapping("/api/v1/customers")
class CustomerController {

    private static final Map<String, String> SORTS = Map.of("name", "name", "createdAt", "created_at");

    record CreateCustomerRequest(@NotBlank @Size(max = 200) String name, @Email @Size(max = 320) String email) {
    }

    record UpdateCustomerRequest(@Size(min = 1, max = 200) String name, @Email @Size(max = 320) String email) {
    }

    private final CustomerService service;

    CustomerController(CustomerService service) {
        this.service = service;
    }

    @GetMapping
    PageResponse<Customer> list(TenantContext tenant, @RequestParam(required = false) String q,
                                @RequestParam(required = false) Integer page,
                                @RequestParam(required = false) Integer size,
                                @RequestParam(required = false) String sort) {
        return service.list(tenant, q, PageQuery.of(page, size, sort, SORTS, "name"));
    }

    @GetMapping("/{id}")
    ResponseEntity<Customer> get(TenantContext tenant, @PathVariable UUID id) {
        Customer customer = service.get(tenant, id);
        return ResponseEntity.ok().eTag(ETags.of(customer.version())).body(customer);
    }

    @PostMapping
    ResponseEntity<Customer> create(TenantContext tenant, @Valid @RequestBody CreateCustomerRequest request) {
        Customer customer = service.create(tenant, request.name(), request.email());
        return ResponseEntity.created(URI.create("/api/v1/customers/" + customer.id()))
                .eTag(ETags.of(customer.version())).body(customer);
    }

    @PatchMapping("/{id}")
    ResponseEntity<Customer> update(TenantContext tenant, @PathVariable UUID id,
                                    @RequestHeader(name = "If-Match", required = false) String ifMatch,
                                    @Valid @RequestBody UpdateCustomerRequest request) {
        Customer customer = service.update(tenant, id, ETags.requireVersion(ifMatch), request.name(), request.email());
        return ResponseEntity.ok().eTag(ETags.of(customer.version())).body(customer);
    }
}
