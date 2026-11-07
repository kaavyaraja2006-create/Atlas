package com.atlas;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class AuthFlowIT {

    private static final String COOKIE = "atlas_refresh";

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    private static Response login(String email, String password) {
        return given().contentType(ContentType.JSON)
                .body(Map.of("email", email, "password", password))
                .post("/api/v1/auth/login");
    }

    private static Response refresh(String cookie) {
        return given().cookie(COOKIE, cookie).post("/api/v1/auth/refresh");
    }

    @Test
    void registrationValidatesAndRejectsDuplicates() {
        String email = TestApi.uniqueEmail("reg");
        TestApi.register(email);
        given().contentType(ContentType.JSON)
                .body(Map.of("email", email.toUpperCase(), "displayName", "Dup", "password", TestApi.PASSWORD))
                .post("/api/v1/auth/register")
                .then().statusCode(409);
        given().contentType(ContentType.JSON)
                .body(Map.of("email", TestApi.uniqueEmail("weak"), "displayName", "Weak", "password", "short"))
                .post("/api/v1/auth/register")
                .then().statusCode(400)
                .body("errors", hasItem("password: must be at least 12 characters"));
    }

    @Test
    void loginSetsHardenedRefreshCookieAndReturnsAccessToken() {
        String email = TestApi.uniqueEmail("login");
        TestApi.register(email);

        login(email, "wrong-password-entirely").then().statusCode(401)
                .body("detail", equalTo("Invalid email or password, or the account is temporarily locked."));
        login("nobody-" + email, TestApi.PASSWORD).then().statusCode(401)
                .body("detail", equalTo("Invalid email or password, or the account is temporarily locked."));

        Response ok = login(email, TestApi.PASSWORD);
        ok.then().statusCode(200)
                .header("Cache-Control", "no-store")
                .body("tokenType", equalTo("Bearer"))
                .body("accessToken", notNullValue());
        String setCookie = ok.getHeader("Set-Cookie");
        assertThat(setCookie).contains(COOKIE + "=", "HttpOnly", "SameSite=Strict", "Path=/api/v1/auth");

        given().header("Authorization", "Bearer " + ok.path("accessToken"))
                .get("/api/v1/users/me")
                .then().statusCode(200).body("email", equalTo(email));
    }

    @Test
    void tamperedAccessTokenIsRejected() {
        TestApi.User user = TestApi.newUser("tamper");
        String token = user.token();
        String tampered = token.substring(0, token.length() - 4) + (token.endsWith("AAAA") ? "BBBB" : "AAAA");
        given().header("Authorization", "Bearer " + tampered).get("/api/v1/users/me").then().statusCode(401);
    }

    /** Critical scenario: a stolen, already-used refresh token must kill the whole session family. */
    @Test
    void refreshRotatesAndReuseRevokesTheFamily() {
        String email = TestApi.uniqueEmail("rotate");
        TestApi.register(email);
        String first = login(email, TestApi.PASSWORD).getCookie(COOKIE);

        Response rotated = refresh(first);
        rotated.then().statusCode(200).body("accessToken", notNullValue());
        String second = rotated.getCookie(COOKIE);
        assertThat(second).isNotBlank().isNotEqualTo(first);

        refresh(first).then().statusCode(401);    // replay of a used token
        refresh(second).then().statusCode(401);   // ...revoked the legitimate successor too

        String revokedReason = jdbc.sql("""
                SELECT DISTINCT revoke_reason FROM refresh_tokens rt JOIN users u ON u.id = rt.user_id
                 WHERE lower(u.email) = lower(:email)""").param("email", email).query(String.class).single();
        assertThat(revokedReason).isEqualTo("REUSE_DETECTED");
    }

    @Test
    void logoutRevokesRefreshToken() {
        String email = TestApi.uniqueEmail("logout");
        TestApi.register(email);
        String cookie = login(email, TestApi.PASSWORD).getCookie(COOKIE);
        given().cookie(COOKIE, cookie).post("/api/v1/auth/logout").then().statusCode(204);
        refresh(cookie).then().statusCode(401);
    }

    @Test
    void fiveFailuresLockTheAccountEvenAgainstTheRightPassword() {
        String email = TestApi.uniqueEmail("lock");
        TestApi.register(email);
        for (int i = 0; i < 5; i++) {
            login(email, "definitely-not-it-" + i).then().statusCode(401);
        }
        login(email, TestApi.PASSWORD).then().statusCode(401);

        String lastOutcome = jdbc.sql("""
                SELECT outcome FROM login_events WHERE email_attempted = :email ORDER BY id DESC LIMIT 1""")
                .param("email", email).query(String.class).single();
        assertThat(lastOutcome).isEqualTo("LOCKED");
        Integer failures = jdbc.sql("SELECT failed_login_count FROM users WHERE lower(email) = lower(:email)")
                .param("email", email).query(Integer.class).single();
        assertThat(failures).isEqualTo(5);
    }
}
