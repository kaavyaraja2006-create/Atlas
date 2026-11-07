package com.atlas.catalogue;

import com.atlas.common.db.Db;
import com.atlas.common.web.PageQuery;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class ProductRepository {

    static final String INSERT_PRODUCT = """
            INSERT INTO products (organization_id, sku, name, unit_price, unit_cost, active)
            VALUES (:orgId, :sku, :name, :unitPrice, :unitCost, :active)
            RETURNING id, organization_id, sku, name, unit_price, unit_cost, active, created_at, updated_at, version
            """;

    static final String FIND_PRODUCT = """
            SELECT id, organization_id, sku, name, unit_price, unit_cost, active, created_at, updated_at, version
              FROM products
             WHERE organization_id = :orgId AND id = :id
            """;

    /** Row lock: the before-snapshot and the version check see the same row the update changes. */
    static final String FIND_PRODUCT_FOR_UPDATE = """
            SELECT id, organization_id, sku, name, unit_price, unit_cost, active, created_at, updated_at, version
              FROM products
             WHERE organization_id = :orgId AND id = :id
               FOR UPDATE
            """;

    static final String UPDATE_PRODUCT = """
            UPDATE products
               SET name = :name, unit_price = :unitPrice, unit_cost = :unitCost, active = :active,
                   updated_at = now(), version = version + 1
             WHERE organization_id = :orgId AND id = :id AND version = :expectedVersion
            RETURNING id, organization_id, sku, name, unit_price, unit_cost, active, created_at, updated_at, version
            """;

    /** %s is replaced only by PageQuery.orderBy(), which comes from a whitelist. */
    static final String LIST_PRODUCTS = """
            SELECT id, organization_id, sku, name, unit_price, unit_cost, active, created_at, updated_at, version
              FROM products
             WHERE organization_id = :orgId
               AND (CAST(:pattern AS varchar) IS NULL
                    OR sku ILIKE CAST(:pattern AS varchar) ESCAPE '\\'
                    OR name ILIKE CAST(:pattern AS varchar) ESCAPE '\\')
               AND (CAST(:active AS boolean) IS NULL OR active = CAST(:active AS boolean))
             ORDER BY %s, id
             LIMIT :limit OFFSET :offset
            """;

    static final String COUNT_PRODUCTS = """
            SELECT count(*)
              FROM products
             WHERE organization_id = :orgId
               AND (CAST(:pattern AS varchar) IS NULL
                    OR sku ILIKE CAST(:pattern AS varchar) ESCAPE '\\'
                    OR name ILIKE CAST(:pattern AS varchar) ESCAPE '\\')
               AND (CAST(:active AS boolean) IS NULL OR active = CAST(:active AS boolean))
            """;

    private final JdbcClient jdbc;

    ProductRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Product insert(UUID orgId, String sku, String name, java.math.BigDecimal unitPrice,
                   java.math.BigDecimal unitCost, boolean active) {
        return jdbc.sql(INSERT_PRODUCT)
                .param("orgId", orgId).param("sku", sku).param("name", name)
                .param("unitPrice", unitPrice).param("unitCost", unitCost).param("active", active)
                .query(ProductRepository::map).single();
    }

    Optional<Product> find(UUID orgId, UUID id) {
        return jdbc.sql(FIND_PRODUCT).param("orgId", orgId).param("id", id)
                .query(ProductRepository::map).optional();
    }

    Optional<Product> findForUpdate(UUID orgId, UUID id) {
        return jdbc.sql(FIND_PRODUCT_FOR_UPDATE).param("orgId", orgId).param("id", id)
                .query(ProductRepository::map).optional();
    }

    Optional<Product> update(Product next, long expectedVersion) {
        return jdbc.sql(UPDATE_PRODUCT)
                .param("orgId", next.organizationId()).param("id", next.id())
                .param("name", next.name()).param("unitPrice", next.unitPrice())
                .param("unitCost", next.unitCost()).param("active", next.active())
                .param("expectedVersion", expectedVersion)
                .query(ProductRepository::map).optional();
    }

    List<Product> list(UUID orgId, String search, Boolean active, PageQuery page) {
        return jdbc.sql(LIST_PRODUCTS.formatted(page.orderBy()))
                .param("orgId", orgId)
                .param("pattern", search == null || search.isBlank() ? null : Db.containsPattern(search.trim()))
                .param("active", active)
                .param("limit", page.size())
                .param("offset", page.offset())
                .query(ProductRepository::map).list();
    }

    long count(UUID orgId, String search, Boolean active) {
        return jdbc.sql(COUNT_PRODUCTS)
                .param("orgId", orgId)
                .param("pattern", search == null || search.isBlank() ? null : Db.containsPattern(search.trim()))
                .param("active", active)
                .query(Long.class).single();
    }

    private static Product map(ResultSet rs, int rowNum) throws SQLException {
        return new Product(
                rs.getObject("id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getString("sku"),
                rs.getString("name"),
                rs.getBigDecimal("unit_price"),
                rs.getBigDecimal("unit_cost"),
                rs.getBoolean("active"),
                Db.instant(rs, "created_at"),
                Db.instant(rs, "updated_at"),
                rs.getLong("version"));
    }
}
