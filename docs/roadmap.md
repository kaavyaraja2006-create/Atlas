# ATLAS — Phased Implementation Checklist

A phase is done only when its exit criteria are met **and recorded in
`docs/STATUS.md` with evidence**. If time runs short, finish every MUST
before starting any SHOULD.

## Phase 1 — Foundation ✅ (this milestone)
- [x] Architecture, SRS, ER model, API plan, page map, ADRs, risks
- [x] Repository layout, Maven project, module packages
- [x] Flyway V1–V3: tenancy, commerce domain, change events, provenance
- [x] Schema guarantee suite (23 assertions; 34 after V4) and negative control
- [x] Audit-chain concurrency check (pgbench)
- [x] Correlation ids, problem+json errors, schema-version endpoint
- [x] Docker Compose (PostgreSQL), Dockerfile, CI workflow
- [ ] `mvn verify` green — **needs a machine with Maven Central access** (see STATUS)

**Exit:** migrations apply; assertions pass; `mvn verify` green in CI.

## Phase 2 — Identity, tenancy, commerce backend (MUST) — written, awaiting first CI build
- [x] V4 migration: hashed rotating refresh tokens with family revocation, append-only login history, per-tenant idempotency keys (verified)
- [x] 2a: Spring Security, JWT access + rotating refresh, BCrypt, lockout (written; SQL + logic verified)
- [x] 2a: Organization creation, membership, role grants; per-request permission checks (written; SQL verified)
- [x] 2a: Products with ETag/If-Match → 428/409; `ChangeRecorder` on every write; audit query + chain verify API
- [x] 2b: Customers, orders (+lines), invoices (+issue/void, credit notes), price provenance edges, Idempotency-Key, gapless numbering (written; SQL + logic verified)
- [ ] Pagination/filtering ✔ for products/members/audit; OpenAPI (springdoc)
- [ ] Module-boundary architecture test
- **Exit:** cross-tenant test for every endpoint; concurrency test; every write path asserts its change events

## Phase 3 — Frontend core (MUST) — frontend built and tested; real-API E2E pending
- [x] Vite + React + TS strict, Tailwind, TanStack Query, React Hook Form + Zod (hand-written shadcn-style components; registry unreachable from the build session)
- [x] Sign in, register, org create/switch, members, catalogue, customers, orders, invoices
- [x] Loading/empty/error states with correlation ids; 409 conflict banner; typed confirmation for destructive actions; dark theme; mobile layout
- [ ] OpenAPI-generated client (needs springdoc on the backend; types are hand-mirrored for now)
- [ ] **Exit:** Playwright: sign in → create product → order → invoice → issue, against the real API

## Phase 4 — Provenance and audit UI (MUST) — done (frontend + SQL verified)
- [x] `ProvenanceRecorder` in pricing, order totals, invoicing paths (done in 2b)
- [x] One-hop provenance query API (done in 2b); multi-hop traversal moves to Phase 5
- [x] Audit explorer with filters, field-level diff, chain verification
- [x] Record history with version timeline and provenance links at exact versions
- [ ] Outbox table + polled publisher — **deferred** until a consumer exists (ADR-0006)
- [ ] **Exit:** for each derivation path, an integration test asserts the exact edges (written in `OrdersAndInvoicesIT`; not yet run)

## Phase 5 — PASCAR analysis and planning (MUST) — engine + experiment verified
- [x] Prior-art review (`docs/research/prior-art.md`)
- [x] Incident model (V6); cycle-safe traversal from the faulty version (field- and version-level)
- [x] Classification, conflict detection (field-level), non-reversible detection (issued/void invoices)
- [x] Plan generation: dependency order, expected versions, proposals, compensation amounts, risk, invariants, fingerprint
- [x] Synthetic generator + edge-independent oracle; precision/recall on 3 sizes; baselines; edge-loss curve
- [x] Algorithm spec (`docs/algorithms/pascar.md`)
- [x] Recovery center UI — done in Phase 6
- [ ] `RecoveryIT` green in CI
- **Exit (met on synthetic data):** precision/recall vs oracle on 3 dataset sizes; cycle and conflict tests

