-- =====================================================================
-- V11 (ATLAS X, Milestone 3): a person-editable order field, and human
-- review decisions on recovery conflicts.
-- =====================================================================

-- Editable on DRAFT and CONFIRMED orders. A real "different field" a person
-- can change after an order consumed a faulty price: recovery must preserve it.
ALTER TABLE orders ADD COLUMN delivery_note varchar(500);

-- Conflicts themselves are COMPUTED from the audit history on every analysis
-- (recovery/engine/ConflictAnalyzer); only the human decision about one is
-- stored. Append-only: a changed mind is a new row, the latest one counts.
CREATE TABLE recovery_conflict_reviews (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid        NOT NULL,
    incident_id     uuid        NOT NULL,
    entity_type     varchar(64) NOT NULL,
    entity_id       uuid        NOT NULL,
    field           varchar(64) NOT NULL,
    kind            varchar(40) NOT NULL CHECK (kind IN ('NO_CONFLICT', 'SAME_FIELD_CONFLICT', 'DIFFERENT_FIELD_UPDATE',
                        'SOURCE_VERSION_MISMATCH', 'CONCURRENT_MODIFICATION', 'UNKNOWN_CHANGE_ORIGIN',
                        'REQUIRES_MANUAL_REVIEW')),
    decision        varchar(32) NOT NULL CHECK (decision IN ('KEEP_LATER_CHANGE', 'RECOVERY_MAY_OVERWRITE',
                        'NEEDS_INVESTIGATION')),
    note            varchar(1000) NOT NULL CHECK (length(btrim(note)) >= 3),
    reviewed_by     uuid        NOT NULL REFERENCES users (id),
    reviewed_at     timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (organization_id, incident_id) REFERENCES recovery_incidents (organization_id, id)
);

CREATE INDEX ix_conflict_reviews_lookup
    ON recovery_conflict_reviews (organization_id, incident_id, entity_type, entity_id, field, reviewed_at DESC);

CREATE TRIGGER recovery_conflict_reviews_append_only
    BEFORE UPDATE OR DELETE ON recovery_conflict_reviews
    FOR EACH ROW EXECUTE FUNCTION trg_reject_mutation();
