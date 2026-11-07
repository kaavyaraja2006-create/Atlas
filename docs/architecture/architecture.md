# ATLAS — System Architecture

**ATLAS** — Audit-Tracked Lineage and Adaptive Selective Recovery.
Status of this document: **Phase 1 baseline, for review.**

## 1. What ATLAS is

A multi-tenant commerce-operations platform (catalogue, customers, orders,
invoices, inventory) whose distinguishing capability is **selective recovery**:
when a faulty write (e.g. a product price entered as ₹500 instead of ₹5,000)
has already propagated into derived records, ATLAS finds exactly the records
that consumed the faulty version, plans a safe correction, gets it approved,
executes it, and verifies the result — without overwriting legitimate later
changes and without erasing history.

The commerce domain was chosen over project/task management because it
produces genuinely *derived* data (prices → order lines → order totals →
invoices). A task tracker has almost none, which would leave the recovery
engine nothing real to trace. See ADR-0002.

## 2. Architectural style

**Modular monolith** (ADR-0001): one deployable Spring Boot application, one
PostgreSQL database, strict module boundaries. Services are extracted only
when a measured need appears (independent scaling, isolation, release cadence).

```
┌────────────────────────────── React + TypeScript SPA ─────────────────────────────┐
│  Catalogue · Orders · Invoices · Audit Explorer · Dependency Explorer · Recovery  │
└───────────────────────────────────────┬───────────────────────────────────────────┘
                                        │ HTTPS, JSON, /api/v1, JWT (Phase 2)
┌───────────────────────────────────────▼───────────────────────────────────────────┐
│ Spring Boot application                                                           │
│  common/web : CorrelationIdFilter · problem+json errors · security filter (P2)    │
│                                                                                   │
│  identity   organization   catalogue   sales   billing   inventory               │
│      │            │            │         │        │          │                    │
│      └────────────┴────────────┴────┬────┴────────┴──────────┘                    │
│                                     │ every write goes through                    │
│                         audit  (ChangeRecorder)  ◄── provenance (ProvenanceRecorder)│
│                                     ▲                         ▲                    │
│                                     └──────── recovery (PASCAR) ┘                  │
└───────────────────────────────────────┬───────────────────────────────────────────┘
                                        │ JDBC (HikariCP), Flyway-managed schema
                              ┌─────────▼─────────┐
                              │  PostgreSQL 16     │  triggers enforce: tenant FKs,
                              │                    │  final invoices, append-only
                              └────────────────────┘  history, DB-computed hash chain
```

### Modules and ownership

| Module | Owns tables | Phase |
|---|---|---|
| `identity` | users, (login_events) | 2 |
| `organization` | organizations, organization_members, member_roles, roles, permissions, role_permissions | 2 |
| `catalogue` | products | 2 |
| `sales` | customers, orders, order_lines | 2 |
| `billing` | invoices, invoice_lines, credit_notes | 2 |
| `inventory` | inventory_items | 2 |
| `audit` | change_events, audit_chain_heads | 1 (schema), 2 (API) |
| `provenance` | provenance_edges | 1 (schema), 4 (API) |
| `recovery` | recovery_incidents, recovery_plans, recovery_actions, recovery_approvals | 5–6 |
| `common` | (none) — web plumbing, error format, IDs, clock | 1 |

**Boundary rule.** A module may call another module only through its public
application services and DTOs; never its repositories or JPA entities. Enforced
from Phase 2 with an architecture test (Spring Modulith or ArchUnit).

### Layering inside a module

`controller` (thin: HTTP ↔ DTO, no logic) → `application service`
(transaction boundary, authorization, orchestration) → `domain` (entities,
invariants, pure logic such as pricing) → `repository` (Spring `JdbcClient` with explicit SQL; see ADR-0007).

## 3. The core mechanism: recording change and provenance at write time

Everything the recovery engine does depends on two facts being recorded
**in the same database transaction** as the business write:

1. **Change capture** — `ChangeRecorder.record(entity, before, after, reason)`
   appends one `change_events` row per entity version, with full before/after
   snapshots. The database assigns the chain position and computes the hash.
2. **Provenance capture** — whenever a service computes a value *from*
   another record, it calls
   `ProvenanceRecorder.derived(source@version.field → target@version.field, relation)`.

Example — adding a line to an order:

