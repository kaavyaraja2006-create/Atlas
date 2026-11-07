-- =====================================================================
-- V15 (Graph Intelligence, step 2): record-level results of every
-- simulated recovery strategy, and immutable pairwise comparisons.
--
-- Counts are exact; details are capped (5,000 records per strategy and per
-- comparison by default, chosen by priority) and every row says how many
-- were stored, how many were omitted and whether the list is partial.
--
-- Only identifiers and field NAMES are stored, never field values: values
-- stay in change_events, behind the audit permissions.
--
-- Rows are immutable once written (trigger); retention deletes them with
-- their run (ON DELETE CASCADE from simulation_runs).
-- Runs simulated before V15 keep NULL in the new columns: no details.
-- =====================================================================

ALTER TABLE simulation_results
    ADD COLUMN records_total            integer CHECK (records_total >= 0),
    ADD COLUMN records_proposed         integer CHECK (records_proposed >= 0),
    ADD COLUMN records_preserved        integer CHECK (records_preserved >= 0),
    ADD COLUMN records_conflicting      integer CHECK (records_conflicting >= 0),
    ADD COLUMN records_unresolved       integer CHECK (records_unresolved >= 0),
    ADD COLUMN preserved_later_changes  integer CHECK (preserved_later_changes >= 0),
    ADD COLUMN unresolved_dependencies  integer CHECK (unresolved_dependencies >= 0),
    ADD COLUMN details_stored           integer CHECK (details_stored >= 0),
    ADD COLUMN details_truncated        boolean,
    ADD CONSTRAINT simulation_results_details_consistent CHECK (
        (records_total IS NULL AND details_stored IS NULL AND details_truncated IS NULL)
        OR (records_total IS NOT NULL AND details_stored IS NOT NULL AND details_truncated IS NOT NULL
            AND details_stored <= records_total AND details_truncated = (details_stored < records_total)));

CREATE TABLE simulation_result_records (
    organization_id  uuid          NOT NULL,
    run_id           uuid          NOT NULL,
    strategy         varchar(40)   NOT NULL,
    seq              integer       NOT NULL CHECK (seq >= 0),
    entity_type      varchar(64)   NOT NULL,
    entity_id        uuid          NOT NULL,
    action           varchar(12)   NOT NULL
                                   CHECK (action IN ('DELETE', 'MODIFY', 'COMPENSATE', 'REVIEW', 'HELD', 'NONE')),
    inspected        boolean       NOT NULL,
    proposed         boolean       NOT NULL,
    modified         boolean       NOT NULL,
    preserved        boolean       NOT NULL,
    conflicting      boolean       NOT NULL,
    unresolved       boolean       NOT NULL,
    fields           text[]        NOT NULL DEFAULT '{}',
    provenance_path  jsonb         NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(provenance_path) = 'array'),
    conflict_kind    varchar(40),
    PRIMARY KEY (run_id, strategy, seq),
    UNIQUE (run_id, strategy, entity_type, entity_id),
    FOREIGN KEY (organization_id, run_id) REFERENCES simulation_runs (organization_id, id) ON DELETE CASCADE,
    FOREIGN KEY (run_id, strategy) REFERENCES simulation_results (run_id, strategy) ON DELETE CASCADE,
    CHECK (NOT modified OR action IN ('DELETE', 'MODIFY')),
    CHECK (conflicting = (conflict_kind IS NOT NULL))
);

CREATE INDEX ix_simulation_records_org ON simulation_result_records (organization_id, run_id, strategy, seq);

CREATE TABLE simulation_comparisons (
    organization_id     uuid         NOT NULL,
    run_id              uuid         NOT NULL,
    strategy_a          varchar(40)  NOT NULL,
    strategy_b          varchar(40)  NOT NULL,
    only_a              integer      NOT NULL CHECK (only_a >= 0),
    only_b              integer      NOT NULL CHECK (only_b >= 0),
    both_strategies     integer      NOT NULL CHECK (both_strategies >= 0),
    same_action         integer      NOT NULL CHECK (same_action >= 0),
    total_differences   integer      NOT NULL CHECK (total_differences >= 0),
    stored_differences  integer      NOT NULL CHECK (stored_differences >= 0),
    truncated           boolean      NOT NULL,
    sha256              char(64)     NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    differences         jsonb        NOT NULL CHECK (jsonb_typeof(differences) = 'array'),
    created_at          timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, strategy_a, strategy_b),
    FOREIGN KEY (organization_id, run_id) REFERENCES simulation_runs (organization_id, id) ON DELETE CASCADE,
    CHECK (strategy_a <> strategy_b),
    CHECK (same_action <= both_strategies),
    CHECK (total_differences = only_a + only_b + both_strategies - same_action),
    CHECK (stored_differences <= total_differences AND truncated = (stored_differences < total_differences)),
    CHECK (jsonb_array_length(differences) = stored_differences)
);

CREATE INDEX ix_simulation_comparisons_org ON simulation_comparisons (organization_id, run_id);

-- Written once, never edited. (DELETE stays possible: retention removes whole runs.)
CREATE FUNCTION simulation_artifact_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% rows are immutable', TG_TABLE_NAME;
END;
$$;

CREATE TRIGGER simulation_results_immutable BEFORE UPDATE ON simulation_results
    FOR EACH ROW EXECUTE FUNCTION simulation_artifact_immutable();
CREATE TRIGGER simulation_result_records_immutable BEFORE UPDATE ON simulation_result_records
    FOR EACH ROW EXECUTE FUNCTION simulation_artifact_immutable();
CREATE TRIGGER simulation_comparisons_immutable BEFORE UPDATE ON simulation_comparisons
    FOR EACH ROW EXECUTE FUNCTION simulation_artifact_immutable();
