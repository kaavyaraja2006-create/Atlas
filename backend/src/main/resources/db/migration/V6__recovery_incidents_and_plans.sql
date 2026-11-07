-- =====================================================================
-- V6: Recovery incidents and plans (Phase 5)
--
-- An incident names ONE change event that was wrong. Plans are generated
-- from a fresh analysis each time and are immutable once stored: only their
-- status moves (PROPOSED -> APPROVED/REJECTED/SUPERSEDED, APPROVED ->
-- EXECUTED/SUPERSEDED). The fingerprint identifies exactly which record
-- versions the plan was computed from; Phase 6 refuses to execute a plan
-- whose fingerprint no longer matches the data.
-- =====================================================================

ALTER TABLE document_counters DROP CONSTRAINT document_counters_kind_check;
ALTER TABLE document_counters ADD CONSTRAINT document_counters_kind_check
    CHECK (kind IN ('ORDER', 'INVOICE', 'CREDIT_NOTE', 'INCIDENT'));

-- Lets other tables reference a change event within the same tenant.
ALTER TABLE change_events ADD CONSTRAINT change_events_org_id_key UNIQUE (organization_id, id);

CREATE TABLE recovery_incidents (
    id                     uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id        uuid          NOT NULL REFERENCES organizations (id),
    incident_number        varchar(32)   NOT NULL,
    faulty_change_event_id uuid          NOT NULL,
    title                  varchar(200)  NOT NULL CHECK (length(trim(title)) > 0),
    reason                 varchar(1000) NOT NULL CHECK (length(trim(reason)) > 0),
    status                 varchar(16)   NOT NULL DEFAULT 'OPEN'
                           CHECK (status IN ('OPEN', 'PLANNED', 'APPROVED', 'EXECUTING', 'RESOLVED', 'CANCELLED')),
    opened_by              uuid          NOT NULL REFERENCES users (id),
    opened_at              timestamptz   NOT NULL DEFAULT now(),
    updated_at             timestamptz   NOT NULL DEFAULT now(),
    version                bigint        NOT NULL DEFAULT 0,
    UNIQUE (organization_id, incident_number),
    UNIQUE (organization_id, id),
    FOREIGN KEY (organization_id, faulty_change_event_id) REFERENCES change_events (organization_id, id)
);
-- One active incident per faulty change.
CREATE UNIQUE INDEX ux_incidents_active_per_change
    ON recovery_incidents (organization_id, faulty_change_event_id)
    WHERE status NOT IN ('RESOLVED', 'CANCELLED');
CREATE INDEX ix_incidents_org_opened ON recovery_incidents (organization_id, opened_at DESC);

CREATE TABLE recovery_plans (
    id                uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id   uuid        NOT NULL,
    incident_id       uuid        NOT NULL,
    plan_number       integer     NOT NULL CHECK (plan_number > 0),
    fingerprint       varchar(80) NOT NULL CHECK (fingerprint ~ '^sha256:[0-9a-f]{64}$'),
    risk              varchar(8)  NOT NULL CHECK (risk IN ('LOW', 'MEDIUM', 'HIGH')),
    requires_approval boolean     NOT NULL,
    step_count        integer     NOT NULL CHECK (step_count >= 0),
    manual_step_count integer     NOT NULL CHECK (manual_step_count >= 0 AND manual_step_count <= step_count),
    impact            jsonb       NOT NULL,
    plan              jsonb       NOT NULL,
    status            varchar(16) NOT NULL DEFAULT 'PROPOSED'
                      CHECK (status IN ('PROPOSED', 'SUPERSEDED', 'APPROVED', 'REJECTED', 'EXECUTED')),
    created_by        uuid        NOT NULL REFERENCES users (id),
    created_at        timestamptz NOT NULL DEFAULT now(),
    UNIQUE (incident_id, plan_number),
    UNIQUE (organization_id, id),
    FOREIGN KEY (organization_id, incident_id) REFERENCES recovery_incidents (organization_id, id)
);
-- At most one plan awaiting a decision per incident.
CREATE UNIQUE INDEX ux_plans_one_proposed ON recovery_plans (incident_id) WHERE status = 'PROPOSED';

CREATE FUNCTION trg_recovery_plans_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'recovery plans are kept forever' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF (NEW.organization_id, NEW.incident_id, NEW.plan_number, NEW.fingerprint, NEW.risk, NEW.requires_approval,
        NEW.step_count, NEW.manual_step_count, NEW.impact, NEW.plan, NEW.created_by, NEW.created_at)
       IS DISTINCT FROM
       (OLD.organization_id, OLD.incident_id, OLD.plan_number, OLD.fingerprint, OLD.risk, OLD.requires_approval,
        OLD.step_count, OLD.manual_step_count, OLD.impact, OLD.plan, OLD.created_by, OLD.created_at) THEN
        RAISE EXCEPTION 'recovery plan % is immutable; generate a new plan instead', OLD.id
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status AND NOT (
           (OLD.status = 'PROPOSED' AND NEW.status IN ('APPROVED', 'REJECTED', 'SUPERSEDED'))
        OR (OLD.status = 'APPROVED' AND NEW.status IN ('EXECUTED', 'SUPERSEDED'))) THEN
        RAISE EXCEPTION 'recovery plan status cannot go from % to %', OLD.status, NEW.status
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER recovery_plans_guard
    BEFORE UPDATE OR DELETE ON recovery_plans
    FOR EACH ROW EXECUTE FUNCTION trg_recovery_plans_guard();
