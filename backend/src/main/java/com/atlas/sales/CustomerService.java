package com.atlas.sales;

import com.atlas.audit.ChangeDiff;
import com.atlas.audit.ChangeRecorder;
import com.atlas.common.error.ApiException;
import com.atlas.common.tenancy.Permissions;
import com.atlas.common.tenancy.TenantContext;
import com.atlas.common.web.PageQuery;
import com.atlas.common.web.PageResponse;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CustomerService {

    static final String ENTITY_TYPE = "customer";

    private final CustomerRepository customers;
    private final ChangeRecorder changes;

    CustomerService(CustomerRepository customers, ChangeRecorder changes) {
        this.customers = customers;
        this.changes = changes;
    }

    @Transactional
    Customer create(TenantContext tenant, String name, String email) {
        tenant.require(Permissions.CUSTOMER_WRITE);
        Customer created = customers.insert(tenant.organizationId(), name.trim(),
                email == null || email.isBlank() ? null : email.trim());
        changes.recordInsert(tenant.organizationId(), tenant.actor(), ENTITY_TYPE, created.id(),
                created.snapshot(), "customer created");
        return created;
    }

    @Transactional(readOnly = true)
    Customer get(TenantContext tenant, UUID id) {
        tenant.require(Permissions.CUSTOMER_READ);
        return customers.find(tenant.organizationId(), id).orElseThrow(() -> ApiException.notFound("Customer"));
    }

    @Transactional(readOnly = true)
    PageResponse<Customer> list(TenantContext tenant, String search, PageQuery page) {
        tenant.require(Permissions.CUSTOMER_READ);
        return PageResponse.of(customers.list(tenant.organizationId(), search, page), page,
                customers.count(tenant.organizationId(), search));
    }

    @Transactional
    Customer update(TenantContext tenant, UUID id, long expectedVersion, String name, String email) {
        tenant.require(Permissions.CUSTOMER_WRITE);
        Customer current = customers.findForUpdate(tenant.organizationId(), id)
                .orElseThrow(() -> ApiException.notFound("Customer"));
        if (current.version() != expectedVersion) {
            throw ApiException.conflict("The customer was changed by someone else (now at version "
                    + current.version() + "). Reload it and apply your change again.");
        }
        Customer proposed = current.withChanges(name, email);
        if (ChangeDiff.changedFields(current.snapshot(), proposed.snapshot()).isEmpty()) {
            return current;
        }
        Customer saved = customers.update(proposed, expectedVersion)
                .orElseThrow(() -> new IllegalStateException("Locked customer row disappeared during update"));
        changes.recordUpdate(tenant.organizationId(), tenant.actor(), ENTITY_TYPE, saved.id(), saved.version(),
                current.snapshot(), saved.snapshot(), "customer updated");
        return saved;
    }
}
