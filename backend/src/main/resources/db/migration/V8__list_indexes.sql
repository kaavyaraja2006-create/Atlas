-- =====================================================================
-- V8: indexes for the list queries (Phase 8, measured on 100k orders /
--     850k change events; docs/security/hardening.md, "Query plans")
-- =====================================================================

-- The audit list is ordered newest first by time, ties by chain position. One index
-- serves the unfiltered list, time windows and type filters without a sort. It
-- replaces ix_change_events_org_time (a prefix of it). Before: a one-hour window in
-- the middle of history walked the chain index backwards past 421,236 rows (171 ms).
CREATE INDEX ix_change_events_org_time_chain
    ON change_events (organization_id, occurred_at DESC, chain_index DESC);
DROP INDEX ix_change_events_org_time;

-- Invoices had no index for their default sort; each page scanned the table.
CREATE INDEX ix_invoices_org_created ON invoices (organization_id, created_at DESC);
