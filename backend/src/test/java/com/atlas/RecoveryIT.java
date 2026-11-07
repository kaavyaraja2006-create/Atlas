package com.atlas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

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

/** End to end through the real database: analysis and planning (Phase 5), approval, execution, verification (Phase 6). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class RecoveryIT {

    @LocalServerPort
    int port;

    private TestApi.User admin;
    private UUID org;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        admin = TestApi.newUser("recovery");
        org = TestApi.createOrganization(admin, "rec");
    }

    @Test
    void analysisAndPlanForAFaultyPrice() {
        String customer = admin.in(org).body(Map.of("name", "Acme")).post("/api/v1/customers").path("id");
        String widget = admin.in(org).body(Map.of("sku", "P100", "name", "Widget", "unitPrice", "5000", "unitCost", "1"))
                .post("/api/v1/products").path("id");
        String tee = admin.in(org).body(Map.of("sku", "T-100", "name", "Tee", "unitPrice", "900", "unitCost", "1"))
                .post("/api/v1/products").path("id");
        admin.in(org).header("If-Match", "\"0\"").body(Map.of("unitPrice", "500", "reason", "typo"))
                .patch("/api/v1/products/" + widget).then().statusCode(200);
        String order = admin.in(org).body(Map.of("customerId", customer)).post("/api/v1/orders").path("id");
        admin.in(org).body(Map.of("productId", widget, "quantity", 3)).post("/api/v1/orders/" + order + "/lines");
        String etag = admin.in(org).body(Map.of("productId", tee, "quantity", 2)).post("/api/v1/orders/" + order + "/lines")
                .header("ETag");
        admin.in(org).header("If-Match", etag).post("/api/v1/orders/" + order + "/confirm").then().statusCode(200);
        String invoice = admin.in(org).post("/api/v1/orders/" + order + "/invoice").path("id");
        admin.in(org).header("If-Match", "\"0\"").post("/api/v1/invoices/" + invoice + "/issue").then().statusCode(200);

        String faultyEvent = admin.in(org).get("/api/v1/audit/events?entityType=product&entityId=" + widget)
                .path("items.find { it.entityVersion == 1 }.id");
        String insertEvent = admin.in(org).get("/api/v1/audit/events?entityType=product&entityId=" + widget)
                .path("items.find { it.entityVersion == 0 }.id");

        admin.in(org).body(Map.of("changeEventId", insertEvent, "title", "x", "reason", "x"))
                .post("/api/v1/recovery/incidents").then().statusCode(422);
        Response opened = admin.in(org).body(Map.of("changeEventId", faultyEvent, "title", "Mistyped price",
                "reason", "P100 entered as 500 instead of 5000")).post("/api/v1/recovery/incidents");
        opened.then().statusCode(201).body("incidentNumber", equalTo("REC-000001")).body("status", equalTo("OPEN"));
        String incident = opened.path("id");
        admin.in(org).body(Map.of("changeEventId", faultyEvent, "title", "again", "reason", "again"))
                .post("/api/v1/recovery/incidents").then().statusCode(409);

        Response impact = admin.in(org).post("/api/v1/recovery/incidents/" + incident + "/impact");
        impact.then().statusCode(200)
                .body("counts.ORIGIN", equalTo(1))
                .body("counts.DIRECTLY_AFFECTED", equalTo(1))     // the widget line
                .body("counts.INDIRECTLY_AFFECTED", equalTo(1))   // the order
                .body("counts.NON_REVERSIBLE", equalTo(2))        // issued invoice + its widget line
                .body("counts.NOT_REACHED_PROVENANCE_COMPLETE", equalTo(2));   // tee line and tee invoice line

        Response plan = admin.in(org).post("/api/v1/recovery/incidents/" + incident + "/plans");
        plan.then().statusCode(201).body("planNumber", equalTo(1)).body("status", equalTo("PROPOSED"))
                .body("fingerprint", startsWith("sha256:")).body("risk", equalTo("HIGH"))
                .body("requiresApproval", equalTo(true));
        List<String> actions = plan.path("plan.steps.action");
        assertThat(actions).containsExactly("RESTORE_FIELDS", "UPDATE_FIELDS", "UPDATE_FIELDS", "SUPPLEMENTARY_CHARGE");
        assertThat(plan.<Float>path("plan.steps.find { it.action == 'SUPPLEMENTARY_CHARGE' }.amount")).isEqualTo(13500.0f);

        // Regenerating supersedes the first plan; history keeps both.
        admin.in(org).post("/api/v1/recovery/incidents/" + incident + "/plans").then().statusCode(201).body("planNumber", equalTo(2));
        admin.in(org).get("/api/v1/recovery/incidents/" + incident + "/plans").then()
                .body("status", equalTo(List.of("PROPOSED", "SUPERSEDED")));
        admin.in(org).get("/api/v1/recovery/incidents/" + incident).then().body("status", equalTo("PLANNED"));

        // Nothing in the business data changed.
        admin.in(org).get("/api/v1/products/" + widget).then().body("unitPrice", equalTo(500.0f)).header("ETag", "\"1\"");
        admin.in(org).post("/api/v1/audit/verify").then().body("intact", equalTo(true));

        TestApi.User employee = TestApi.newUser("emp");
        admin.in(org).body(Map.of("email", employee.email(), "roles", Set.of("EMPLOYEE")))
                .post("/api/v1/members").then().statusCode(201);
        employee.in(org).body(Map.of("changeEventId", faultyEvent, "title", "x", "reason", "x"))
                .post("/api/v1/recovery/incidents").then().statusCode(403);
        TestApi.User outsider = TestApi.newUser("out");
        UUID otherOrg = TestApi.createOrganization(outsider, "other");
        outsider.in(otherOrg).get("/api/v1/recovery/incidents/" + incident).then().statusCode(404);
    }

    /**
     * P100 typed as 50000 instead of 5000; one order invoiced and ISSUED (over-billed), one still a draft.
     * A planner generates, a separate approver approves, the admin executes, anyone with audit.read verifies.
     */
    @Test
    void approvedPlanIsExecutedAndVerified() {
        String customer = admin.in(org).body(Map.of("name", "Acme")).post("/api/v1/customers").path("id");
        String widget = admin.in(org).body(Map.of("sku", "P100", "name", "Widget", "unitPrice", "5000", "unitCost", "1"))
                .post("/api/v1/products").path("id");
        admin.in(org).header("If-Match", "\"0\"").body(Map.of("unitPrice", "50000", "reason", "typo"))
                .patch("/api/v1/products/" + widget).then().statusCode(200);
        String billed = admin.in(org).body(Map.of("customerId", customer)).post("/api/v1/orders").path("id");
        String etag = admin.in(org).body(Map.of("productId", widget, "quantity", 3))
                .post("/api/v1/orders/" + billed + "/lines").header("ETag");
        admin.in(org).header("If-Match", etag).post("/api/v1/orders/" + billed + "/confirm").then().statusCode(200);
        String invoice = admin.in(org).post("/api/v1/orders/" + billed + "/invoice").path("id");
        admin.in(org).header("If-Match", "\"0\"").post("/api/v1/invoices/" + invoice + "/issue").then().statusCode(200);
        String draft = admin.in(org).body(Map.of("customerId", customer)).post("/api/v1/orders").path("id");
        admin.in(org).body(Map.of("productId", widget, "quantity", 2)).post("/api/v1/orders/" + draft + "/lines");

        String faultyEvent = admin.in(org).get("/api/v1/audit/events?entityType=product&entityId=" + widget)
                .path("items.find { it.entityVersion == 1 }.id");
        String incident = admin.in(org).body(Map.of("changeEventId", faultyEvent, "title", "Mistyped price",
                "reason", "P100 entered as 50000")).post("/api/v1/recovery/incidents").path("id");
        Response plan = admin.in(org).post("/api/v1/recovery/incidents/" + incident + "/plans");
        String planId = plan.path("id");
        String fingerprint = plan.path("fingerprint");
        assertThat(plan.<List<String>>path("plan.steps.action")).contains("ISSUE_CREDIT_NOTE");

        // The admin may not approve (separation of duties: no recovery.approve), nor run an unapproved HIGH plan.
        admin.in(org).body(Map.of("fingerprint", fingerprint)).post("/api/v1/recovery/plans/" + planId + "/approval")
                .then().statusCode(403);
        admin.in(org).post("/api/v1/recovery/plans/" + planId + "/execution").then().statusCode(409);

        TestApi.User approver = TestApi.newUser("approver");
        admin.in(org).body(Map.of("email", approver.email(), "roles", Set.of("RECOVERY_APPROVER")))
                .post("/api/v1/members").then().statusCode(201);
        approver.in(org).body(Map.of("fingerprint", "sha256:" + "0".repeat(64)))
                .post("/api/v1/recovery/plans/" + planId + "/approval").then().statusCode(409);
        approver.in(org).body(Map.of("fingerprint", fingerprint, "comment", "credit checked"))
                .post("/api/v1/recovery/plans/" + planId + "/approval").then().statusCode(200)
                .body("approved", equalTo(true));
        approver.in(org).post("/api/v1/recovery/plans/" + planId + "/execution").then().statusCode(403);

        Response run = admin.in(org).post("/api/v1/recovery/plans/" + planId + "/execution");
        run.then().statusCode(200).body("status", equalTo("EXECUTED"));
        assertThat(run.<List<String>>path("steps.status")).containsOnly("DONE");
        // Executing again replays the result and writes nothing.
        admin.in(org).post("/api/v1/recovery/plans/" + planId + "/execution").then().statusCode(200)
                .body("status", equalTo("EXECUTED"));

        admin.in(org).get("/api/v1/products/" + widget).then().body("unitPrice", equalTo(5000.0f)).header("ETag", "\"2\"");
        admin.in(org).get("/api/v1/orders/" + draft).then().body("totalAmount", equalTo(10000.0f));
        admin.in(org).get("/api/v1/invoices/" + invoice).then().body("status", equalTo("ISSUED"))
                .body("totalAmount", equalTo(150000.0f));
        admin.in(org).get("/api/v1/invoices/" + invoice + "/credit-notes").then()
                .body("amount", equalTo(List.of(135000.0f)));
        admin.in(org).get("/api/v1/audit/events?entityType=product&entityId=" + widget).then()
                .body("items.find { it.entityVersion == 2 }.actorKind", equalTo("RECOVERY"));

        Response verified = admin.in(org).post("/api/v1/recovery/plans/" + planId + "/verifications");
        verified.then().statusCode(200).body("outcome", equalTo("PASSED"));
        assertThat(verified.<List<Boolean>>path("checks.passed")).containsOnly(true);
        admin.in(org).get("/api/v1/recovery/incidents/" + incident).then().body("status", equalTo("RESOLVED"));
        admin.in(org).get("/api/v1/recovery/plans/" + planId + "/execution").then()
                .body("decisions.decision", equalTo(List.of("APPROVED")));
        admin.in(org).post("/api/v1/audit/verify").then().body("intact", equalTo(true));
    }

    @Test
    void theAuthorOfAPlanCannotApproveItEvenWithThePermission() {
        TestApi.User both = TestApi.newUser("both");
        admin.in(org).body(Map.of("email", both.email(), "roles", Set.of("OPERATIONS_MANAGER", "RECOVERY_APPROVER")))
                .post("/api/v1/members").then().statusCode(201);
        String widget = admin.in(org).body(Map.of("sku", "P1", "name", "W", "unitPrice", "10", "unitCost", "1"))
                .post("/api/v1/products").path("id");
        admin.in(org).header("If-Match", "\"0\"").body(Map.of("unitPrice", "100", "reason", "typo"))
                .patch("/api/v1/products/" + widget).then().statusCode(200);
        String faultyEvent = admin.in(org).get("/api/v1/audit/events?entityType=product&entityId=" + widget)
                .path("items.find { it.entityVersion == 1 }.id");
        String incident = both.in(org).body(Map.of("changeEventId", faultyEvent, "title", "t", "reason", "r"))
                .post("/api/v1/recovery/incidents").path("id");
        Response plan = both.in(org).post("/api/v1/recovery/incidents/" + incident + "/plans");
        both.in(org).body(Map.of("fingerprint", plan.<String>path("fingerprint")))
                .post("/api/v1/recovery/plans/" + plan.<String>path("id") + "/approval")
                .then().statusCode(403).body("detail", equalTo("The author of a plan cannot approve it"));
    }
}
