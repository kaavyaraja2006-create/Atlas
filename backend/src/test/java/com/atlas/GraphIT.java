package com.atlas;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.restassured.RestAssured;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Graph Intelligence through HTTP: the ER Schema Explorer is platform-admin only (checked in the
 * database, 403 otherwise), every access and denial is in the platform audit trail, and the
 * schema is read from the live catalog with a snapshot stored at start-up.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class GraphIT {

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    private TestApi.User platformAdmin() {
        String email = TestApi.uniqueEmail("root");
        TestApi.register(email);
        jdbc.sql("UPDATE users SET platform_admin = true WHERE email = :e").param("e", email).update();
        return new TestApi.User(email, TestApi.login(email, TestApi.PASSWORD));
    }

    private long audited(String email, String action) {
        return jdbc.sql("""
                SELECT count(*) FROM platform_audit_events a JOIN users u ON u.id = a.actor_user_id
                 WHERE u.email = :e AND a.action = :a
                """).param("e", email).param("a", action).query(Long.class).single();
    }

    @Test
    void ordinaryUsersAreRefusedAndTheRefusalIsAudited() {
        TestApi.User user = TestApi.newUser("plain");
        TestApi.createOrganization(user, "plain");   // an organization admin is still not a platform admin
        user.as().get("/api/v1/graph/schema").then().statusCode(403);
        user.as().get("/api/v1/graph/schema/versions").then().statusCode(403);
        user.as().get("/api/v1/graph/schema/diff?from=live").then().statusCode(403);
        user.as().body(Map.of("format", "SVG", "version", "live", "tables", 1)).post("/api/v1/graph/schema/exports")
                .then().statusCode(403);
        assertEquals(4, audited(user.email(), "SCHEMA_ACCESS_DENIED"));
        RestAssured.given().get("/api/v1/graph/schema").then().statusCode(401);
    }

    @Test
    void platformAdminsSeeTheLiveCatalogAndEveryAccessIsAudited() {
        TestApi.User root = platformAdmin();
        root.as().get("/api/v1/graph/schema").then().statusCode(200)
                .body("flywayVersion", equalTo("15"))
                .body("schema.tables.name", hasItem("provenance_edges"))
                .body("schema.relationships.find { it.from == 'invoices' && it.to == 'orders' }.kind",
                        equalTo("AT_MOST_ONE_ACTIVE"))
                .body("schema.relationships.findAll { it.kind == 'MANY_TO_MANY' }.size()", equalTo(2))
                .body("latestSnapshot", notNullValue())
                .body("changedSinceSnapshot", equalTo(false));
        String snapshot = root.as().get("/api/v1/graph/schema/versions").then().statusCode(200)
                .body("size()", greaterThan(0)).extract().path("[0].id");
        root.as().get("/api/v1/graph/schema/diff?from=" + snapshot + "&to=live").then().statusCode(200)
                .body("diff.changes.size()", equalTo(0));
        root.as().get("/api/v1/graph/schema/diff?from=not-an-id").then().statusCode(400);
        root.as().body(Map.of("format", "PNG", "version", "live", "tables", 39)).post("/api/v1/graph/schema/exports")
                .then().statusCode(201).body("auditEventId", notNullValue());
        root.as().body(Map.of("format", "PDF", "version", "live", "tables", 39)).post("/api/v1/graph/schema/exports")
                .then().statusCode(400);
        assertEquals(1, audited(root.email(), "SCHEMA_VIEW"));
        assertEquals(1, audited(root.email(), "SCHEMA_VERSIONS"));
        assertEquals(1, audited(root.email(), "SCHEMA_DIFF"));
        assertEquals(1, audited(root.email(), "SCHEMA_EXPORT"));
    }

    @Test
    void revokingTheFlagTakesEffectBeforeTheTokenExpires() {
        TestApi.User root = platformAdmin();
        root.as().get("/api/v1/graph/schema/versions").then().statusCode(200);
        jdbc.sql("UPDATE users SET platform_admin = false WHERE email = :e").param("e", root.email()).update();
        root.as().get("/api/v1/graph/schema/versions").then().statusCode(403);
    }
}
