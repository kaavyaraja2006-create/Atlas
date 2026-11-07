-- =====================================================================
-- V7: Recovery approval, execution and verification (Phase 6)
--
-- recovery_decisions     approvals and rejections, bound to the plan's fingerprint
-- recovery_actions       one row per executed step: its outcome, written once
-- recovery_verifications every verification run, appended
--
-- Rules enforced here as well as in the application:
--   * the author of a plan cannot approve it;
--   * a plan that needs approval cannot start executing without one;
--   * a step has one outcome; only a manual step's acknowledgement is added later;
--   * nothing in these tables is ever updated otherwise or deleted.
-- =====================================================================

-- Plans gain EXECUTING and FAILED. EXECUTED is reached only through EXECUTING.
ALTER TABLE recovery_plans DROP CONSTRAINT recovery_plans_status_check;
ALTER TABLE recovery_plans ADD CONSTRAINT recovery_plans_status_check
    CHECK (status IN ('PROPOSED', 'SUPERSEDED', 'APPROVED', 'REJECTED', 'EXECUTING', 'EXECUTED', 'FAILED'));

-- At most one execution in progress per incident.
CREATE UNIQUE INDEX ux_plans_one_executing ON recovery_plans (incident_id) WHERE status = 'EXECUTING';

CREATE TABLE recovery_decisions (
    id              uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid          NOT NULL,
    plan_id         uuid          NOT NULL,
    decision        varchar(8)    NOT NULL CHECK (decision IN ('APPROVED', 'REJECTED')),
    fingerprint     varchar(80)   NOT NULL CHECK (fingerprint ~ '^sha256:[0-9a-f]{64}$'),
    comment         varchar(1000),
    decided_by      uuid          NOT NULL REFERENCES users (id),
    decided_at      timestamptz   NOT NULL DEFAULT now(),
    FOREIGN KEY (organization_id, plan_id) REFERENCES recovery_plans (organization_id, id),
    CHECK (decision <> 'REJECTED' OR length(trim(comment)) > 0)
);
CREATE UNIQUE INDEX ux_decisions_one_approval ON recovery_decisions (plan_id) WHERE decision = 'APPROVED';
CREATE INDEX ix_decisions_plan ON recovery_decisions (plan_id, decided_at);

-- Separation of duties and fingerprint binding, checked against the plan row.
CREATE FUNCTION trg_recovery_decisions_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_plan recovery_plans%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'recovery decisions are append-only' USING ERRCODE = 'insufficient_privilege';
    END IF;
    SELECT * INTO v_plan FROM recovery_plans WHERE id = NEW.plan_id;
    IF NEW.decision = 'APPROVED' AND NEW.decided_by = v_plan.created_by THEN
        RAISE EXCEPTION 'the author of recovery plan % cannot approve it', v_plan.id
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF NEW.fingerprint <> v_plan.fingerprint THEN
        RAISE EXCEPTION 'decision names fingerprint % but plan % has %', NEW.fingerprint, v_plan.id, v_plan.fingerprint
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER recovery_decisions_guard
    BEFORE INSERT OR UPDATE OR DELETE ON recovery_decisions
    FOR EACH ROW EXECUTE FUNCTION trg_recovery_decisions_guard();

CREATE TABLE recovery_actions (
    id               uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id  uuid          NOT NULL,
    plan_id          uuid          NOT NULL,
    step_no          integer       NOT NULL CHECK (step_no > 0),
    action           varchar(24)   NOT NULL CHECK (action IN ('RESTORE_FIELDS', 'UPDATE_FIELDS', 'ISSUE_CREDIT_NOTE',
                                                             'SUPPLEMENTARY_CHARGE', 'MANUAL_REVIEW', 'NO_ACTION')),
    entity_type      varchar(64)   NOT NULL,
    entity_id        uuid          NOT NULL,
    expected_version bigint        NOT NULL CHECK (expected_version >= 0),
    status           varchar(24)   NOT NULL CHECK (status IN ('DONE', 'FAILED', 'BLOCKED', 'SKIPPED',
                                                              'AWAITING_ACKNOWLEDGEMENT', 'ACKNOWLEDGED')),
    detail           varchar(1000) NOT NULL,
    result_version   bigint,
    result_entity_id uuid,
    txn_id           uuid          NOT NULL,
    executed_by      uuid          NOT NULL REFERENCES users (id),
    recorded_at      timestamptz   NOT NULL DEFAULT now(),
    acknowledged_by  uuid          REFERENCES users (id),
    acknowledged_at  timestamptz,
    acknowledgement  varchar(1000),
    UNIQUE (plan_id, step_no),
    FOREIGN KEY (organization_id, plan_id) REFERENCES recovery_plans (organization_id, id),
    -- A completed step says what it produced.
    CHECK (status <> 'DONE' OR result_version IS NOT NULL OR result_entity_id IS NOT NULL),
    -- An acknowledged step says who did what, and when.
    CHECK ((status = 'ACKNOWLEDGED') = (acknowledged_by IS NOT NULL AND acknowledged_at IS NOT NULL
                                        AND length(trim(acknowledgement)) > 0))
);
CREATE INDEX ix_actions_txn ON recovery_actions (txn_id);

