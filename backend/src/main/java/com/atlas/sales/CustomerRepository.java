package com.atlas.sales;

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
class CustomerRepository {

    static final String INSERT_CUSTOMER = """
            INSERT INTO customers (organization_id, name, email)
            VALUES (:orgId, :name, :email)
            RETURNING id, organization_id, name, email, created_at, updated_at, version
            """;

    static final String FIND_CUSTOMER = """
            SELECT id, organization_id, name, email, created_at, updated_at, version
              FROM customers
             WHERE organization_id = :orgId AND id = :id
            """;

    static final String FIND_CUSTOMER_FOR_UPDATE = """
            SELECT id, organization_id, name, email, created_at, updated_at, version
              FROM customers
             WHERE organization_id = :orgId AND id = :id
               FOR UPDATE
            """;

    static final String UPDATE_CUSTOMER = """
            UPDATE customers
               SET name = :name, email = :email, updated_at = now(), version = version + 1
             WHERE organization_id = :orgId AND id = :id AND version = :expectedVersion
            RETURNING id, organization_id, name, email, created_at, updated_at, version
            """;

    static final String LIST_CUSTOMERS = """
            SELECT id, organization_id, name, email, created_at, updated_at, version
              FROM customers
             WHERE organization_id = :orgId
               AND (CAST(:pattern AS varchar) IS NULL
                    OR name ILIKE CAST(:pattern AS varchar) ESCAPE '\\'
                    OR email ILIKE CAST(:pattern AS varchar) ESCAPE '\\')
             ORDER BY %s, id
             LIMIT :limit OFFSET :offset
            """;

    static final String COUNT_CUSTOMERS = """
            SELECT count(*)
              FROM customers
             WHERE organization_id = :orgId
               AND (CAST(:pattern AS varchar) IS NULL
                    OR name ILIKE CAST(:pattern AS varchar) ESCAPE '\\'
                    OR email ILIKE CAST(:pattern AS varchar) ESCAPE '\\')
            """;

    private final JdbcClient jdbc;

    CustomerRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Customer insert(UUID orgId, String name, String email) {
        return jdbc.sql(INSERT_CUSTOMER).param("orgId", orgId).param("name", name).param("email", email)
                .query(CustomerRepository::map).single();
    }

    Optional<Customer> find(UUID orgId, UUID id) {
        return jdbc.sql(FIND_CUSTOMER).param("orgId", orgId).param("id", id).query(CustomerRepository::map).optional();
    }

    Optional<Customer> findForUpdate(UUID orgId, UUID id) {
        return jdbc.sql(FIND_CUSTOMER_FOR_UPDATE).param("orgId", orgId).param("id", id)
                .query(CustomerRepository::map).optional();
    }

    Optional<Customer> update(Customer next, long expectedVersion) {
        return jdbc.sql(UPDATE_CUSTOMER)
                .param("orgId", next.organizationId()).param("id", next.id())
                .param("name", next.name()).param("email", next.email())
                .param("expectedVersion", expectedVersion)
                .query(CustomerRepository::map).optional();
    }

    List<Customer> list(UUID orgId, String search, PageQuery page) {
        return jdbc.sql(LIST_CUSTOMERS.formatted(page.orderBy()))
                .param("orgId", orgId).param("pattern", pattern(search))
                .param("limit", page.size()).param("offset", page.offset())
                .query(CustomerRepository::map).list();
    }

    long count(UUID orgId, String search) {
        return jdbc.sql(COUNT_CUSTOMERS).param("orgId", orgId).param("pattern", pattern(search))
                .query(Long.class).single();
    }

    private static String pattern(String search) {
        return search == null || search.isBlank() ? null : Db.containsPattern(search.trim());
    }

    private static Customer map(ResultSet rs, int n) throws SQLException {
        return new Customer(rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class),
                rs.getString("name"), rs.getString("email"), Db.instant(rs, "created_at"),
                Db.instant(rs, "updated_at"), rs.getLong("version"));
    }
}
