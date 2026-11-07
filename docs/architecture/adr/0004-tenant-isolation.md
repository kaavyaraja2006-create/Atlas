# ADR-0004: Tenant isolation — application checks plus composite foreign keys

**Status:** Accepted (2026-11-05)

**Decision.** Primary control: every request resolves an organization from
the caller's memberships, and every repository query filters by
`organization_id`; foreign-tenant ids return 404. Defence in depth: every
child row references its parent through `(organization_id, parent_id)`, so
the database cannot store a cross-tenant link even if the application has a
bug. PostgreSQL row-level security is deferred (it complicates connection
pooling and tests) and may be added in Phase 8 with a measured overhead.

**Consequences.** Slightly wider foreign keys and an extra unique index per
table. Verified by `schema-assertions.sql` §1; Phase 2 adds an API-level
cross-tenant test for every endpoint.
