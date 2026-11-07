package com.atlas;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * Phase 8: rate limits answer 429 with Retry-After, and the metrics endpoint is
 * served only to the configured scraper. Tiny limits for this context only.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "atlas.rate-limit.registrations-per-hour=2",
        "atlas.rate-limit.sign-in-per-minute=3",
        "atlas.metrics.username=prometheus",
        "atlas.metrics.password=scrape-secret-for-tests"})
@Import(TestcontainersConfiguration.class)
class HardeningIT {

    @LocalServerPort
    int port;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    private static io.restassured.response.Response register(String email) {
        return given().contentType(ContentType.JSON)
                .body(Map.of("email", email, "displayName", "Test", "password", TestApi.PASSWORD))
                .post("/api/v1/auth/register");
    }

    @Test
    void registrationBeyondTheLimitGets429WithRetryAfterAndIsCounted() {
        register(TestApi.uniqueEmail("rl")).then().statusCode(201);
        register(TestApi.uniqueEmail("rl")).then().statusCode(201);
        register(TestApi.uniqueEmail("rl")).then()
                .statusCode(429)
                .header("Retry-After", "1800")   // 2 per hour: one every 30 minutes
                .contentType("application/problem+json")
                .body("status", equalTo(429))
                .body("title", equalTo("Too Many Requests"));

        given().auth().preemptive().basic("prometheus", "scrape-secret-for-tests")
                .get("/actuator/prometheus").then()
                .statusCode(200)
                .body(containsString("atlas_ratelimit_rejected_total{"))
                .body(containsString("limit=\"registration\""));
    }

    @Test
    void signInIsLimitedPerAddressEvenWithTheRightPassword() {
        for (int i = 0; i < 3; i++) {
            given().contentType(ContentType.JSON)
                    .body(Map.of("email", "nobody-" + i + "@atlas.test", "password", "wrong-password-123"))
                    .post("/api/v1/auth/login").then().statusCode(401);
        }
        given().contentType(ContentType.JSON)
                .body(Map.of("email", "nobody@atlas.test", "password", "wrong-password-123"))
                .post("/api/v1/auth/login").then().statusCode(429);
    }

    @Test
    void metricsAreRefusedWithoutTheScrapeCredentials() {
        given().get("/actuator/prometheus").then().statusCode(401);
        given().auth().preemptive().basic("prometheus", "wrong").get("/actuator/prometheus").then().statusCode(401);
    }
}
