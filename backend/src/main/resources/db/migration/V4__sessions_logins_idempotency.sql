-- =====================================================================
-- V4: Authentication sessions, login history, idempotency keys (Phase 2)
--
-- refresh_tokens   : only a SHA-256 of each token is stored. Tokens rotate
--                    on every refresh; each belongs to a "family" so that
--                    reuse of an already-rotated token (a stolen token) can
--                    revoke the whole family in one statement.
-- login_events     : append-only login history and lockout evidence.
-- idempotency_keys : (organization, key) -> first response, so a client
--                    retry of a POST replays the result instead of
--                    repeating the side effect.
-- =====================================================================

CREATE TABLE refresh_tokens (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       uuid        NOT NULL REFERENCES users (id),
    family_id     uuid        NOT NULL,
    token_hash    bytea       NOT NULL UNIQUE CHECK (octet_length(token_hash) = 32),
    issued_at     timestamptz NOT NULL DEFAULT now(),
    expires_at    timestamptz NOT NULL,
    rotated_at    timestamptz,                 -- set when exchanged for a successor
    revoked_at    timestamptz,                 -- set on logout or reuse detection
    revoke_reason varchar(32)
                  CHECK (revoke_reason IN ('LOGOUT', 'REUSE_DETECTED', 'PASSWORD_CHANGED', 'ADMIN')),
    user_agent    varchar(300),
    CHECK (expires_at > issued_at),
    CHECK ((revoked_at IS NULL) = (revoke_reason IS NULL))
);
CREATE INDEX ix_refresh_tokens_user   ON refresh_tokens (user_id) WHERE revoked_at IS NULL;
CREATE INDEX ix_refresh_tokens_family ON refresh_tokens (family_id);
-- At most one usable (not rotated, not revoked) token per family.
CREATE UNIQUE INDEX ux_refresh_tokens_one_live_per_family
    ON refresh_tokens (family_id) WHERE rotated_at IS NULL AND revoked_at IS NULL;

CREATE TABLE login_events (
    id              bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id         uuid         REFERENCES users (id),   -- null when the email matched no user
    email_attempted varchar(320) NOT NULL,
    outcome         varchar(24)  NOT NULL
                    CHECK (outcome IN ('SUCCESS', 'BAD_CREDENTIALS', 'LOCKED', 'DISABLED')),
    ip_address      inet,
    user_agent      varchar(300),
    occurred_at     timestamptz  NOT NULL DEFAULT now(),
    CHECK (outcome = 'BAD_CREDENTIALS' OR user_id IS NOT NULL)
);
CREATE INDEX ix_login_events_user_time ON login_events (user_id, occurred_at DESC);
CREATE INDEX ix_login_events_ip_time   ON login_events (ip_address, occurred_at DESC);

CREATE TRIGGER login_events_append_only
    BEFORE UPDATE OR DELETE ON login_events
    FOR EACH ROW EXECUTE FUNCTION trg_reject_mutation();

CREATE TABLE idempotency_keys (
    organization_id  uuid         NOT NULL REFERENCES organizations (id),
    idempotency_key  varchar(128) NOT NULL CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{8,128}$'),
    user_id          uuid         NOT NULL REFERENCES users (id),
    method           varchar(8)   NOT NULL,
    path             varchar(300) NOT NULL,
    request_hash     bytea        NOT NULL CHECK (octet_length(request_hash) = 32),
    status           varchar(16)  NOT NULL DEFAULT 'IN_PROGRESS'
                     CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    response_status  integer,
    response_body    jsonb,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    completed_at     timestamptz,
    PRIMARY KEY (organization_id, idempotency_key),
    CHECK (status = 'IN_PROGRESS' OR (response_status IS NOT NULL AND completed_at IS NOT NULL))
);
CREATE INDEX ix_idempotency_created ON idempotency_keys (created_at);   -- for expiry sweeps

-- Revokes every token in a family; used on logout and when a rotated
-- token is presented again. Returns the number of tokens revoked.
CREATE FUNCTION revoke_token_family(p_family uuid, p_reason varchar) RETURNS integer
LANGUAGE sql AS $$
    WITH r AS (
        UPDATE refresh_tokens
           SET revoked_at = now(), revoke_reason = p_reason
         WHERE family_id = p_family AND revoked_at IS NULL
        RETURNING 1)
    SELECT count(*)::integer FROM r
$$;
