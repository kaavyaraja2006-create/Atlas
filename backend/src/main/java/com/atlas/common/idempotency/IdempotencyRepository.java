package com.atlas.common.idempotency;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class IdempotencyRepository {

    record StoredKey(UUID userId, String method, String path, byte[] requestHash, String status,
                     Integer responseStatus, String responseBody) {

        boolean sameRequest(UUID otherUser, String otherMethod, String otherPath, byte[] otherHash) {
            return userId.equals(otherUser) && method.equals(otherMethod) && path.equals(otherPath)
                    && Arrays.equals(requestHash, otherHash);
        }

        boolean completed() {
            return "COMPLETED".equals(status);
        }
    }

    /**
     * A concurrent duplicate blocks on the primary key until the first
     * transaction commits, then inserts nothing and reads the stored result.
     */
    static final String CLAIM_IDEMPOTENCY_KEY = """
            INSERT INTO idempotency_keys (organization_id, idempotency_key, user_id, method, path, request_hash)
            VALUES (:orgId, :key, :userId, :method, :path, :requestHash)
            ON CONFLICT (organization_id, idempotency_key) DO NOTHING
            RETURNING idempotency_key
            """;

    static final String FIND_IDEMPOTENCY_KEY = """
            SELECT user_id, method, path, request_hash, status, response_status,
                   CAST(response_body AS text) AS response_body
              FROM idempotency_keys
             WHERE organization_id = :orgId AND idempotency_key = :key
            """;

    static final String COMPLETE_IDEMPOTENCY_KEY = """
            UPDATE idempotency_keys
               SET status = 'COMPLETED', response_status = :responseStatus,
                   response_body = CAST(:responseBody AS jsonb), completed_at = now()
             WHERE organization_id = :orgId AND idempotency_key = :key AND status = 'IN_PROGRESS'
            """;

    private final JdbcClient jdbc;

    IdempotencyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    boolean claim(UUID orgId, String key, UUID userId, String method, String path, byte[] requestHash) {
        return jdbc.sql(CLAIM_IDEMPOTENCY_KEY)
                .param("orgId", orgId).param("key", key).param("userId", userId)
                .param("method", method).param("path", path).param("requestHash", requestHash)
                .query(String.class).optional().isPresent();
    }

    Optional<StoredKey> find(UUID orgId, String key) {
        return jdbc.sql(FIND_IDEMPOTENCY_KEY).param("orgId", orgId).param("key", key)
                .query((rs, n) -> new StoredKey(
                        rs.getObject("user_id", UUID.class), rs.getString("method"), rs.getString("path"),
                        rs.getBytes("request_hash"), rs.getString("status"),
                        rs.getObject("response_status", Integer.class), rs.getString("response_body")))
                .optional();
    }

    void complete(UUID orgId, String key, int responseStatus, String responseBody) {
        int updated = jdbc.sql(COMPLETE_IDEMPOTENCY_KEY)
                .param("orgId", orgId).param("key", key)
                .param("responseStatus", responseStatus).param("responseBody", responseBody)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Idempotency key " + key + " was not in progress");
        }
    }
}
