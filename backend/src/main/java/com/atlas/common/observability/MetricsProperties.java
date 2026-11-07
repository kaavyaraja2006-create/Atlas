package com.atlas.common.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Credentials Prometheus uses to scrape /actuator/prometheus (HTTP Basic). With
 * either value missing the endpoint refuses every request: metrics are never public.
 */
@ConfigurationProperties("atlas.metrics")
public record MetricsProperties(String username, String password) {

    public boolean configured() {
        return username != null && !username.isBlank() && password != null && password.length() >= 16;
    }
}