CREATE FUNCTION trg_recovery_actions_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'recovery actions are kept forever' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NOT EXISTS (SELECT 1 FROM recovery_plans WHERE id = NEW.plan_id AND status = 'EXECUTING') THEN
            RAISE EXCEPTION 'steps are recorded only while plan % is EXECUTING', NEW.plan_id
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.status = 'ACKNOWLEDGED' THEN
            RAISE EXCEPTION 'a step is acknowledged only after it awaited acknowledgement'
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN NEW;
    END IF;
    IF NOT (OLD.status = 'AWAITING_ACKNOWLEDGEMENT' AND NEW.status = 'ACKNOWLEDGED')
       OR (NEW.organization_id, NEW.plan_id, NEW.step_no, NEW.action, NEW.entity_type, NEW.entity_id,
           NEW.expected_version, NEW.detail, NEW.result_version, NEW.result_entity_id, NEW.txn_id,
           NEW.executed_by, NEW.recorded_at)
          IS DISTINCT FROM
          (OLD.organization_id, OLD.plan_id, OLD.step_no, OLD.action, OLD.entity_type, OLD.entity_id,
           OLD.expected_version, OLD.detail, OLD.result_version, OLD.result_entity_id, OLD.txn_id,
           OLD.executed_by, OLD.recorded_at) THEN
        RAISE EXCEPTION 'a recorded step only changes by acknowledging a manual step'
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER recovery_actions_guard
    BEFORE INSERT OR UPDATE OR DELETE ON recovery_actions
    FOR EACH ROW EXECUTE FUNCTION trg_recovery_actions_guard();

CREATE TABLE recovery_verifications (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid        NOT NULL,
    plan_id         uuid        NOT NULL,
    outcome         varchar(12) NOT NULL CHECK (outcome IN ('PASSED', 'INCOMPLETE', 'FAILED')),
    check_count     integer     NOT NULL CHECK (check_count >= 0),
    failed_count    integer     NOT NULL CHECK (failed_count >= 0 AND failed_count <= check_count),
    pending_manual  integer     NOT NULL CHECK (pending_manual >= 0),
    checks          jsonb       NOT NULL CHECK (jsonb_typeof(checks) = 'array'),
    verified_by     uuid        NOT NULL REFERENCES users (id),
    verified_at     timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (organization_id, plan_id) REFERENCES recovery_plans (organization_id, id),
    -- The outcome must agree with the counts: no PASSED with a failure in it.
    CHECK (outcome <> 'PASSED' OR (failed_count = 0 AND pending_manual = 0)),
    CHECK (outcome <> 'INCOMPLETE' OR (failed_count = 0 AND pending_manual > 0)),
    CHECK (outcome <> 'FAILED' OR failed_count > 0)
);
CREATE INDEX ix_verifications_plan ON recovery_verifications (plan_id, verified_at DESC);

CREATE FUNCTION trg_recovery_verifications_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'recovery verifications are append-only' USING ERRCODE = 'insufficient_privilege';
END $$;

CREATE TRIGGER recovery_verifications_guard
    BEFORE UPDATE OR DELETE ON recovery_verifications
    FOR EACH ROW EXECUTE FUNCTION trg_recovery_verifications_guard();

-- Plan status transitions, now including execution. Content stays immutable.
CREATE OR REPLACE FUNCTION trg_recovery_plans_guard() RETURNS trigger
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
           (OLD.status = 'PROPOSED'  AND NEW.status IN ('APPROVED', 'REJECTED', 'SUPERSEDED'))
        OR (OLD.status = 'PROPOSED'  AND NEW.status = 'EXECUTING' AND NOT OLD.requires_approval)
        OR (OLD.status = 'APPROVED'  AND NEW.status IN ('EXECUTING', 'REJECTED', 'SUPERSEDED'))
        OR (OLD.status = 'EXECUTING' AND NEW.status IN ('EXECUTED', 'FAILED'))) THEN
        RAISE EXCEPTION 'recovery plan status cannot go from % to %', OLD.status, NEW.status
            USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.status = 'APPROVED' AND OLD.status <> 'APPROVED' AND NOT EXISTS (
           SELECT 1 FROM recovery_decisions
            WHERE plan_id = NEW.id AND decision = 'APPROVED' AND fingerprint = NEW.fingerprint) THEN
        RAISE EXCEPTION 'recovery plan % has no approval for its fingerprint', NEW.id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END $$;
