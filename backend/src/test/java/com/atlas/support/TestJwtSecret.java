package com.atlas.support;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * TEST ONLY: gives every test run its own JWT signing key, so no key is
 * committed. 48 bytes from {@link SecureRandom}, base64-encoded as
 * {@code AuthProperties} requires. Spring's {@code ${random.value}} is not used:
 * it is not suitable for secrets (CVE-2026-40975).
 *
 * <p>Registered in {@code src/test/resources/META-INF/spring.factories}, so it
 * exists only on the test classpath. An explicitly set ATLAS_JWT_SECRET wins.
 */
public final class TestJwtSecret implements EnvironmentPostProcessor {

    static final String PROPERTY = "atlas.auth.jwt-secret";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String configured = environment.getProperty(PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return;
        }
        environment.getPropertySources().addFirst(new MapPropertySource("atlasTestJwtSecret",
                Map.of(PROPERTY, generate())));
    }

    static String generate() {
        byte[] key = new byte[48];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}
