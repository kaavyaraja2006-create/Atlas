package com.atlas.identity;

import com.atlas.common.observability.AtlasMetrics;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class LoginEventRepository {

    static final String INSERT_LOGIN_EVENT = """
            INSERT INTO login_events (user_id, email_attempted, outcome, ip_address, user_agent)
            VALUES (:userId, :email, :outcome, CAST(:ip AS inet), :userAgent)
            """;

    private final JdbcClient jdbc;
    private final AtlasMetrics metrics;

    LoginEventRepository(JdbcClient jdbc, AtlasMetrics metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    void insert(UUID userId, String emailAttempted, String outcome, ClientInfo client) {
        jdbc.sql(INSERT_LOGIN_EVENT)
                .param("userId", userId)
                .param("email", truncate(emailAttempted, 320))
                .param("outcome", outcome)
                .param("ip", client.ip())
                .param("userAgent", client.userAgent())
                .update();
        metrics.login(outcome);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
