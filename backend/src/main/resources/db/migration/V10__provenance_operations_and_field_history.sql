-- =====================================================================
-- V10 (ATLAS X, Milestone 2-3): business operation on provenance edges,
-- and a field-level history VIEW over change_events.
--
-- No new history table: change_events already stores the full before/after
-- state of every version, its changed fields, actor, transaction,
-- correlation id, reason and time. field_changes only reshapes that into
-- one row per changed field, so there is a single source of truth.
-- =====================================================================

-- The business operation that recorded the edge ("order.add_line",
-- "invoice.create_from_order", "recovery.update_fields", ...). NULL for
-- edges recorded before V10: shown as "not recorded", never guessed.
ALTER TABLE provenance_edges ADD COLUMN operation varchar(64)
    CHECK (operation IS NULL OR operation ~ '^[a-z][a-z_]*\.[a-z][a-z_]*$');

CREATE VIEW field_changes AS
SELECT e.organization_id,
       e.id                                        AS change_event_id,
       e.chain_index,
       e.txn_id,
       e.entity_type,
       e.entity_id,
       e.entity_version                            AS new_version,
       CASE WHEN e.operation = 'INSERT' THEN NULL ELSE e.entity_version - 1 END AS previous_version,
       f.field,
       e.before_state -> f.field                   AS previous_value,
       e.after_state  -> f.field                   AS new_value,
       e.operation,
       e.actor_kind,
       e.actor_user_id,
       e.reason,
       e.correlation_id,
       e.occurred_at
  FROM change_events e
 CROSS JOIN LATERAL unnest(e.changed_fields) AS f(field);

COMMENT ON VIEW field_changes IS
    'One row per changed field per recorded version (ATLAS X field history); derived from change_events, never written';
