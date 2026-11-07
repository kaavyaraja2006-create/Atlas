-- =====================================================================
-- V12 (ATLAS X, Milestones 4-5): counterfactual simulation runs, their
-- per-strategy results and the optimiser's assessment.
--
-- No snapshot DATA is stored: a run records the audit-chain position it
-- read (snapshot_chain_index) and the fingerprint of the records it read,
-- so it can be reproduced from the append-only history instead of from a
-- second copy of production data.
-- Retention: runs older than atlas.simulation.retention-days (default 180)
-- are deleted with their results; they are operational evidence, not audit
-- history (decisions and executions live in the recovery tables).
-- =====================================================================

CREATE TABLE simulation_runs (
    id                    uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id       uuid          NOT NULL,
    incident_id           uuid          NOT NULL,
    status                varchar(12)   NOT NULL DEFAULT 'RUNNING'
                                        CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    snapshot_chain_index  bigint,
    fingerprint           varchar(80),
    records               integer,
    versions              integer,
    recommended_strategy  varchar(40),
    risk_level            varchar(10)   CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    assessment            jsonb,
    failure               varchar(1000),
    started_by            uuid          NOT NULL REFERENCES users (id),
    started_at            timestamptz   NOT NULL DEFAULT now(),
    finished_at           timestamptz,
    duration_ms           integer,
    UNIQUE (organization_id, id),
    FOREIGN KEY (organization_id, incident_id) REFERENCES recovery_incidents (organization_id, id),
    CHECK ((status = 'COMPLETED') = (assessment IS NOT NULL AND finished_at IS NOT NULL)),
    CHECK (status <> 'FAILED' OR failure IS NOT NULL)
);

-- One simulation at a time per incident.
CREATE UNIQUE INDEX ux_simulation_running ON simulation_runs (organization_id, incident_id) WHERE status = 'RUNNING';
CREATE INDEX ix_simulation_runs_incident ON simulation_runs (organization_id, incident_id, started_at DESC);
CREATE INDEX ix_simulation_runs_age ON simulation_runs (started_at);

CREATE TABLE simulation_results (
    organization_id         uuid         NOT NULL,
    run_id                  uuid         NOT NULL,
    strategy                varchar(40)  NOT NULL,
    letter                  char(1)      NOT NULL,
    applicable              boolean      NOT NULL,
    not_applicable_reason   varchar(500),
    records_inspected       integer      NOT NULL CHECK (records_inspected >= 0),
    records_modified        integer      NOT NULL CHECK (records_modified >= 0),
    records_deleted         integer      NOT NULL CHECK (records_deleted >= 0),
    faulty_records          integer      NOT NULL CHECK (faulty_records >= 0),
    repaired                integer      NOT NULL CHECK (repaired >= 0),
    collateral_modified     integer      NOT NULL CHECK (collateral_modified >= 0),
    legitimate_overwritten  integer      NOT NULL CHECK (legitimate_overwritten >= 0),
    conflicts_surfaced      integer      NOT NULL CHECK (conflicts_surfaced >= 0),
    conflicts_ignored       integer      NOT NULL CHECK (conflicts_ignored >= 0),
    invariant_violations    integer      NOT NULL CHECK (invariant_violations >= 0),
    field_writes            integer      NOT NULL CHECK (field_writes >= 0),
    compensations           integer      NOT NULL CHECK (compensations >= 0),
    manual_steps            integer      NOT NULL CHECK (manual_steps >= 0),
    simulation_micros       bigint       NOT NULL CHECK (simulation_micros >= 0),
    violations              jsonb        NOT NULL DEFAULT '[]',
    notes                   jsonb        NOT NULL DEFAULT '[]',
    PRIMARY KEY (run_id, strategy),
    FOREIGN KEY (organization_id, run_id) REFERENCES simulation_runs (organization_id, id) ON DELETE CASCADE,
    CHECK (repaired <= faulty_records)
);