## Phase 6 — PASCAR execution and verification (MUST) — workflow, SQL and UI tested
- [x] Approval bound to plan + fingerprint; author cannot approve (application and database)
- [x] Step executor: one transaction per step, unique outcome per step, version checks, blocking, resume, replay
- [x] Credit-note compensation for issued invoices, never beyond what remains; no double credit on re-plan
- [x] Verifier: field values, totals = Σ lines, credits, scope of writes, fresh re-plan finds nothing; results kept
- [x] Recovery center UI (fixture-tested; end-to-end waits on the backend build)
- [ ] `RecoveryIT` green in CI
- **Exit (met in unit tests):** crash after every commit and inside every write resumes correctly; stale plan → 409 (`RecoveryException.STALE_PLAN` → 409; HTTP mapping covered by `RecoveryIT`, not yet run)

## Phase 7 — Benchmarks (MUST) — done on synthetic data
- [x] Baselines: full restore (pg_dump/pg_restore + records lost), naive FK traversal, record-level dependency closure (the granularity of txn-level undo; an approximation, not an implementation)
- [x] Metrics: time, records touched, unaffected touched, completeness, conflicts, lost updates, invariant violations (database-wide)
- [x] Raw results, scripts and seeds committed; methodology documented (`docs/experiments/phase7-benchmark.md`)
- [x] Two scale problems found: quadratic step lock (fixed), quadratic single-transaction bulk append (documented)
- [ ] Repeat with the Java orchestration once the backend builds (here it is a Python replay of the workflow)
- **Exit (met):** benchmark report with real numbers and stated limits

## Phase 8 — Hardening (SHOULD) — done except items needing a running build (see STATUS)
- Micrometer → Prometheus/Grafana; OpenTelemetry traces
- k6 load tests vs NFR-PERF-1 (script written, not run); EXPLAIN ANALYZE on hot queries (done, V8)
- gitleaks (done, CI), dependency scanning (npm done; Maven via Dependabot), container scanning + ZAP baseline (not run)
- External anchoring of audit chain head (done, V9)
- Redis only if a measured need appears (none measured; not added)

## Phase 9 — Deployment and presentation (SHOULD) — written; nothing deployed (see STATUS)
- Done here: accessibility (axe) with fixes, web tier, demo seed, recovery smoke test, CI stack job (k6, ZAP, Trivy), JaCoCo gate, Terraform/OpenTofu for AWS (validated, Checkov-clean), deploy workflow, Prometheus alerts + Grafana
- Waiting on a first CI run / AWS account: running-stack checks, `tofu apply`, live URL, demo video, screenshots from live data
- Next: Spring Boot 4.1 migration (`docs/architecture/spring-boot-4-migration.md`), digest pinning
- Terraform: ECS Fargate, RDS, ALB, Secrets Manager
- README with screenshots, demo video, architecture diagram, limitations

## ATLAS X — causal provenance, temporal conflicts, simulation, integrity (Milestones 2–9) — engine, SQL, UI and experiment tested; Spring wiring and `AtlasXIT` not yet run

- [x] Provenance operation + confidence, multi-hop lineage, gaps
- [x] Field history and timeline
- [x] Per-field conflicts with append-only reviews; unreviewed conflicts block execution
- [x] Counterfactual simulation of six strategies on one snapshot; optimizer with hard constraints and risk
- [x] Integrity monitoring (5 rule kinds, scheduler, findings)
- [x] Frontend views; experiment with ablations (synthetic)
- [ ] `mvn verify` + `AtlasXIT` on a machine with Maven and Docker
- [ ] Prometheus alert on open critical findings

## LATER
MFA · Kafka · Kubernetes · workflow builder · project/task management · OpenSearch · real-time updates
