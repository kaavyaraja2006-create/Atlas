package com.atlas.identity;

import com.atlas.common.db.Db;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class UserRepository {

    static final String INSERT_USER = """
            INSERT INTO users (email, display_name, password_hash)
            VALUES (:email, :displayName, :passwordHash)
            RETURNING id
            """;

    static final String FIND_USER_BY_EMAIL = """
            SELECT id, email, display_name, password_hash, platform_admin, status,
                   failed_login_count, locked_until
              FROM users
             WHERE lower(email) = lower(:email)
            """;

    static final String FIND_USER_BY_ID = """
            SELECT id, email, display_name, password_hash, platform_admin, status,
                   failed_login_count, locked_until
              FROM users
             WHERE id = :id
            """;

    /*
     * Atomic, so parallel guesses cannot race past the limit. If an earlier
     * lock has expired the count restarts at 1, otherwise a user would be
     * re-locked by their first typo after the lock ends.
     */
    static final String RECORD_LOGIN_FAILURE = """
            UPDATE users
               SET failed_login_count = CASE
                       WHEN locked_until IS NOT NULL AND locked_until <= :now THEN 1
                       ELSE failed_login_count + 1 END,
                   locked_until = CASE
                       WHEN locked_until IS NOT NULL AND locked_until <= :now THEN NULL
                       WHEN failed_login_count + 1 >= :maxFailures THEN :lockUntil
                       ELSE locked_until END,
                   updated_at = :now
             WHERE id = :id
            RETURNING failed_login_count, locked_until
            """;

    static final String RESET_LOGIN_FAILURES = """
            UPDATE users
               SET failed_login_count = 0, locked_until = NULL, updated_at = :now
             WHERE id = :id AND (failed_login_count <> 0 OR locked_until IS NOT NULL)
            """;

    private final JdbcClient jdbc;

    UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    UUID insert(String email, String displayName, String passwordHash) {
        return jdbc.sql(INSERT_USER)
                .param("email", email)
                .param("displayName", displayName)
                .param("passwordHash", passwordHash)
                .query(UUID.class)
                .single();
    }

    Optional<UserAccount> findByEmail(String email) {
        return jdbc.sql(FIND_USER_BY_EMAIL).param("email", email).query(UserRepository::map).optional();
    }

    Optional<UserAccount> findById(UUID id) {
        return jdbc.sql(FIND_USER_BY_ID).param("id", id).query(UserRepository::map).optional();
    }

    void recordFailure(UUID id, Instant now, int maxFailures, Instant lockUntil) {
        jdbc.sql(RECORD_LOGIN_FAILURE)
                .param("id", id)
                .param("now", Db.ts(now))
                .param("maxFailures", maxFailures)
                .param("lockUntil", Db.ts(lockUntil))
                .query((rs, n) -> rs.getInt("failed_login_count"))
                .single();
    }

    void resetFailures(UUID id, Instant now) {
        jdbc.sql(RESET_LOGIN_FAILURES).param("id", id).param("now", Db.ts(now)).update();
    }

    private static UserAccount map(ResultSet rs, int rowNum) throws SQLException {
        return new UserAccount(
                rs.getObject("id", UUID.class),
                rs.getString("email"),
                rs.getString("display_name"),
                rs.getString("password_hash"),
                rs.getBoolean("platform_admin"),
                rs.getString("status"),
                rs.getInt("failed_login_count"),
                Db.instant(rs, "locked_until"));
    }
}
