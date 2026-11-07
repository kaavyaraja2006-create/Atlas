package com.atlas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * The order-to-invoice flow, including the provenance it records. The
 * scenario mirrors the recovery story: a product price is mistyped
 * (5000 → 500), an order is priced from the faulty version and invoiced.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class OrdersAndInvoicesIT {

    @LocalServerPort
    int port;

    private TestApi.User admin;
    private UUID org;
    private String customer;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        admin = TestApi.newUser("ops");
        org = TestApi.createOrganization(admin, "shop");
        customer = admin.in(org).body(Map.of("name", "Acme Traders"))
                .post("/api/v1/customers").then().statusCode(201).extract().path("id");
    }

    private String product(String sku, String price) {
        return admin.in(org).body(Map.of("sku", sku, "name", "Item " + sku, "unitPrice", price, "unitCost", "1"))
                .post("/api/v1/products").then().statusCode(201).extract().path("id");
    }

    private Response newOrder() {
        return admin.in(org).body(Map.of("customerId", customer)).post("/api/v1/orders");
    }

    private Response addLine(String orderId, String productId, int qty) {
        return admin.in(org).body(Map.of("productId", productId, "quantity", qty))
                .post("/api/v1/orders/" + orderId + "/lines");
    }

    private String confirmedOrder(String productId) {
        String id = newOrder().then().statusCode(201).extract().path("id");
        String etag = addLine(id, productId, 1).then().statusCode(200).extract().header("ETag");
        admin.in(org).header("If-Match", etag).post("/api/v1/orders/" + id + "/confirm").then().statusCode(200);
        return id;
    }

    @Test
    void faultyPriceFlowsIntoOrderAndInvoiceAndProvenanceShowsExactlyHow() {
        String widget = product("P100", "5000");
        String tee = product("T-100", "900");
        admin.in(org).header("If-Match", "\"0\"").body(Map.of("unitPrice", "500", "reason", "typo"))
                .patch("/api/v1/products/" + widget).then().statusCode(200).header("ETag", "\"1\"");

        Response created = newOrder();
        created.then().statusCode(201).body("orderNumber", equalTo("SO-000001")).body("status", equalTo("DRAFT"));
        String orderId = created.path("id");

        addLine(orderId, widget, 3).then().statusCode(200).body("totalAmount", equalTo(1500.0f));
        Response two = addLine(orderId, tee, 2);
        two.then().statusCode(200).body("totalAmount", equalTo(3300.0f)).body("lines", hasSize(2))
                .body("lines[0].description", equalTo("P100 - Item P100"));
        String widgetLine = two.path("lines[0].id");
        String teeLine = two.path("lines[1].id");

        admin.in(org).post("/api/v1/orders/" + orderId + "/confirm").then().statusCode(428);
        admin.in(org).header("If-Match", two.header("ETag")).post("/api/v1/orders/" + orderId + "/confirm")
                .then().statusCode(200).body("status", equalTo("CONFIRMED"));
        addLine(orderId, tee, 1).then().statusCode(422);

        Response invoice = admin.in(org).header("Idempotency-Key", "invoice-" + orderId)
                .post("/api/v1/orders/" + orderId + "/invoice");
        invoice.then().statusCode(201).body("invoiceNumber", equalTo("INV-000001"))
                .body("totalAmount", equalTo(3300.0f)).body("lines", hasSize(2));
        String invoiceId = invoice.path("id");

        // Provenance: the faulty product version v1 fed exactly the widget line...
        List<Map<String, Object>> fromFaulty = admin.in(org)
                .get("/api/v1/provenance/product/" + widget + "?version=1").then().statusCode(200)
                .extract().path("$");
        assertThat(fromFaulty).hasSize(1);
        assertThat(fromFaulty.get(0)).containsEntry("relation", "DERIVED_VALUE")
                .containsEntry("evidence", "RECORDED_AT_WRITE");
        assertThat(((Map<?, ?>) fromFaulty.get(0).get("target")).get("entityId")).isEqualTo(widgetLine);
        // ...the correct version v0 fed nothing...
        admin.in(org).get("/api/v1/provenance/product/" + widget + "?version=0").then().body("$", hasSize(0));
        // ...each order line was copied into an invoice line, and the invoice total aggregates both.
        admin.in(org).get("/api/v1/provenance/order_line/" + widgetLine).then()
                .body("findAll { it.relation == 'COPIED_INTO' }", hasSize(1));
        admin.in(org).get("/api/v1/provenance/order_line/" + teeLine).then()
                .body("findAll { it.relation == 'COPIED_INTO' }", hasSize(1));
        admin.in(org).get("/api/v1/provenance/invoice/" + invoiceId + "?direction=upstream").then()
                .body("$", hasSize(2)).body("relation", equalTo(List.of("AGGREGATED_INTO", "AGGREGATED_INTO")));

        admin.in(org).post("/api/v1/audit/verify").then().body("intact", equalTo(true));
    }

    @Test
    void idempotencyKeyReplaysInsteadOfCreatingTwice() {
        Response first = admin.in(org).header("Idempotency-Key", "create-order-0001")
                .body(Map.of("customerId", customer)).post("/api/v1/orders");
        first.then().statusCode(201);
        Response retry = admin.in(org).header("Idempotency-Key", "create-order-0001")
                .body(Map.of("customerId", customer)).post("/api/v1/orders");
        retry.then().statusCode(201).header("Idempotent-Replayed", "true")
                .body("id", equalTo(first.path("id"))).body("orderNumber", equalTo("SO-000001"));

        String otherCustomer = admin.in(org).body(Map.of("name", "Other")).post("/api/v1/customers").path("id");
        admin.in(org).header("Idempotency-Key", "create-order-0001")
                .body(Map.of("customerId", otherCustomer)).post("/api/v1/orders").then().statusCode(422);
        admin.in(org).header("Idempotency-Key", "short").body(Map.of("customerId", customer))
                .post("/api/v1/orders").then().statusCode(400);

        admin.in(org).get("/api/v1/orders").then().body("page.totalItems", equalTo(1));
    }

    @Test
    void invoiceLifecycleWithCreditNotesAndDraftDiscard() {
        String widget = product("P100", "5000");
        String orderId = confirmedOrder(widget);
        Response inv = admin.in(org).post("/api/v1/orders/" + orderId + "/invoice");
        String invoiceId = inv.path("id");
        String issuedEtag = admin.in(org).header("If-Match", "\"0\"").post("/api/v1/invoices/" + invoiceId + "/issue")
                .then().statusCode(200).body("status", equalTo("ISSUED")).extract().header("ETag");

        admin.in(org).body(Map.of("amount", "4000", "reason", "discount")).post("/api/v1/invoices/" + invoiceId + "/credit-notes")
                .then().statusCode(201).body("creditNoteNumber", equalTo("CN-000001"));
        admin.in(org).body(Map.of("amount", "1000.0001", "reason", "too much"))
                .post("/api/v1/invoices/" + invoiceId + "/credit-notes").then().statusCode(422);
        admin.in(org).body(Map.of("amount", "1000", "reason", "rest"))
                .post("/api/v1/invoices/" + invoiceId + "/credit-notes").then().statusCode(201);
        admin.in(org).get("/api/v1/invoices/" + invoiceId + "/credit-notes").then().body("$", hasSize(2));
        admin.in(org).header("If-Match", issuedEtag).post("/api/v1/invoices/" + invoiceId + "/void")
                .then().statusCode(422);

        // A mistaken draft can be discarded and the order invoiced again.
        String second = confirmedOrder(widget);
        String draft = admin.in(org).post("/api/v1/orders/" + second + "/invoice").then().statusCode(201)
                .body("invoiceNumber", equalTo("INV-000002")).extract().path("id");
        admin.in(org).post("/api/v1/orders/" + second + "/invoice").then().statusCode(409);
        admin.in(org).header("If-Match", "\"0\"").post("/api/v1/invoices/" + draft + "/void")
                .then().statusCode(200).body("status", equalTo("VOID"));
        admin.in(org).post("/api/v1/orders/" + second + "/invoice").then().statusCode(201)
                .body("invoiceNumber", equalTo("INV-000003"));
    }

    @Test
    void businessRulesAndTenantBoundaries() {
        String widget = product("P100", "5000");
        String draftOrder = newOrder().path("id");
        admin.in(org).post("/api/v1/orders/" + draftOrder + "/invoice").then().statusCode(422);
        admin.in(org).header("If-Match", "\"0\"").post("/api/v1/orders/" + draftOrder + "/confirm").then().statusCode(422);
        addLine(draftOrder, UUID.randomUUID().toString(), 1).then().statusCode(422);
        addLine(draftOrder, widget, 0).then().statusCode(400);

        admin.in(org).header("If-Match", "\"0\"").body(Map.of("active", false))
                .patch("/api/v1/products/" + widget).then().statusCode(200);
        addLine(draftOrder, widget, 1).then().statusCode(422);

        TestApi.User other = TestApi.newUser("rival");
        UUID otherOrg = TestApi.createOrganization(other, "rival");
        String foreignProduct = other.in(otherOrg).body(Map.of("sku", "R1", "name", "r", "unitPrice", "1", "unitCost", "1"))
                .post("/api/v1/products").path("id");
        addLine(draftOrder, foreignProduct, 1).then().statusCode(422);
        other.in(otherOrg).get("/api/v1/orders/" + draftOrder).then().statusCode(404);
        other.in(otherOrg).body(Map.of("customerId", customer)).post("/api/v1/orders").then().statusCode(422);

        TestApi.User sales = TestApi.newUser("sales");
        admin.in(org).body(Map.of("email", sales.email(), "roles", Set.of("SALES_STAFF")))
                .post("/api/v1/members").then().statusCode(201);
        sales.in(org).body(Map.of("customerId", customer)).post("/api/v1/orders").then().statusCode(201);
        sales.in(org).post("/api/v1/orders/" + draftOrder + "/invoice").then().statusCode(403);
    }

    @Test
    void twoSimultaneousInvoiceRequestsForOneOrderProduceOneInvoice() throws Exception {
        String orderId = confirmedOrder(product("P100", "5000"));
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> invoiceIt = () -> {
            start.await();
            return admin.in(org).post("/api/v1/orders/" + orderId + "/invoice").statusCode();
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = pool.submit(invoiceIt);
            Future<Integer> b = pool.submit(invoiceIt);
            start.countDown();
            assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(201, 409);
        } finally {
            pool.shutdownNow();
        }
        admin.in(org).get("/api/v1/invoices?orderId=" + orderId).then().body("page.totalItems", equalTo(1));
    }
}
