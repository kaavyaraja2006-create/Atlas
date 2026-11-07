package com.atlas.sales;

import com.atlas.common.db.Db;
import com.atlas.common.web.PageQuery;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class OrderRepository {

    static final String INSERT_ORDER = """
            INSERT INTO orders (organization_id, order_number, customer_id, created_by)
            VALUES (:orgId, :orderNumber, :customerId, :createdBy)
            RETURNING id, organization_id, order_number, customer_id, status, total_amount, created_by,
                      placed_at, created_at, updated_at, version, delivery_note
            """;

    static final String FIND_ORDER = """
            SELECT id, organization_id, order_number, customer_id, status, total_amount, created_by,
                   placed_at, created_at, updated_at, version, delivery_note
              FROM orders
             WHERE organization_id = :orgId AND id = :id
            """;

    static final String FIND_ORDER_FOR_UPDATE = """
            SELECT id, organization_id, order_number, customer_id, status, total_amount, created_by,
                   placed_at, created_at, updated_at, version, delivery_note
              FROM orders
             WHERE organization_id = :orgId AND id = :id
               FOR UPDATE
            """;

    static final String LIST_ORDER_LINES = """
            SELECT id, order_id, line_no, product_id, description, quantity, unit_price, line_total, version
              FROM order_lines
             WHERE organization_id = :orgId AND order_id = :orderId
             ORDER BY line_no
            """;

    /** Called with the order row locked, so max(line_no) + 1 cannot race. */
    static final String INSERT_ORDER_LINE = """
            INSERT INTO order_lines (organization_id, order_id, line_no, product_id, description, quantity, unit_price)
            VALUES (:orgId, :orderId,
                    (SELECT COALESCE(max(line_no), 0) + 1 FROM order_lines
                      WHERE organization_id = :orgId AND order_id = :orderId),
                    :productId, :description, :quantity, :unitPrice)
            RETURNING id, order_id, line_no, product_id, description, quantity, unit_price, line_total, version
            """;

    /** The total is always recomputed from the lines in SQL, never incremented, so it cannot drift. */
    static final String RECOMPUTE_ORDER_TOTAL = """
            UPDATE orders
               SET total_amount = (SELECT COALESCE(sum(line_total), 0) FROM order_lines
                                    WHERE organization_id = :orgId AND order_id = :id),
                   updated_at = now(), version = version + 1
             WHERE organization_id = :orgId AND id = :id
            RETURNING id, organization_id, order_number, customer_id, status, total_amount, created_by,
                      placed_at, created_at, updated_at, version, delivery_note
            """;

    static final String SET_ORDER_STATUS = """
            UPDATE orders
               SET status = CAST(:status AS varchar),
                   placed_at = CASE WHEN CAST(:status AS varchar) = 'CONFIRMED' THEN now() ELSE placed_at END,
                   updated_at = now(), version = version + 1
             WHERE organization_id = :orgId AND id = :id AND version = :expectedVersion
            RETURNING id, organization_id, order_number, customer_id, status, total_amount, created_by,
                      placed_at, created_at, updated_at, version, delivery_note
            """;

    /** ATLAS X: a field people edit after confirmation, independent of prices and totals. */
    static final String SET_DELIVERY_NOTE = """
            UPDATE orders
               SET delivery_note = :deliveryNote, updated_at = now(), version = version + 1
             WHERE organization_id = :orgId AND id = :id AND version = :expectedVersion
            RETURNING id, organization_id, order_number, customer_id, status, total_amount, created_by,
                      placed_at, created_at, updated_at, version, delivery_note
            """;

    static final String LIST_ORDERS = """
            SELECT id, organization_id, order_number, customer_id, status, total_amount, created_by,
                   placed_at, created_at, updated_at, version, delivery_note
              FROM orders
             WHERE organization_id = :orgId
               AND (CAST(:status AS varchar) IS NULL OR status = CAST(:status AS varchar))
               AND (CAST(:customerId AS uuid) IS NULL OR customer_id = CAST(:customerId AS uuid))
             ORDER BY %s, id
             LIMIT :limit OFFSET :offset
            """;

    static final String COUNT_ORDERS = """
            SELECT count(*)
              FROM orders
             WHERE organization_id = :orgId
               AND (CAST(:status AS varchar) IS NULL OR status = CAST(:status AS varchar))
               AND (CAST(:customerId AS uuid) IS NULL OR customer_id = CAST(:customerId AS uuid))
            """;

    private final JdbcClient jdbc;

    OrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Order insert(UUID orgId, String orderNumber, UUID customerId, UUID createdBy) {
        return jdbc.sql(INSERT_ORDER).param("orgId", orgId).param("orderNumber", orderNumber)
                .param("customerId", customerId).param("createdBy", createdBy)
                .query(OrderRepository::mapHeader).single();
    }

    Optional<Order> find(UUID orgId, UUID id) {
        return jdbc.sql(FIND_ORDER).param("orgId", orgId).param("id", id)
                .query(OrderRepository::mapHeader).optional()
                .map(o -> o.withLines(lines(orgId, id)));
    }

    Optional<Order> findForUpdate(UUID orgId, UUID id) {
        return jdbc.sql(FIND_ORDER_FOR_UPDATE).param("orgId", orgId).param("id", id)
                .query(OrderRepository::mapHeader).optional()
                .map(o -> o.withLines(lines(orgId, id)));
    }

    List<OrderLine> lines(UUID orgId, UUID orderId) {
        return jdbc.sql(LIST_ORDER_LINES).param("orgId", orgId).param("orderId", orderId)
                .query(OrderRepository::mapLine).list();
    }

    OrderLine insertLine(UUID orgId, UUID orderId, UUID productId, String description, int quantity,
                         BigDecimal unitPrice) {
        return jdbc.sql(INSERT_ORDER_LINE).param("orgId", orgId).param("orderId", orderId)
                .param("productId", productId).param("description", description)
                .param("quantity", quantity).param("unitPrice", unitPrice)
                .query(OrderRepository::mapLine).single();
    }

    Order recomputeTotal(UUID orgId, UUID id) {
        Order header = jdbc.sql(RECOMPUTE_ORDER_TOTAL).param("orgId", orgId).param("id", id)
                .query(OrderRepository::mapHeader).single();
        return header.withLines(lines(orgId, id));
    }

    Optional<Order> setStatus(UUID orgId, UUID id, String status, long expectedVersion) {
        return jdbc.sql(SET_ORDER_STATUS).param("orgId", orgId).param("id", id).param("status", status)
                .param("expectedVersion", expectedVersion)
                .query(OrderRepository::mapHeader).optional()
                .map(o -> o.withLines(lines(orgId, id)));
    }

    Optional<Order> setDeliveryNote(UUID orgId, UUID id, String note, long expectedVersion) {
        return jdbc.sql(SET_DELIVERY_NOTE).param("orgId", orgId).param("id", id).param("deliveryNote", note)
                .param("expectedVersion", expectedVersion)
                .query(OrderRepository::mapHeader).optional()
                .map(o -> o.withLines(lines(orgId, id)));
    }

    /** List views carry headers only; fetch one order for its lines. */
    List<Order> list(UUID orgId, String status, UUID customerId, PageQuery page) {
        return jdbc.sql(LIST_ORDERS.formatted(page.orderBy()))
                .param("orgId", orgId).param("status", status).param("customerId", customerId)
                .param("limit", page.size()).param("offset", page.offset())
                .query(OrderRepository::mapHeader).list();
    }

    long count(UUID orgId, String status, UUID customerId) {
        return jdbc.sql(COUNT_ORDERS).param("orgId", orgId).param("status", status).param("customerId", customerId)
                .query(Long.class).single();
    }

    private static Order mapHeader(ResultSet rs, int n) throws SQLException {
        return new Order(rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class),
                rs.getString("order_number"), rs.getObject("customer_id", UUID.class), rs.getString("status"),
                rs.getBigDecimal("total_amount"), rs.getObject("created_by", UUID.class),
                Db.instant(rs, "placed_at"), Db.instant(rs, "created_at"), Db.instant(rs, "updated_at"),
                rs.getLong("version"), rs.getString("delivery_note"), List.of());
    }

    private static OrderLine mapLine(ResultSet rs, int n) throws SQLException {
        return new OrderLine(rs.getObject("id", UUID.class), rs.getObject("order_id", UUID.class),
                rs.getInt("line_no"), rs.getObject("product_id", UUID.class), rs.getString("description"),
                rs.getInt("quantity"), rs.getBigDecimal("unit_price"), rs.getBigDecimal("line_total"),
                rs.getLong("version"));
    }
}
