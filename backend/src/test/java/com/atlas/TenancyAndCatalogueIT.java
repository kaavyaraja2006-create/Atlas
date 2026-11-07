package com.atlas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class TenancyAndCatalogueIT {

    @LocalServerPort
    int port;

    private TestApi.User admin;
    private UUID org;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
        admin = TestApi.newUser("admin");
        org = TestApi.createOrganization(admin, "acme");
    }

    private String createProduct(String sku, String price) {
        return admin.in(org)
                .body(Map.of("sku", sku, "name", "Widget " + sku, "unitPrice", price, "unitCost", "3000"))
                .post("/api/v1/products")
                .then().statusCode(201).header("ETag", "\"0\"")
                .extract().path("id");
    }

    private TestApi.User addMember(String role) {
        TestApi.User user = TestApi.newUser(role.toLowerCase());
        admin.in(org).body(Map.of("email", user.email(), "roles", Set.of(role)))
                .post("/api/v1/members").then().statusCode(201);
        return user;
    }

    @Test
    void tenantHeaderIsRequiredAndNonMembersSeeNotFound() {
        admin.as().get("/api/v1/products").then().statusCode(400);
        admin.as().header("X-Organization-Id", "not-a-uuid").get("/api/v1/products").then().statusCode(400);
        admin.in(UUID.randomUUID()).get("/api/v1/products").then().statusCode(404);

        TestApi.User outsider = TestApi.newUser("outsider");
        outsider.in(org).get("/api/v1/products").then().statusCode(404)
                .body("detail", equalTo("Organization not found"));
    }

    /** Critical scenario 1: changing an id in the URL must not reach another tenant's data. */
    @Test
    void crossTenantProductAccessIsNotFound() {
        String productId = createProduct("P100", "5000");
        TestApi.User otherAdmin = TestApi.newUser("other");
        UUID otherOrg = TestApi.createOrganization(otherAdmin, "globex");

        otherAdmin.in(otherOrg).get("/api/v1/products/" + productId).then().statusCode(404);
        otherAdmin.in(otherOrg).header("If-Match", "\"0\"").body(Map.of("unitPrice", "1"))
                .patch("/api/v1/products/" + productId).then().statusCode(404);
        otherAdmin.in(otherOrg).get("/api/v1/products").then().statusCode(200).body("page.totalItems", equalTo(0));
        otherAdmin.in(org).get("/api/v1/products/" + productId).then().statusCode(404);
    }

    @Test
    void permissionsFollowRoles() {
        TestApi.User employee = addMember("EMPLOYEE");
        employee.in(org).get("/api/v1/products").then().statusCode(200);
        employee.in(org).get("/api/v1/access").then().statusCode(200)
                .body("roles", equalTo(List.of("EMPLOYEE")))
                .body("permissions", hasItem("catalogue.read"));
        employee.in(org).body(Map.of("sku", "X1", "name", "x", "unitPrice", "1", "unitCost", "1"))
                .post("/api/v1/products").then().statusCode(403)
                .body("detail", equalTo("Missing permission: catalogue.write"));
        employee.in(org).get("/api/v1/audit/events").then().statusCode(403);

        // Promotion takes effect on the very next request: roles are not baked into the token.
        UUID employeeId = UUID.fromString(employee.as().get("/api/v1/users/me").path("id"));
        admin.in(org).body(Map.of("roles", Set.of("OPERATIONS_MANAGER")))
                .put("/api/v1/members/" + employeeId + "/roles").then().statusCode(200);
        employee.in(org).body(Map.of("sku", "X1", "name", "x", "unitPrice", "1", "unitCost", "1"))
                .post("/api/v1/products").then().statusCode(201);
    }

    @Test
    void lastAdminCannotBeDemoted() {
        UUID adminId = UUID.fromString(admin.as().get("/api/v1/users/me").path("id"));
        admin.in(org).body(Map.of("roles", Set.of("EMPLOYEE")))
                .put("/api/v1/members/" + adminId + "/roles").then().statusCode(422);
        admin.in(org).body(Map.of("roles", Set.of("NOT_A_ROLE")))
                .put("/api/v1/members/" + adminId + "/roles").then().statusCode(400);
    }

    @Test
    void productValidationAndDuplicateSku() {
        admin.in(org).body(Map.of("sku", "bad sku!", "name", "", "unitPrice", "-1", "unitCost", "1.123456"))
                .post("/api/v1/products").then().statusCode(400)
                .body("errors", hasItem("unitPrice: must be greater than or equal to 0"));
        createProduct("P100", "5000");
        admin.in(org).body(Map.of("sku", "P100", "name", "dup", "unitPrice", "1", "unitCost", "1"))
                .post("/api/v1/products").then().statusCode(409);
        admin.in(org).get("/api/v1/products?sort=unit_price;drop").then().statusCode(400);
    }

    /** Critical scenario 2: concurrent edits are detected, never silently overwritten. */
    @Test
    void optimisticConcurrencyWithEtags() {
        String id = createProduct("P100", "5000");
        String path = "/api/v1/products/" + id;

        admin.in(org).get(path).then().statusCode(200).header("ETag", "\"0\"");
        admin.in(org).body(Map.of("unitPrice", "500")).patch(path).then().statusCode(428);
        admin.in(org).header("If-Match", "\"0\"").body(Map.of("unitPrice", "500", "reason", "price edit"))
                .patch(path).then().statusCode(200).header("ETag", "\"1\"").body("unitPrice", equalTo(500.0f));
        admin.in(org).header("If-Match", "\"0\"").body(Map.of("unitPrice", "450"))
                .patch(path).then().statusCode(409);
        // Same value, different scale: a no-op that creates no new version.
        admin.in(org).header("If-Match", "\"1\"").body(Map.of("unitPrice", "500.00"))
                .patch(path).then().statusCode(200).header("ETag", "\"1\"");
    }

    @Test
    void twoSimultaneousEditsOfTheSameVersionProduceExactlyOneWinner() throws Exception {
        String id = createProduct("P200", "5000");
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> edit = () -> {
            start.await();
            return admin.in(org).header("If-Match", "\"0\"").body(Map.of("unitPrice", "4000"))
                    .patch("/api/v1/products/" + id).statusCode();
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = pool.submit(edit);
            Future<Integer> b = pool.submit(edit);
            start.countDown();
            assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void everyChangeIsAuditedWithSnapshotsAndTheChainVerifies() {
        String id = createProduct("P100", "5000");
        admin.in(org).header("If-Match", "\"0\"").header("X-Correlation-Id", "it-price-edit")
                .body(Map.of("unitPrice", "500", "reason", "the faulty edit"))
                .patch("/api/v1/products/" + id).then().statusCode(200);

        Response events = admin.in(org).get("/api/v1/audit/events?entityType=product&entityId=" + id);
        events.then().statusCode(200).body("page.totalItems", equalTo(2));
        Map<String, Object> update = events.path("items[0]");
        assertThat(update).containsEntry("operation", "UPDATE").containsEntry("entityVersion", 1)
                .containsEntry("changedFields", List.of("unit_price"))
                .containsEntry("reason", "the faulty edit")
                .containsEntry("correlationId", "it-price-edit");
        assertThat(events.<Float>path("items[0].before.unit_price")).isEqualTo(5000.0f);
        assertThat(events.<Float>path("items[0].after.unit_price")).isEqualTo(500.0f);
        assertThat(events.<String>path("items[1].operation")).isEqualTo("INSERT");
        assertThat(events.<String>path("items[0].txnId")).isNotEqualTo(events.<String>path("items[1].txnId"));

        // Organization creation, admin membership, product v0, product v1.
        admin.in(org).post("/api/v1/audit/verify").then().statusCode(200)
                .body("intact", equalTo(true)).body("eventCount", equalTo(4));
    }
}
