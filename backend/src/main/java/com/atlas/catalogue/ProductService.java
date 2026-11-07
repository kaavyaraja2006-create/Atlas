package com.atlas.catalogue;

import com.atlas.audit.ChangeDiff;
import com.atlas.audit.ChangeRecorder;
import com.atlas.common.error.ApiException;
import com.atlas.common.tenancy.Permissions;
import com.atlas.common.tenancy.TenantContext;
import com.atlas.common.web.PageQuery;
import com.atlas.common.web.PageResponse;
import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every product version (v0 on create, +1 per real change) gets exactly one
 * change event with a full snapshot. Phase 2b's order pricing records which
 * of these versions each order line consumed, which is what lets recovery
 * find every line priced from a faulty version.
 */
@Service
public class ProductService {

    public static final String ENTITY_TYPE = "product";

    private final ProductRepository products;
    private final ChangeRecorder changes;

    ProductService(ProductRepository products, ChangeRecorder changes) {
        this.products = products;
        this.changes = changes;
    }

    @Transactional
    Product create(TenantContext tenant, String sku, String name, BigDecimal unitPrice, BigDecimal unitCost,
                   Boolean active) {
        tenant.require(Permissions.CATALOGUE_WRITE);
        Product created;
        try {
            created = products.insert(tenant.organizationId(), sku.trim(), name.trim(), unitPrice, unitCost,
                    active == null || active);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("A product with SKU '" + sku.trim() + "' already exists.");
        }
        changes.recordInsert(tenant.organizationId(), tenant.actor(), ENTITY_TYPE, created.id(),
                created.snapshot(), "product created");
        return created;
    }

    @Transactional(readOnly = true)
    public Product get(TenantContext tenant, UUID id) {
        tenant.require(Permissions.CATALOGUE_READ);
        return products.find(tenant.organizationId(), id).orElseThrow(() -> ApiException.notFound("Product"));
    }

    @Transactional(readOnly = true)
    PageResponse<Product> list(TenantContext tenant, String search, Boolean active, PageQuery page) {
        tenant.require(Permissions.CATALOGUE_READ);
        return PageResponse.of(
                products.list(tenant.organizationId(), search, active, page),
                page,
                products.count(tenant.organizationId(), search, active));
    }

    /**
     * Optimistic concurrency: the client says which version it edited. If
     * someone else changed the product since, the update is refused with
     * 409 rather than overwriting their work.
     */
    @Transactional
    Product update(TenantContext tenant, UUID id, long expectedVersion, String name, BigDecimal unitPrice,
                   BigDecimal unitCost, Boolean active, String reason) {
        tenant.require(Permissions.CATALOGUE_WRITE);
        Product current = products.findForUpdate(tenant.organizationId(), id)
                .orElseThrow(() -> ApiException.notFound("Product"));
        if (current.version() != expectedVersion) {
            throw ApiException.conflict("The product was changed by someone else (now at version "
                    + current.version() + "). Reload it and apply your change again.");
        }
        Product proposed = current.withChanges(name, unitPrice, unitCost, active);
        if (ChangeDiff.changedFields(current.snapshot(), proposed.snapshot()).isEmpty()) {
            return current;   // no-op: no new version, no history noise
        }
        Product saved = products.update(proposed, expectedVersion)
                .orElseThrow(() -> new IllegalStateException("Locked product row disappeared during update"));
        changes.recordUpdate(tenant.organizationId(), tenant.actor(), ENTITY_TYPE, saved.id(), saved.version(),
                current.snapshot(), saved.snapshot(), reason);
        return saved;
    }
}
