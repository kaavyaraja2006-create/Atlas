-- =====================================================================
-- V3: Change capture, tamper-evident audit chain, provenance graph
--
-- change_events   : one row per (entity, version). Holds the full
--                   before/after snapshot, so the state of any record at
--                   any version can be reconstructed. (Replaces a
--                   separate record_versions table, see ADR-0005.)
-- audit_chain_heads: per-organization hash-chain head. The chain is
--                   computed by the database, not the application.
-- provenance_edges: directed "this version of X was computed from that
--                   version of Y" facts, written in the SAME transaction
--                   as the derived write (ADR-0003). Composite FKs force
--                   both endpoints to be real, recorded versions.
-- =====================================================================

CREATE TABLE audit_chain_heads (
    organization_id uuid    PRIMARY KEY REFERENCES organizations (id),
    last_index      bigint  NOT NULL DEFAULT 0,
    last_hash       bytea   NOT NULL DEFAULT '\x0000000000000000000000000000000000000000000000000000000000000000'::bytea
);

CREATE TABLE change_events (
    id               uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  uuid         NOT NULL REFERENCES organizations (id),
    chain_index      bigint       NOT NULL,      -- set by trigger
    txn_id           uuid         NOT NULL,      -- one business command = one txn_id
    actor_kind       varchar(16)  NOT NULL CHECK (actor_kind IN ('USER', 'SYSTEM', 'RECOVERY')),
    actor_user_id    uuid         REFERENCES users (id),
    entity_type      varchar(64)  NOT NULL,
    entity_id        uuid         NOT NULL,
    entity_version   bigint       NOT NULL CHECK (entity_version >= 0),
    operation        varchar(16)  NOT NULL
                     CHECK (operation IN ('INSERT', 'UPDATE', 'DELETE', 'COMPENSATE')),
    changed_fields   text[]       NOT NULL DEFAULT '{}',
    before_state     jsonb,
    after_state      jsonb,
    reason           varchar(500),
    correlation_id   varchar(64),
    occurred_at      timestamptz  NOT NULL DEFAULT now(),
    prev_hash        bytea        NOT NULL,      -- set by trigger
    hash             bytea        NOT NULL,      -- set by trigger
    UNIQUE (organization_id, chain_index),
    UNIQUE (organization_id, entity_type, entity_id, entity_version),
    CHECK (actor_kind <> 'USER' OR actor_user_id IS NOT NULL),
    CHECK (operation = 'INSERT' OR before_state IS NOT NULL),
    CHECK (operation = 'DELETE' OR after_state  IS NOT NULL)
);
CREATE INDEX ix_change_events_org_time  ON change_events (organization_id, occurred_at DESC);
CREATE INDEX ix_change_events_txn       ON change_events (organization_id, txn_id);
CREATE INDEX ix_change_events_actor     ON change_events (organization_id, actor_user_id, occurred_at DESC);

-- Canonical byte string that the hash covers. jsonb::text is already
-- normalised (sorted keys, no duplicate keys), so it is deterministic.
CREATE FUNCTION change_event_payload(e change_events) RETURNS bytea
LANGUAGE sql IMMUTABLE AS $$
    SELECT convert_to(concat_ws('|',
        e.organization_id::text, e.chain_index::text, e.txn_id::text,
        e.actor_kind, coalesce(e.actor_user_id::text, '-'),
        e.entity_type, e.entity_id::text, e.entity_version::text, e.operation,
        array_to_string(e.changed_fields, ','),
        coalesce(e.before_state::text, '-'), coalesce(e.after_state::text, '-'),
        coalesce(e.reason, '-'), coalesce(e.correlation_id, '-'),
        (extract(epoch FROM e.occurred_at) * 1000000)::bigint::text
    ), 'UTF8')
$$;

CREATE FUNCTION trg_change_events_chain() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_index bigint;
    v_prev  bytea;
BEGIN
    INSERT INTO audit_chain_heads (organization_id) VALUES (NEW.organization_id)
        ON CONFLICT (organization_id) DO NOTHING;

    -- Row lock serialises appends per organization, so chain order is
    -- well-defined even under concurrent writers.
    SELECT last_index, last_hash INTO v_index, v_prev
      FROM audit_chain_heads WHERE organization_id = NEW.organization_id
       FOR UPDATE;

    NEW.chain_index := v_index + 1;
    NEW.prev_hash   := v_prev;
    NEW.hash        := sha256(v_prev || change_event_payload(NEW));

    UPDATE audit_chain_heads
       SET last_index = NEW.chain_index, last_hash = NEW.hash
     WHERE organization_id = NEW.organization_id;
    RETURN NEW;
