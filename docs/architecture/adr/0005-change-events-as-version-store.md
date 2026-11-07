# ADR-0005: change_events is the version store; the hash chain is computed in the database

**Status:** Accepted (2026-11-05)

**Decision.** One `change_events` row per (entity, version) with full
before/after JSON snapshots, unique on (org, type, id, version). This
replaces a separate `record_versions` table, which would duplicate the same
data and could disagree with it. A BEFORE INSERT trigger assigns a per-tenant
`chain_index` under a row lock on `audit_chain_heads` and computes
`hash = sha256(prev_hash || canonical payload)`. UPDATE, DELETE and TRUNCATE
are rejected by trigger. `verify_audit_chain(org)` recomputes the chain.

**Why in the database.** The application cannot forge or skip chain
positions, and concurrent writers are serialised per tenant (verified with
32 concurrent pgbench clients).

**Consequences.** Appends for one tenant are serialised on one row lock —
a throughput ceiling to measure in Phase 8. Snapshots cost storage; JSONB
compression and partitioning by month are the planned mitigations if needed.
Stated limitation: a superuser can rewrite the whole chain; external
anchoring of the head (Phase 8) is required to detect that.
