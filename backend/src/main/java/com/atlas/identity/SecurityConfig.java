package com.atlas.identity;

import com.atlas.common.observability.AtlasMetrics;
import com.atlas.common.observability.MetricsProperties;
import com.atlas.common.ratelimit.RateLimitFilter;
import com.atlas.common.ratelimit.RateLimitPolicy;
import com.atlas.common.ratelimit.RateLimitProperties;
import com.atlas.common.web.Problems;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * Stateless bearer-token security. CSRF protection is off because the API
 * authenticates with an Authorization header, which browsers never attach
 * on their own; the one cookie (refresh) is SameSite=Strict and only
 * accepted by /api/v1/auth.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

    static final String API_CSP = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";

    /** Per-instance request limits; see RateLimitPolicy for the rules. */
    @Bean
    RateLimitPolicy rateLimitPolicy(RateLimitProperties limits) {
        return RateLimitPolicy.from(limits, Clock.systemUTC());
    }

    /**
     * /actuator/prometheus gets its own chain: HTTP Basic with credentials from the
     * environment (Prometheus supports basic_auth), and denied to everyone when they
     * are not configured. Ordered first so the bearer-token chain never sees it.
     */
    @Bean
    @Order(1)
    SecurityFilterChain metricsSecurity(HttpSecurity http, MetricsProperties metrics) throws Exception {
        http.securityMatcher("/actuator/prometheus")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(Customizer.withDefaults());
        if (metrics.configured()) {
            PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
            http.userDetailsService(new InMemoryUserDetailsManager(User.withUsername(metrics.username())
                            .password(encoder.encode(metrics.password())).roles("METRICS").build()))
                    .authorizeHttpRequests(a -> a.anyRequest().hasRole("METRICS"));
        } else {
            http.authorizeHttpRequests(a -> a.anyRequest().denyAll());
        }
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiSecurity(HttpSecurity http, ObjectMapper mapper, RateLimitPolicy limits,
                                    AtlasMetrics atlasMetrics) throws Exception {
        AuthenticationEntryPoint unauthenticated = (request, response, ex) -> {
            response.setHeader("WWW-Authenticate", "Bearer");
            writeProblem(response, mapper, 401, "Unauthorized", "Sign in to access this resource.");
        };
        AccessDeniedHandler denied = (request, response, ex) ->
                writeProblem(response, mapper, 403, "Forbidden", "You do not have access to this resource.");

        // CSRF protection is off because no ambient credential reaches the API: requests carry a
        // bearer token in a header, and the one cookie (refresh token) is SameSite=Strict and
        // scoped to /api/v1/auth. CORS is not enabled, so other origins cannot read responses.
        http.csrf(csrf -> csrf.disable())
                // The API serves JSON only: forbid every resource type and framing. Defaults kept:
                // nosniff, X-Frame-Options DENY, Cache-Control no-store, HSTS on HTTPS.
                .headers(h -> h
                        .contentSecurityPolicy(csp -> csp.policyDirectives(API_CSP))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login",
                                "/api/v1/auth/refresh", "/api/v1/auth/logout").permitAll()
                        .requestMatchers("/api/v1/system/**").hasRole("PLATFORM_ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(unauthenticated)
                        .accessDeniedHandler(denied))
                .exceptionHandling(e -> e.authenticationEntryPoint(unauthenticated).accessDeniedHandler(denied))
                // After authentication, so the API limit can key on the signed-in user.
                .addFilterAfter(new RateLimitFilter(limits, mapper, atlasMetrics), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /** Platform admin is the only authority carried in the token; tenant roles are loaded per request. */
    private static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(SecurityConfig::authorities);
        return converter;
    }

    private static Collection<GrantedAuthority> authorities(Jwt jwt) {
        if (Boolean.TRUE.equals(jwt.getClaimAsBoolean("platform_admin"))) {
            return List.of(new SimpleGrantedAuthority("ROLE_PLATFORM_ADMIN"));
        }
        return List.of();
    }

    @Bean
    SecretKey jwtSigningKey(AuthProperties properties) {
        return new SecretKeySpec(properties.jwtKeyBytes(), "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSigningKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSigningKey) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(AccessTokenIssuer.ISSUER));
        return decoder;
    }

    /** BCrypt by default, with {id} prefixes so the algorithm can be upgraded later. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    private static void writeProblem(HttpServletResponse response, ObjectMapper mapper, int status,
                                     String title, String detail) throws IOException {
        ProblemDetail problem = Problems.of(status, title, detail);
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), problem);
    }
}