END $$;

CREATE TRIGGER change_events_chain
    BEFORE INSERT ON change_events
    FOR EACH ROW EXECUTE FUNCTION trg_change_events_chain();

CREATE FUNCTION trg_reject_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only; % is not permitted', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'insufficient_privilege';
END $$;

CREATE TRIGGER change_events_append_only
    BEFORE UPDATE OR DELETE ON change_events
    FOR EACH ROW EXECUTE FUNCTION trg_reject_mutation();
CREATE TRIGGER change_events_no_truncate
    BEFORE TRUNCATE ON change_events
    FOR EACH STATEMENT EXECUTE FUNCTION trg_reject_mutation();

-- Recomputes the chain for one organization. Returns the chain_index of
-- the first event whose stored hash or link does not match, or NULL if
-- the whole chain verifies. Detects edits made by anyone able to bypass
-- the triggers, unless they also rewrite every later hash AND the head;
-- anchoring the head externally (Phase 8) closes that gap.
CREATE FUNCTION verify_audit_chain(p_org uuid) RETURNS bigint
LANGUAGE plpgsql STABLE AS $$
DECLARE
    e        change_events;
    v_prev   bytea := '\x0000000000000000000000000000000000000000000000000000000000000000'::bytea;
    v_expect bigint := 1;
BEGIN
    FOR e IN SELECT * FROM change_events
              WHERE organization_id = p_org ORDER BY chain_index
    LOOP
        IF e.chain_index <> v_expect
           OR e.prev_hash <> v_prev
           OR e.hash <> sha256(v_prev || change_event_payload(e)) THEN
            RETURN e.chain_index;
        END IF;
        v_prev   := e.hash;
        v_expect := v_expect + 1;
    END LOOP;
    IF EXISTS (SELECT 1 FROM audit_chain_heads h
                WHERE h.organization_id = p_org
                  AND (h.last_index <> v_expect - 1 OR h.last_hash <> v_prev)) THEN
        RETURN v_expect;   -- head disagrees with the rows: truncation or rewrite
    END IF;
    RETURN NULL;
END $$;

-- ---------------------------------------------------------------------
-- Provenance graph
-- ---------------------------------------------------------------------
CREATE TABLE provenance_edges (
    id                    uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id       uuid        NOT NULL,
    txn_id                uuid        NOT NULL,
    source_entity_type    varchar(64) NOT NULL,
    source_entity_id      uuid        NOT NULL,
    source_entity_version bigint      NOT NULL,
    source_field          varchar(64),
    target_entity_type    varchar(64) NOT NULL,
    target_entity_id      uuid        NOT NULL,
    target_entity_version bigint      NOT NULL,
    target_field          varchar(64),
    relation              varchar(32) NOT NULL
                          CHECK (relation IN ('DERIVED_VALUE', 'AGGREGATED_INTO', 'COPIED_INTO')),
    -- How we know: written by the service at derivation time, or added
    -- later by a backfill job (never used for automatic recovery).
    evidence              varchar(32) NOT NULL
                          CHECK (evidence IN ('RECORDED_AT_WRITE', 'BACKFILLED')),
    recorded_at           timestamptz NOT NULL DEFAULT now(),
    CHECK (NOT (source_entity_type = target_entity_type
                AND source_entity_id = target_entity_id
                AND source_entity_version = target_entity_version)),
    -- Both endpoints must be versions that were actually recorded.
    -- DEFERRABLE so a service may write the edge before or after the
    -- change events inside the same transaction.
    FOREIGN KEY (organization_id, source_entity_type, source_entity_id, source_entity_version)
        REFERENCES change_events (organization_id, entity_type, entity_id, entity_version)
        DEFERRABLE INITIALLY DEFERRED,
    FOREIGN KEY (organization_id, target_entity_type, target_entity_id, target_entity_version)
        REFERENCES change_events (organization_id, entity_type, entity_id, entity_version)
        DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX ix_prov_source ON provenance_edges
    (organization_id, source_entity_type, source_entity_id, source_entity_version);
CREATE INDEX ix_prov_target ON provenance_edges
    (organization_id, target_entity_type, target_entity_id, target_entity_version);

CREATE TRIGGER provenance_edges_append_only
    BEFORE UPDATE OR DELETE ON provenance_edges
    FOR EACH ROW EXECUTE FUNCTION trg_reject_mutation();
CREATE TRIGGER provenance_edges_no_truncate
    BEFORE TRUNCATE ON provenance_edges
    FOR EACH STATEMENT EXECUTE FUNCTION trg_reject_mutation();
