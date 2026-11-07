-- =====================================================================
-- V14 (Graph Intelligence, step 1): platform-level audit and schema
-- snapshots for the ER Schema Explorer.
--
-- The database schema belongs to no tenant, so inspecting it is audited
-- here, not in a tenant's hash-chained change_events. Rows are append-only
-- (trigger), like every other audit table in ATLAS.
--
-- schema_snapshots holds one canonical description of the application
-- schema per distinct structure (sha256 of the canonical JSON), captured at
-- start-up when the migration version changes. Schema versions are compared
-- from these rows, never from a hardcoded model.
-- =====================================================================

CREATE TABLE platform_audit_events (
    id              uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    occurred_at     timestamptz   NOT NULL DEFAULT now(),
    actor_user_id   uuid          NOT NULL REFERENCES users (id),
    action          varchar(40)   NOT NULL CHECK (action IN (
                        'SCHEMA_VIEW', 'SCHEMA_VERSIONS', 'SCHEMA_DIFF', 'SCHEMA_EXPORT', 'SCHEMA_ACCESS_DENIED')),
    target          varchar(200),
    detail          jsonb         NOT NULL DEFAULT '{}'::jsonb,
    correlation_id  varchar(64)
);

CREATE INDEX ix_platform_audit_time ON platform_audit_events (occurred_at DESC);
CREATE INDEX ix_platform_audit_actor ON platform_audit_events (actor_user_id, occurred_at DESC);

CREATE FUNCTION graph_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END;
$$;

CREATE TRIGGER platform_audit_events_append_only
    BEFORE UPDATE OR DELETE ON platform_audit_events
    FOR EACH ROW EXECUTE FUNCTION graph_append_only();

CREATE TABLE schema_snapshots (
    id              uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    flyway_version  varchar(50)   NOT NULL,
    sha256          char(64)      NOT NULL UNIQUE CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    table_count     integer       NOT NULL CHECK (table_count >= 0),
    relationship_count integer    NOT NULL CHECK (relationship_count >= 0),
    catalog         jsonb         NOT NULL,
    captured_at     timestamptz   NOT NULL DEFAULT now()
);

CREATE INDEX ix_schema_snapshots_time ON schema_snapshots (captured_at DESC);

CREATE TRIGGER schema_snapshots_append_only
    BEFORE UPDATE OR DELETE ON schema_snapshots
    FOR EACH ROW EXECUTE FUNCTION graph_append_only();
