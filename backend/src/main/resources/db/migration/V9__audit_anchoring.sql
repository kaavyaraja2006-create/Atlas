-- =====================================================================
-- V9: External anchoring of the audit chain (Phase 8)
--
-- verify_audit_chain() proves the chain is internally consistent. It cannot
-- prove the chain is the ORIGINAL one: anyone who can bypass the triggers (a
-- database superuser) can edit an old event and recompute every later hash.
--
-- Anchors close that gap. The application periodically signs (organization,
-- chain index, hash) with a key the database never sees and appends it to a
-- store outside the database. Verification recomputes the chain from event
-- CONTENT between consecutive anchors and compares the result with the signed
-- hash. A rewrite before the latest anchor cannot match; only events after the
-- latest anchor are exposed (bounded by the anchoring interval).
-- =====================================================================

-- Recomputes the chain from content, from a trusted point (p_after, p_prev_hash)
-- up to p_upto, ignoring the stored hash columns except to report where they
-- first disagree with the recomputation.
--   last_index      the last contiguous index reached (< p_upto means events are missing)
--   last_hash       the hash recomputed from content at last_index
--   first_mismatch  first index whose stored prev_hash/hash disagree with the recomputation
CREATE FUNCTION recompute_audit_chain(p_org uuid, p_after bigint, p_prev_hash bytea, p_upto bigint,
                                      OUT last_index bigint, OUT last_hash bytea, OUT first_mismatch bigint)
LANGUAGE plpgsql STABLE AS $$
DECLARE
    e        change_events;
    v_prev   bytea  := p_prev_hash;
    v_expect bigint := p_after + 1;
BEGIN
    first_mismatch := NULL;
    FOR e IN SELECT * FROM change_events
              WHERE organization_id = p_org AND chain_index > p_after AND chain_index <= p_upto
              ORDER BY chain_index
    LOOP
        EXIT WHEN e.chain_index <> v_expect;          -- a gap: stop; last_index shows where
        IF first_mismatch IS NULL AND e.prev_hash <> v_prev THEN
            first_mismatch := e.chain_index;
        END IF;
        v_prev := sha256(v_prev || change_event_payload(e));
        IF first_mismatch IS NULL AND e.hash <> v_prev THEN
            first_mismatch := e.chain_index;
        END IF;
        v_expect := v_expect + 1;
    END LOOP;
    last_index := v_expect - 1;
    last_hash  := v_prev;
END $$;

-- The application's own record of what it published. A convenience for listing:
-- verification trusts only the signed copies in the external store, because this
-- table can be rewritten by the same person who could rewrite the chain.
CREATE TABLE audit_anchors (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid        NOT NULL REFERENCES organizations (id),
    chain_index     bigint      NOT NULL CHECK (chain_index > 0),
    hash            bytea       NOT NULL CHECK (length(hash) = 32),
    key_id          varchar(64) NOT NULL,
    signature       varchar(200) NOT NULL,
    sink            varchar(200) NOT NULL,
    anchored_at     timestamptz NOT NULL,
    UNIQUE (organization_id, chain_index)
);

CREATE TRIGGER audit_anchors_append_only
    BEFORE UPDATE OR DELETE ON audit_anchors
    FOR EACH ROW EXECUTE FUNCTION trg_reject_mutation();
