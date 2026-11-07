package com.atlas.identity;

import com.atlas.common.error.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Registration, login, refresh-token rotation and logout.
 *
 * <p>Transactions are explicit (TransactionTemplate) rather than
 * {@code @Transactional}: a failed login must still COMMIT its failure count
 * and login event before the 401 is thrown. With a declarative transaction,
 * throwing the 401 would roll back the evidence and make lockout useless.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final String INVALID_LOGIN =
            "Invalid email or password, or the account is temporarily locked.";
    private static final String SESSION_ENDED = "Your session has ended. Sign in again.";

    /** Tokens handed to the client after login or refresh. */
    public record Session(UUID userId, String accessToken, Instant accessTokenExpiresAt,
                          String refreshToken, Instant refreshTokenExpiresAt) {
    }

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final LoginEventRepository loginEvents;
    private final PasswordEncoder passwordEncoder;
    private final AccessTokenIssuer accessTokens;
    private final AuthProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final String timingDummyHash;

    AuthService(UserRepository users, RefreshTokenRepository refreshTokens, LoginEventRepository loginEvents,
                PasswordEncoder passwordEncoder, AccessTokenIssuer accessTokens, AuthProperties properties,
                Clock clock, TransactionTemplate tx) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.loginEvents = loginEvents;
        this.passwordEncoder = passwordEncoder;
        this.accessTokens = accessTokens;
        this.properties = properties;
        this.clock = clock;
        this.tx = tx;
        // Checked against when the email is unknown, so "no such user" takes as
        // long as "wrong password" and response time does not reveal accounts.
        this.timingDummyHash = passwordEncoder.encode("timing-equaliser-" + UUID.randomUUID());
    }

    public UUID register(String email, String displayName, String password) {
        List<String> problems = PasswordPolicy.violations(password, email);
        if (!problems.isEmpty()) {
            throw ApiException.validation(problems);
        }
        String hash = passwordEncoder.encode(password);
        try {
            return tx.execute(status -> users.insert(email.trim(), displayName.trim(), hash));
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("An account with this email already exists.");
        }
    }

    public Session login(String email, String password, ClientInfo client) {
        Instant now = Instant.now(clock);
        Optional<UserAccount> found = users.findByEmail(email);
        // The slow BCrypt check runs before the transaction, so no locks are held during it.
        boolean passwordMatches = passwordEncoder.matches(password,
                found.map(UserAccount::passwordHash).orElse(timingDummyHash));

        Session session = tx.execute(status -> decideLogin(found, passwordMatches, email, client, now));
        if (session == null) {
            throw ApiException.unauthorized(INVALID_LOGIN);
        }
        return session;
    }

    private Session decideLogin(Optional<UserAccount> found, boolean passwordMatches, String email,
                                ClientInfo client, Instant now) {
        if (found.isEmpty()) {
            loginEvents.insert(null, email, "BAD_CREDENTIALS", client);
            return null;
        }
        UserAccount user = found.get();
        if (!user.active()) {
            loginEvents.insert(user.id(), email, "LOCKED".equals(user.status()) ? "LOCKED" : "DISABLED", client);
            return null;
        }
        if (user.lockedAt(now)) {
            loginEvents.insert(user.id(), email, "LOCKED", client);
            return null;
        }
        if (!passwordMatches) {
            users.recordFailure(user.id(), now, properties.maxFailedLogins(), now.plus(properties.lockDuration()));
            loginEvents.insert(user.id(), email, "BAD_CREDENTIALS", client);
            return null;
        }
        users.resetFailures(user.id(), now);
        loginEvents.insert(user.id(), email, "SUCCESS", client);
        return startSession(user, UUID.randomUUID(), client, now);
    }

    /**
     * Exchanges a refresh token for a new pair. Each token works once. If a
     * token that was already exchanged comes back, someone holds a copy, so
     * the whole token family (every session descended from that login) is
     * revoked.
     */
    public Session refresh(String rawToken, ClientInfo client) {
        if (rawToken == null || rawToken.isBlank()) {
            throw ApiException.unauthorized(SESSION_ENDED);
        }
        Instant now = Instant.now(clock);
        Session session = tx.execute(status -> rotate(rawToken, client, now));
        if (session == null) {
            throw ApiException.unauthorized(SESSION_ENDED);
        }
        return session;
    }

    private Session rotate(String rawToken, ClientInfo client, Instant now) {
        Optional<RefreshTokenRepository.StoredToken> found = refreshTokens.findByHash(Tokens.sha256(rawToken));
        if (found.isEmpty()) {
            return null;
        }
        RefreshTokenRepository.StoredToken token = found.get();
        if (token.revokedAt() != null) {
            return null;
        }
        if (token.rotatedAt() != null) {
            return reuseDetected(token);
        }
        if (!token.expiresAt().isAfter(now)) {
            return null;
        }
        // Two requests racing with the same token: only one can rotate it.
        // The loser is treated as reuse, which is the safe interpretation.
        if (!refreshTokens.markRotated(token.id(), now)) {
            return reuseDetected(token);
        }
        Optional<UserAccount> user = users.findById(token.userId());
        if (user.isEmpty() || !user.get().active()) {
            refreshTokens.revokeFamily(token.familyId(), "ADMIN");
            return null;
        }
        return startSession(user.get(), token.familyId(), client, now);
    }

    private Session reuseDetected(RefreshTokenRepository.StoredToken token) {
        int revoked = refreshTokens.revokeFamily(token.familyId(), "REUSE_DETECTED");
        log.warn("Refresh token reuse detected for user {}; revoked {} token(s) in family {}",
                token.userId(), revoked, token.familyId());
        return null;
    }

    public void logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        tx.executeWithoutResult(status -> refreshTokens.findByHash(Tokens.sha256(rawToken))
                .ifPresent(token -> refreshTokens.revokeFamily(token.familyId(), "LOGOUT")));
    }

    Optional<UserAccount> findUser(UUID id) {
        return users.findById(id);
    }

    private Session startSession(UserAccount user, UUID familyId, ClientInfo client, Instant now) {
        String refreshToken = Tokens.newOpaqueToken();
        Instant refreshExpiresAt = now.plus(properties.refreshTokenTtl());
        refreshTokens.insert(user.id(), familyId, Tokens.sha256(refreshToken), now, refreshExpiresAt,
                client.userAgent());
        AccessTokenIssuer.IssuedToken access = accessTokens.issue(user);
        return new Session(user.id(), access.value(), access.expiresAt(), refreshToken, refreshExpiresAt);
    }
}
