package com.atlas;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;

import io.restassured.RestAssured;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ApplicationIT {

    @LocalServerPort
    int port;

    @Autowired
    Flyway flyway;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @Test
    void healthIsPublicAndUp() {
        given().get("/actuator/health")
                .then().statusCode(200)
                .body("status", equalTo("UP"));
    }

    @Test
    void allMigrationsAreAppliedAtStartup() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("15");
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    void systemInfoRequiresPlatformAdmin() {
        given().get("/api/v1/system/info").then().statusCode(401);
        TestApi.User user = TestApi.newUser("not-admin");
        user.as().get("/api/v1/system/info").then().statusCode(403);
    }

    @Test
    void apiResponsesCarrySecurityHeaders() {
        given().get("/actuator/health").then()
                .header("Content-Security-Policy",
                        "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'")
                .header("Referrer-Policy", "no-referrer")
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Frame-Options", "DENY");
    }

    @Test
    void safeCorrelationIdIsEchoed() {
        given().header("X-Correlation-Id", "it-req-001")
                .get("/actuator/health")
                .then().header("X-Correlation-Id", "it-req-001");
    }

    @Test
    void unsafeCorrelationIdIsReplaced() {
        given().header("X-Correlation-Id", "bad id with spaces")
                .get("/actuator/health")
                .then().header("X-Correlation-Id", matchesPattern("^[0-9a-f-]{36}$"));
    }

    @Test
    void unauthenticatedRequestGetsProblemDetailWithCorrelationId() {
        given().header("X-Correlation-Id", "it-req-401")
                .get("/api/v1/products")
                .then().statusCode(401)
                .header("WWW-Authenticate", "Bearer")
                .contentType("application/problem+json")
                .body("status", equalTo(401))
                .body("correlationId", equalTo("it-req-401"))
                .body("timestamp", notNullValue());
    }

    @Test
    void unknownRouteReturnsProblemDetail() {
        TestApi.User user = TestApi.newUser("route");
        user.as().header("X-Correlation-Id", "it-req-404")
                .get("/api/v1/does-not-exist")
                .then().statusCode(404)
                .contentType("application/problem+json")
                .body("correlationId", equalTo("it-req-404"));
    }
}
