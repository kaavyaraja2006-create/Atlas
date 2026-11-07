-- =====================================================================
-- V13 (ATLAS X, Milestone 6): continuous data-integrity monitoring.
--
-- Rules are per organization and versioned (every change is a new row in
-- integrity_rule_versions). A check run evaluates the enabled rules; each
-- finding opens an incident, or refreshes the open one for the same rule and
-- subject (never a duplicate). A finding that disappears is CLEARED by the next
-- run. Monitoring never changes business records: it only writes these tables.
-- =====================================================================

INSERT INTO permissions (code, description) VALUES
    ('integrity.read',   'View integrity rules, checks and incidents'),
    ('integrity.manage', 'Configure integrity rules, run checks and resolve integrity incidents');

INSERT INTO role_permissions (role_code, permission_code) VALUES
    ('ORGANIZATION_ADMIN', 'integrity.read'), ('ORGANIZATION_ADMIN', 'integrity.manage'),
    ('OPERATIONS_MANAGER', 'integrity.read'), ('OPERATIONS_MANAGER', 'integrity.manage'),
    ('AUDITOR', 'integrity.read'),
    ('RECOVERY_APPROVER', 'integrity.read');

CREATE TABLE integrity_rules (
    id              uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid         NOT NULL REFERENCES organizations (id),
    code            varchar(64)  NOT NULL CHECK (code ~ '^[A-Z][A-Z0-9_]*$'),
    kind            varchar(40)  NOT NULL CHECK (kind IN ('ORDER_TOTAL_MATCHES_LINES', 'INVOICE_TOTAL_MATCHES_LINES',
                                     'DERIVED_VALUE_MATCHES_SOURCE', 'REQUIRED_PROVENANCE', 'AUDIT_CHAIN_INTACT')),
    name            varchar(200) NOT NULL CHECK (length(btrim(name)) > 0),
    severity        varchar(10)  NOT NULL CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    enabled         boolean      NOT NULL DEFAULT true,
    config          jsonb        NOT NULL DEFAULT '{}' CHECK (jsonb_typeof(config) = 'object'),
    version         bigint       NOT NULL DEFAULT 0,
    created_by      uuid         NOT NULL REFERENCES users (id),
    created_at      timestamptz  NOT NULL DEFAULT now(),
    updated_at      timestamptz  NOT NULL DEFAULT now(),
    UNIQUE (organization_id, code),
    UNIQUE (organization_id, id)
);

CREATE TABLE integrity_rule_versions (
    organization_id uuid         NOT NULL,
    rule_id         uuid         NOT NULL,
    version         bigint       NOT NULL,
    kind            varchar(40)  NOT NULL,
    name            varchar(200) NOT NULL,
    severity        varchar(10)  NOT NULL,
    enabled         boolean      NOT NULL,
    config          jsonb        NOT NULL,
    changed_by      uuid         NOT NULL REFERENCES users (id),
    changed_at      timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (rule_id, version),
    FOREIGN KEY (organization_id, rule_id) REFERENCES integrity_rules (organization_id, id)
);

CREATE TRIGGER integrity_rule_versions_append_only
    BEFORE UPDATE OR DELETE ON integrity_rule_versions
    FOR EACH ROW EXECUTE FUNCTION trg_reject_mutation();

CREATE TABLE integrity_check_runs (
    id                uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   uuid         NOT NULL REFERENCES organizations (id),
    trigger           varchar(12)  NOT NULL CHECK (trigger IN ('SCHEDULED', 'MANUAL')),
    started_by        uuid         REFERENCES users (id),
    status            varchar(12)  NOT NULL DEFAULT 'RUNNING' CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    rules_evaluated   integer      NOT NULL DEFAULT 0,
    findings          integer      NOT NULL DEFAULT 0,
    opened            integer      NOT NULL DEFAULT 0,
    cleared           integer      NOT NULL DEFAULT 0,
    failure           varchar(1000),
    started_at        timestamptz  NOT NULL DEFAULT now(),
    finished_at       timestamptz,
    UNIQUE (organization_id, id),
    CHECK ((trigger = 'MANUAL') = (started_by IS NOT NULL))
);

CREATE INDEX ix_integrity_runs_org ON integrity_check_runs (organization_id, started_at DESC);

CREATE TABLE integrity_incidents (
    id               uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  uuid          NOT NULL,
    rule_id          uuid          NOT NULL,
    rule_version     bigint        NOT NULL,
    subject_type     varchar(64)   NOT NULL,
    subject_id       uuid          NOT NULL,
    severity         varchar(10)   NOT NULL CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    status           varchar(14)   NOT NULL DEFAULT 'OPEN'
                                   CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED', 'CLEARED')),
    title            varchar(300)  NOT NULL,
    detail           jsonb         NOT NULL DEFAULT '{}',
    occurrences      integer       NOT NULL DEFAULT 1 CHECK (occurrences > 0),
    first_seen_at    timestamptz   NOT NULL DEFAULT now(),
    last_seen_at     timestamptz   NOT NULL DEFAULT now(),
    first_run_id     uuid          NOT NULL,
    last_run_id      uuid          NOT NULL,
    closed_at        timestamptz,
    version          bigint        NOT NULL DEFAULT 0,
    UNIQUE (organization_id, id),
    FOREIGN KEY (organization_id, rule_id) REFERENCES integrity_rules (organization_id, id),
    FOREIGN KEY (organization_id, first_run_id) REFERENCES integrity_check_runs (organization_id, id),
    FOREIGN KEY (organization_id, last_run_id) REFERENCES integrity_check_runs (organization_id, id),
    CHECK ((status IN ('RESOLVED', 'CLEARED')) = (closed_at IS NOT NULL))
);

-- At most one open (or acknowledged) incident per rule and subject: a repeated finding refreshes it.
CREATE UNIQUE INDEX ux_integrity_incident_open
    ON integrity_incidents (organization_id, rule_id, subject_type, subject_id)
 WHERE status IN ('OPEN', 'ACKNOWLEDGED');
CREATE INDEX ix_integrity_incidents_list ON integrity_incidents (organization_id, status, severity, last_seen_at DESC);

-- Resolution history: every status change, by whom and why. Append-only.
CREATE TABLE integrity_incident_events (
    id               uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  uuid          NOT NULL,
    incident_id      uuid          NOT NULL,
    from_status      varchar(14),
    to_status        varchar(14)   NOT NULL,
    actor_user_id    uuid          REFERENCES users (id),
    note             varchar(1000),
    run_id           uuid,
    occurred_at      timestamptz   NOT NULL DEFAULT now(),
    FOREIGN KEY (organization_id, incident_id) REFERENCES integrity_incidents (organization_id, id)
);

CREATE INDEX ix_integrity_incident_events ON integrity_incident_events (organization_id, incident_id, occurred_at);

CREATE TRIGGER integrity_incident_events_append_only
    BEFORE UPDATE OR DELETE ON integrity_incident_events
    FOR EACH ROW EXECUTE FUNCTION trg_reject_mutation();