```
txn T (one business command, one txn_id)
  read  product P100 @ version 7  (unit_price = 500)      ← the faulty version
  write order_line L1 @ version 0 (unit_price = 500, qty 3)
        → change_events: (order_line, L1, v0, INSERT, after={...})
        → provenance_edges: product P100 v7 .unit_price ─DERIVED_VALUE→ order_line L1 v0 .unit_price
  write order O1 @ version 4 (total_amount += 1500)
        → change_events: (order, O1, v4, UPDATE, before/after)
        → provenance_edges: order_line L1 v0 ─AGGREGATED_INTO→ order O1 v4 .total_amount
commit
```

**Why record edges explicitly instead of inferring them from foreign keys.**
`inventory_items.product_id` is a foreign key to `products`, but inventory is
valued at `unit_cost`, so a *price* error never affects it. A recovery engine
that followed FKs would "repair" inventory needlessly. Only recorded
derivations count (ADR-0003). The database enforces that both ends of every
edge are versions that really exist in `change_events` (deferred composite
FKs), so edges cannot be fabricated.

**Why edges are version-to-version.** If the price was wrong in v7 and fixed
in v8, order lines priced from v6 or v8 are *not* affected. Version-level
edges let the impact analyzer select precisely the records that consumed v7.

## 4. Guarantees enforced by the database (verified in Phase 1)

| Guarantee | Mechanism | Verified by |
|---|---|---|
| No cross-tenant references | `UNIQUE (organization_id, id)` + composite FKs | schema-assertions §1 |
| Issued invoices are final; only ISSUED→VOID | `invoices_guard_issued` trigger | §3 |
| Lines of non-draft invoices are frozen | `invoice_lines_guard_final` trigger | §3 |
| One active invoice per order | partial unique index | §3 |
| History is append-only | `trg_reject_mutation` on UPDATE/DELETE/TRUNCATE | §4 |
| One event per entity version | `UNIQUE (org, type, id, version)` | §4 |
| Tamper evidence | per-tenant SHA-256 chain computed in-DB; `verify_audit_chain()` | §4, §6, concurrency check |
| No fabricated provenance edges | deferred composite FKs to `change_events` | §5 |

Limits stated plainly: a database superuser can disable triggers. The hash
chain *detects* that kind of edit, but a superuser who rewrites every later
hash and the chain head would go undetected unless the head is anchored
outside the database — planned for Phase 8 (periodic signed export of the
chain head). Application authorization remains the primary tenant control;
the composite keys are defence in depth.

## 5. Transaction boundaries

* One HTTP command = one application-service method = one database
  transaction = one `txn_id`. Business rows, change events and provenance
  edges commit or roll back together.
* Optimistic locking (`version` column, JPA `@Version`) on every mutable
  aggregate. A lost race returns **409 Conflict**; nothing is overwritten.
* Recovery execution (Phase 6) uses *bounded* transactions: one per plan
  step, each idempotent (keyed by plan id + step id) and version-checked, with
  progress persisted so a crash resumes instead of re-applying.

## 6. Cross-cutting concerns

* **Errors:** RFC 9457 `application/problem+json` everywhere, with
  `correlationId` and `timestamp`; no stack traces or internal messages leak.
* **Correlation:** `X-Correlation-Id` accepted if safe (`[A-Za-z0-9._-]{1,64}`),
  otherwise generated; placed in the log MDC and echoed in the response.
* **Configuration and secrets:** environment variables only; `.env` is
  git-ignored; startup fails if DB credentials are missing.
* **Observability:** Actuator health with liveness/readiness probes now;
  Micrometer → Prometheus/Grafana and OpenTelemetry tracing in Phase 8.
* **Events:** a transactional outbox table polled by a background publisher
  (Phase 7). Kafka is introduced only if a consumer needs it (ADR-0006).

## 7. Deployment view

| Stage | Target |
|---|---|
| Local | Docker Compose: PostgreSQL 16 (+ app with `--profile app`) |
| CI | GitHub Actions: psql schema suite, pgbench chain check, `mvn verify` with Testcontainers |
| Cloud (Phase 9) | AWS: ECS Fargate behind an ALB, RDS PostgreSQL, Secrets Manager, CloudWatch; Terraform |
| Later, optional | Kubernetes only if a concrete need appears |
