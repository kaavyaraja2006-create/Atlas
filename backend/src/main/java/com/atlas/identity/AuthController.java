package com.atlas.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The access token goes in the response body (the SPA keeps it in memory).
 * The refresh token goes in an HttpOnly, SameSite=Strict cookie scoped to
 * /api/v1/auth, so page scripts cannot read it and other sites cannot make
 * the browser send it.
 */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController {

    static final String REFRESH_COOKIE = "atlas_refresh";
    private static final String COOKIE_PATH = "/api/v1/auth";

    record RegisterRequest(
            @NotBlank @Email @Size(max = 320) String email,
            @NotBlank @Size(max = 200) String displayName,
            @NotNull @Size(max = 1000) String password) {
    }

    record LoginRequest(@NotBlank @Size(max = 320) String email, @NotBlank @Size(max = 1000) String password) {
    }

    record RegisteredUser(UUID id, String email) {
    }

    record AccessTokenResponse(String accessToken, String tokenType, Instant expiresAt) {
    }

    private final AuthService auth;
    private final AuthProperties properties;

    AuthController(AuthService auth, AuthProperties properties) {
        this.auth = auth;
        this.properties = properties;
    }

    @PostMapping("/register")
    ResponseEntity<RegisteredUser> register(@Valid @RequestBody RegisterRequest request) {
        UUID id = auth.register(request.email(), request.displayName(), request.password());
        return ResponseEntity.status(HttpStatus.CREATED).body(new RegisteredUser(id, request.email().trim()));
    }

    @PostMapping("/login")
    ResponseEntity<AccessTokenResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return withSession(auth.login(request.email(), request.password(), ClientInfo.from(http)));
    }

    @PostMapping("/refresh")
    ResponseEntity<AccessTokenResponse> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken, HttpServletRequest http) {
        return withSession(auth.refresh(refreshToken, ClientInfo.from(http)));
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        auth.logout(refreshToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString())
                .build();
    }

    private ResponseEntity<AccessTokenResponse> withSession(AuthService.Session session) {
        Duration refreshTtl = Duration.between(Instant.now(), session.refreshTokenExpiresAt());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie(session.refreshToken(), refreshTtl).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new AccessTokenResponse(session.accessToken(), "Bearer", session.accessTokenExpiresAt()));
    }

    private ResponseCookie cookie(String value, Duration maxAge) {
        return ResponseCookie.from(REFRESH_COOKIE, value)
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .sameSite("Strict")
                .path(COOKIE_PATH)
                .maxAge(maxAge)
                .build();
    }
}
