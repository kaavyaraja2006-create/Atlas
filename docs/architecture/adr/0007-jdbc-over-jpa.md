# ADR-0007: Plain JDBC (Spring JdbcClient) instead of JPA/Hibernate

**Status:** Accepted (2026-11-05)

**Context.** Every write must record an exact before/after snapshot and a
version-checked update in the same transaction, and Phase 2b adds
provenance edges keyed by the exact version a value was read from. JPA
hides when SQL runs (dirty checking, flush order, lazy loading) and its
version handling throws exceptions that are hard to map to a clean 409.

**Decision.** Repositories use `JdbcClient` with explicit SQL kept in
`static final String` constants. Updates lock the row (`FOR UPDATE`),
compare the client's version, and write with `WHERE version = :expected`.

**Consequences.**
* What runs is exactly what is written, which makes change capture and
  provenance auditable and testable: `scripts/verify-repository-sql.py`
  extracts every SQL constant from the Java source and runs it against
  the real schema (57 checks, all 34 statements).
* More code for mapping rows. Accepted: the domain has few tables.
* No Hibernate schema validation; Flyway plus the schema suite cover it.
