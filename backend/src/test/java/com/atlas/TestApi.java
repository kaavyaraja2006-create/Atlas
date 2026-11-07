package com.atlas;

import static io.restassured.RestAssured.given;

import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import java.util.Map;
import java.util.UUID;

/** HTTP helpers for integration tests. All users and organizations created here are test data. */
final class TestApi {

    static final String PASSWORD = "integration-test-passphrase";

    record User(String email, String token) {
        RequestSpecification as() {
            return given().header("Authorization", "Bearer " + token).contentType(ContentType.JSON);
        }

        RequestSpecification in(UUID orgId) {
            return as().header("X-Organization-Id", orgId.toString());
        }
    }

    private TestApi() {
    }

    static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@atlas.test";
    }

    static void register(String email) {
        given().contentType(ContentType.JSON)
                .body(Map.of("email", email, "displayName", "Test " + email, "password", PASSWORD))
                .post("/api/v1/auth/register")
                .then().statusCode(201);
    }

    static String login(String email, String password) {
        return given().contentType(ContentType.JSON)
                .body(Map.of("email", email, "password", password))
                .post("/api/v1/auth/login")
                .then().statusCode(200)
                .extract().path("accessToken");
    }

    static User newUser(String prefix) {
        String email = uniqueEmail(prefix);
        register(email);
        return new User(email, login(email, PASSWORD));
    }

    static UUID createOrganization(User admin, String slugPrefix) {
        String slug = slugPrefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        String id = admin.as()
                .body(Map.of("name", "Org " + slug, "slug", slug))
                .post("/api/v1/organizations")
                .then().statusCode(201)
                .extract().path("id");
        return UUID.fromString(id);
    }
}
