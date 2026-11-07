package com.atlas.identity;

import com.atlas.common.db.Db;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class RefreshTokenRepository {

    record StoredToken(UUID id, UUID userId, UUID familyId, Instant expiresAt, Instant rotatedAt,
                       Instant revokedAt) {
    }

    static final String INSERT_REFRESH_TOKEN = """
            INSERT INTO refresh_tokens (user_id, family_id, token_hash, issued_at, expires_at, user_agent)
            VALUES (:userId, :familyId, :tokenHash, :now, :expiresAt, :userAgent)
            """;

    static final String FIND_REFRESH_TOKEN = """
            SELECT id, user_id, family_id, expires_at, rotated_at, revoked_at
              FROM refresh_tokens
             WHERE token_hash = :tokenHash
            """;

    /** 0 rows means another request already rotated (or revoked) this token. */
    static final String MARK_TOKEN_ROTATED = """
            UPDATE refresh_tokens
               SET rotated_at = :now
             WHERE id = :id AND rotated_at IS NULL AND revoked_at IS NULL
            """;

    static final String REVOKE_TOKEN_FAMILY = """
            SELECT revoke_token_family(:familyId, :reason)
            """;

    private final JdbcClient jdbc;

    RefreshTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void insert(UUID userId, UUID familyId, byte[] tokenHash, Instant now, Instant expiresAt, String userAgent) {
        jdbc.sql(INSERT_REFRESH_TOKEN)
                .param("userId", userId)
                .param("familyId", familyId)
                .param("tokenHash", tokenHash)
                .param("now", Db.ts(now))
                .param("expiresAt", Db.ts(expiresAt))
                .param("userAgent", userAgent)
                .update();
    }

    Optional<StoredToken> findByHash(byte[] tokenHash) {
        return jdbc.sql(FIND_REFRESH_TOKEN)
                .param("tokenHash", tokenHash)
                .query((rs, n) -> new StoredToken(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getObject("family_id", UUID.class),
                        Db.instant(rs, "expires_at"),
                        Db.instant(rs, "rotated_at"),
                        Db.instant(rs, "revoked_at")))
                .optional();
    }

    boolean markRotated(UUID id, Instant now) {
        return jdbc.sql(MARK_TOKEN_ROTATED).param("id", id).param("now", Db.ts(now)).update() == 1;
    }

    int revokeFamily(UUID familyId, String reason) {
        return jdbc.sql(REVOKE_TOKEN_FAMILY)
                .param("familyId", familyId)
                .param("reason", reason)
                .query(Integer.class)
                .single();
    }
}
