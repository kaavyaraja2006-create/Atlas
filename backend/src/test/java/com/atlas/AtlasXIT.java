package com.atlas;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.restassured.RestAssured;
import io.restassured.response.Response;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * ATLAS X end to end through HTTP: lineage with confidence, field history, conflicts and
 * their review gate, counterfactual simulation and assessment, integrity monitoring, and
 * tenant isolation of all of them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class AtlasXIT {

    @LocalServerPort
    int port;

    private TestApi.User admin;
    private UUID org;
    private String widget;
    private String order;
    private String line;
    private String incident;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        admin = TestApi.newUser("atlasx");
        org = TestApi.createOrganization(admin, "atlasx");
        String customer = admin.in(org).body(Map.of("name", "Acme (test)")).post("/api/v1/customers").path("id");
        widget = admin.in(org).body(Map.of("sku", "P100", "name", "Widget", "unitPrice", "5000", "unitCost", "1"))
                .post("/api/v1/products").path("id");
        admin.in(org).header("If-Match", "\"0\"").body(Map.of("unitPrice", "500", "reason", "typo"))
                .patch("/api/v1/products/" + widget).then().statusCode(200);
        order = admin.in(org).body(Map.of("customerId", customer)).post("/api/v1/orders").path("id");
        Response added = admin.in(org).body(Map.of("productId", widget, "quantity", 3)).post("/api/v1/orders/" + order + "/lines");
        line = added.path("lines[0].id");
        String faulty = admin.in(org).get("/api/v1/audit/events?entityType=product&entityId=" + widget)
                .path("items.find { it.entityVersion == 1 }.id");
        incident = admin.in(org).body(Map.of("changeEventId", faulty, "title", "Typo", "reason", "500 instead of 5000"))
                .post("/api/v1/recovery/incidents").path("id");
    }

    @Test
    void lineageCarriesConfidenceAndOperation() {
        Response r = admin.in(org).get("/api/v1/provenance/product/" + widget + "/lineage?version=1&depth=4");
        r.then().statusCode(200).body("direction", equalTo("downstream")).body("truncated", equalTo(false));
        List<Map<String, Object>> edges = r.path("edges");
        Map<String, Object> first = edges.get(0);
        assertEquals(1, first.get("depth"));
        assertEquals("VERIFIED", first.get("confidence"));
        assertEquals("order.add_line", first.get("operation"));
        assertTrue(edges.stream().anyMatch(e -> "total_amount".equals(e.get("targetField"))), "reaches the order total");
        admin.in(org).get("/api/v1/provenance/product/" + widget + "/lineage?depth=99").then().statusCode(400);
        admin.in(org).get("/api/v1/provenance/gaps").then().statusCode(200).body("size()", equalTo(0));
    }

    @Test
    void fieldHistoryAndDeliveryNote() {
        String etag = admin.in(org).get("/api/v1/orders/" + order).header("ETag");
        admin.in(org).header("If-Match", etag).body(Map.of("deliveryNote", "Leave at gate 2"))
                .patch("/api/v1/orders/" + order).then().statusCode(200).body("deliveryNote", equalTo("Leave at gate 2"));
        admin.in(org).header("If-Match", etag).body(Map.of("deliveryNote", "stale"))
                .patch("/api/v1/orders/" + order).then().statusCode(409);
        admin.in(org).get("/api/v1/history/order/" + order + "/fields?field=delivery_note").then().statusCode(200)
                .body("size()", equalTo(1)).body("[0].newValue", equalTo("\"Leave at gate 2\""))
                .body("[0].actorKind", equalTo("USER"));
        admin.in(org).get("/api/v1/history/order/" + order + "/timeline").then().statusCode(200)
                .body("operation", hasItem("INSERT")).body("size()", greaterThan(2));
    }

    @Test
    void conflictsAreComputedPerFieldAndReviewsAreValidated() {
        // No one touched the faulty data since: every field is NO_CONFLICT, nothing blocks execution.
        // (Same-field conflicts need an edit the API does not offer for line prices; the engine and SQL
        // tests cover them.)
        admin.in(org).post("/api/v1/recovery/incidents/" + incident + "/plans").then().statusCode(201);
        Response c = admin.in(org).get("/api/v1/recovery/incidents/" + incident + "/conflicts");
        c.then().statusCode(200).body("open", equalTo(0));
        List<String> kinds = c.path("conflicts.conflict.kind");
        assertTrue(kinds.stream().allMatch(k -> k.equals("NO_CONFLICT")), kinds.toString());

        admin.in(org).body(Map.of("entityType", "order_line", "entityId", line, "field", "unit_price",
                "decision", "KEEP_LATER_CHANGE", "note", "no conflict to review")).post(
                "/api/v1/recovery/incidents/" + incident + "/conflicts/reviews").then().statusCode(201);
        admin.in(org).body(Map.of("entityType", "order_line", "entityId", UUID.randomUUID().toString(),
                "field", "unit_price", "decision", "KEEP_LATER_CHANGE", "note", "no such conflict")).post(
                "/api/v1/recovery/incidents/" + incident + "/conflicts/reviews").then().statusCode(422);
    }

    @Test
    void simulationComparesStrategiesAndAssessesThePlan() {
        String plan = admin.in(org).post("/api/v1/recovery/incidents/" + incident + "/plans").path("id");
        Response sim = admin.in(org).post("/api/v1/recovery/incidents/" + incident + "/simulations");
        sim.then().statusCode(201).body("run.status", equalTo("COMPLETED"))
                .body("run.recommendedStrategy", equalTo("PROVENANCE_CONFLICT_AWARE"))
                .body("run.snapshotChainIndex", greaterThan(0))
                .body("results.size()", equalTo(6));
        Map<String, Object> f = sim.path("results.find { it.letter == 'F' }");
        assertEquals(f.get("faultyRecords"), f.get("repaired"));
        assertEquals(0, f.get("collateralModified"));
        assertEquals(0, f.get("invariantViolations"));
        admin.in(org).get("/api/v1/recovery/plans/" + plan + "/assessment").then().statusCode(200)
                .body("stale", equalTo(false)).body("simulation.riskLevel", notNullValue());

        // Record-level details (V15): exact totals, details complete for an incident this small.
        String runId = sim.path("run.id");
        admin.in(org).get("/api/v1/recovery/simulations/" + runId + "/strategies/PROVENANCE_CONFLICT_AWARE/records")
                .then().statusCode(200).body("partial", equalTo(false)).body("totals.truncated", equalTo(false))
                .body("totals.total", greaterThan(0)).body("items.action", hasItem("MODIFY"));
        admin.in(org).get("/api/v1/recovery/simulations/" + runId + "/strategies/PROVENANCE_CONFLICT_AWARE/records?category=NOPE")
                .then().statusCode(400);
        admin.in(org).get("/api/v1/recovery/simulations/" + runId + "/comparisons").then().statusCode(200)
                .body("size()", equalTo(15));
        admin.in(org).get("/api/v1/recovery/simulations/" + runId + "/comparisons/PROVENANCE_CONFLICT_AWARE/FULL_RESTORE")
                .then().statusCode(200).body("sha256", notNullValue()).body("differences", notNullValue());

        // The product changes: the old simulation no longer describes the plan.
        admin.in(org).header("If-Match", "\"1\"").body(Map.of("name", "Widget (renamed)"))
                .patch("/api/v1/products/" + widget).then().statusCode(200);
        String newPlan = admin.in(org).post("/api/v1/recovery/incidents/" + incident + "/plans").path("id");
        admin.in(org).get("/api/v1/recovery/plans/" + newPlan + "/assessment").then().statusCode(200)
                .body("stale", equalTo(true));
        admin.in(org).get("/api/v1/recovery/incidents/" + incident + "/simulations").then().statusCode(200)
                .body("size()", equalTo(1));
    }

    @Test
    void integrityMonitoringOpensOnceAndClears() {
        admin.in(org).get("/api/v1/integrity/rule-kinds").then().statusCode(200).body("size()", equalTo(5));
        String rule = admin.in(org).body(Map.of("kind", "REQUIRED_PROVENANCE")).post("/api/v1/integrity/rules")
                .then().statusCode(201).extract().path("id");
        admin.in(org).body(Map.of("kind", "REQUIRED_PROVENANCE")).post("/api/v1/integrity/rules").then().statusCode(409);
        admin.in(org).body(Map.of("kind", "ORDER_TOTAL_MATCHES_LINES")).post("/api/v1/integrity/rules").then().statusCode(201);
        admin.in(org).body(Map.of("kind", "AUDIT_CHAIN_INTACT")).post("/api/v1/integrity/rules").then().statusCode(201);
        admin.in(org).body(Map.of("kind", "NOT_A_KIND")).post("/api/v1/integrity/rules").then().statusCode(400);

        Response check = admin.in(org).post("/api/v1/integrity/checks");
        check.then().statusCode(201).body("status", equalTo("COMPLETED")).body("rulesEvaluated", equalTo(3))
                .body("findings", equalTo(0));
        admin.in(org).header("If-Match", "\"0\"").body(Map.of("name", "Provenance required", "severity", "HIGH",
                "enabled", false)).put("/api/v1/integrity/rules/" + rule).then().statusCode(200).body("version", equalTo(1));
        admin.in(org).get("/api/v1/integrity/rules/" + rule + "/versions").then().statusCode(200).body("size()", equalTo(2));
        admin.in(org).get("/api/v1/integrity/incidents").then().statusCode(200).body("page.totalItems", equalTo(0));
    }

    @Test
    void everythingIsTenantScopedAndPermissioned() {
        TestApi.User other = TestApi.newUser("atlasx-other");
        UUID otherOrg = TestApi.createOrganization(other, "atlasx-o");
        other.in(otherOrg).get("/api/v1/recovery/incidents/" + incident + "/conflicts").then().statusCode(404);
        other.in(otherOrg).post("/api/v1/recovery/incidents/" + incident + "/simulations").then().statusCode(404);
        String run = admin.in(org).post("/api/v1/recovery/incidents/" + incident + "/simulations").path("run.id");
        other.in(otherOrg).get("/api/v1/recovery/simulations/" + run + "/strategies/FULL_RESTORE/records")
                .then().statusCode(404);
        other.in(otherOrg).get("/api/v1/recovery/simulations/" + run + "/comparisons").then().statusCode(404);
        other.in(otherOrg).get("/api/v1/provenance/product/" + widget + "/lineage").then().statusCode(200)
                .body("edges.size()", equalTo(0));
        other.in(otherOrg).get("/api/v1/history/product/" + widget + "/timeline").then().statusCode(200)
                .body("size()", equalTo(0));

        TestApi.User sales = TestApi.newUser("atlasx-sales");
        admin.in(org).body(Map.of("email", sales.email(), "roles", Set.of("SALES_STAFF"))).post("/api/v1/members")
                .then().statusCode(201);
        sales.in(org).get("/api/v1/provenance/product/" + widget + "/lineage").then().statusCode(403);
        sales.in(org).post("/api/v1/recovery/incidents/" + incident + "/simulations").then().statusCode(403);
        sales.in(org).get("/api/v1/integrity/incidents").then().statusCode(403);
        sales.in(org).post("/api/v1/integrity/checks").then().statusCode(403);
    }
}
