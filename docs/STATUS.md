# ATLAS — Implementation Status

Levels: NOT STARTED · IN PROGRESS · IMPLEMENTED (code exists) ·
TESTED (automated tests pass) · VERIFIED (tested and checked end to end).
Last updated: 2026-11-07 (Graph Intelligence, steps 1–4).

## Phase 1 items

| Item | Status | Evidence |
|---|---|---|
| Design docs (architecture, SRS, ER, API, UX, ADRs, risks) | IMPLEMENTED | `docs/` — awaiting review |
| Flyway V1–V4 schema | **VERIFIED** | Applied to PostgreSQL 16.15 with psql; `scripts/verify-schema.sh` → 34/34 assertions pass |
| Schema suite catches real regressions | **VERIFIED** | Negative control: invoice guard removed → suite fails at "issued invoice amount cannot be rewritten" |
| Audit chain under concurrency | **VERIFIED** | `scripts/chain-concurrency-check.sh`: 8×250 and 32×100 concurrent appends, gap-free, verifies |
| Tamper detection | **VERIFIED** | Assertion §6: trigger bypassed, history edited → `verify_audit_chain` reports event #2 |
| Spring Boot app, correlation filter, problem+json, `/api/v1/system/info` | IMPLEMENTED | Sources parse with `javac`; **not compiled or run** (see below) |
| Unit test `CorrelationIdFilterTest` | IMPLEMENTED | Not yet run |
| Integration tests `ApplicationIT`, `SchemaGuaranteesIT` (Testcontainers) | IMPLEMENTED | Not yet run |
| Docker Compose, Dockerfile | IMPLEMENTED | Not yet run (no Docker daemon/registry access in the build session) |
| GitHub Actions CI | IMPLEMENTED | Not yet run — first push will run it |

### Why parts are only IMPLEMENTED

The Phase 1 build session's network policy blocked Maven Central and Docker
registries, so Spring Boot dependencies could not be downloaded. The schema
— the part every later phase depends on — was verified independently with
plain PostgreSQL tooling. To close the gap, run on any normal machine:

```bash
cd backend && mvn verify          # needs Docker running for Testcontainers
```

or push to GitHub and let CI run. Update this table with the result.

## Phase 2a items

| Item | Status | Evidence |
|---|---|---|
| V4 schema: refresh tokens, login history, idempotency keys | **VERIFIED** | 11 assertions in `schema-assertions.sql` (34 total) |
| Framework-free logic: password policy, token hashing, change diff, paging/sort whitelist, ETag parsing, tenant permission checks, product partial update | **TESTED** | 26 JUnit tests compiled with `javac -Xlint:all -Werror` and run with a stand-in runner; negative control (number comparison broken) fails 2 tests as expected |
| All 34 SQL statements in the repositories (users, lockout, refresh tokens, login events, organizations, memberships, products, change events, audit queries) | **TESTED** | `scripts/verify-repository-sql.py` extracts them from the Java source and runs 57 checks on PostgreSQL 16; fails if any statement is unexercised; negative control (optimistic-lock condition removed) fails as expected |
| Spring wiring: security config, JWT encode/decode, controllers, services, transaction handling, tenant resolver, error handling | IMPLEMENTED | Parses with `javac`; **not compiled against Spring** |
| Integration tests: `AuthFlowIT`, `TenancyAndCatalogueIT`, updated `ApplicationIT` (rotation + reuse, lockout, cross-tenant 404, role changes, ETag 428/409, concurrent edit, audit capture, chain verify) | IMPLEMENTED | Not yet run — need Maven + Docker |

What the local checks do **not** cover: JDBC parameter typing, row mapping,
Jackson serialisation, Spring Security behaviour and HTTP status mapping.
The integration tests cover those; they must pass in CI before 2a counts
as TESTED.

## Phase 2b items

| Item | Status | Evidence |
|---|---|---|
| V5 schema: gapless per-org document numbers, order line descriptions, credit note issued_at, invoice `issued_at` rule fixed so a DRAFT can be discarded | **VERIFIED** | 5 new assertions (39 total); 16×200 concurrent numbering → 3,200 unique, gapless numbers |
| Framework-free logic: order/invoice lifecycle rules, credit-note limits, totals, document number format, Idempotency-Key validation and request hashing | **TESTED** | 41 unit tests total (15 new), `javac -Xlint:all -Werror` |
| All 68 repository SQL statements (34 new: customers, orders, lines, totals, invoices, credit notes, idempotency, provenance) | **TESTED** | 104 checks; includes an end-to-end check that edges from the faulty price reach exactly the affected line → order totals → invoice line → invoice, and not the correctly priced line; negative control (order total summing unit prices) fails 2 checks |
| Spring wiring for customers, orders, invoices, credit notes, Idempotency-Key, provenance API | IMPLEMENTED | Parses with `javac`; not compiled against Spring |
| `OrdersAndInvoicesIT`: faulty-price flow + provenance API, idempotent replay, credit notes, draft discard, business rules, tenant boundaries, concurrent invoicing | IMPLEMENTED | Not yet run |

Gap to note: in the SQL check the script writes the provenance edges
itself, so it proves the schema and the edge semantics, not that
`OrderService` picks the right product version. `OrdersAndInvoicesIT`
covers that.

## Phase 3 items

| Item | Status | Evidence |
|---|---|---|
| React 18 + TypeScript (strict, `noUncheckedIndexedAccess`) + Vite + Tailwind; design tokens, light/dark themes | **TESTED** | `npm run typecheck` clean; `npm run build` succeeds (107 kB JS gzipped) |
| API client: in-memory access token, one shared refresh on 401 then retry, session end on refresh failure, tenant header, If-Match, Idempotency-Key, problem+json errors | **TESTED** | 10 Vitest tests; negative control (refresh not shared) fails the concurrency test — matters because parallel refreshes would trip the server's reuse detection and log the user out |
| Pages: sign in, register, organizations (list/create), members (add, edit roles), catalogue (search, filter, paging, create, edit with 409 conflict handling), customers, orders (create with Idempotency-Key, lines, confirm, cancel, invoice), invoices (issue, void/discard with typed confirmation, credit notes) | **TESTED** (UI behaviour) | 21 tests total incl. component tests (ProductEditor conflict + reload, SignIn errors, registration rules); Chromium screenshots of 8 views, no JS errors, no horizontal overflow at 390 px — against **fixture** API responses (see `docs/ux/screenshots/`) |
| Backend `GET /api/v1/access` (caller's roles + permissions; UI hides actions, server still enforces) | IMPLEMENTED | Parses; assertion added to `TenancyAndCatalogueIT`; not yet run |
| Frontend ↔ real backend | NOT STARTED | Needs the backend to build and run |
| Playwright E2E against the real API (roadmap exit criterion) | NOT STARTED | Same dependency |

## Phase 4 items

| Item | Status | Evidence |
|---|---|---|
| Audit API returns actor display names (LEFT JOIN users) | **TESTED** (SQL) | 2 new checks; 106/106 pass. Java mapping not yet compiled |
| Audit explorer: filters (record type, time window, one transaction), field-level diff, chain verification with a loud failure state | **TESTED** | 4 component tests (diff rendering, transaction filter request, broken chain alert, intact chain) |
| Record history: version timeline, diff per version, "computed from" / "fed into" provenance with links to the exact version | **TESTED** | 2 component tests + 8 unit tests (diff ordering, direction-aware edge sentences); negative control (version dropped from links) fails |
| History links from product, order and invoice pages (only with `audit.read`) | **TESTED** | Typecheck + build; screenshots |
| Screenshots of audit, diff, history (desktop + 390 px) | **VERIFIED** (visual) | 12 views, no JS errors, no horizontal overflow — fixture API responses |
| Transactional outbox | **DEFERRED** | No consumer exists yet; built when recovery or notifications need one (ADR-0006) |

## Phase 5 items

| Item | Status | Evidence |
|---|---|---|
| Prior-art review (Ammann et al. 2002, Akkuş & Goel 2010, Retro 2010, Warp 2011) with verified citations | WRITTEN | `docs/research/prior-art.md`; conclusion: not novel, framed as engineering + evaluation |
| PASCAR engine (framework-free): field-level taint, carry-forward, conflicts, cycles (iterative Tarjan), backfilled evidence, classification, dependency-ordered plan, compensation, invariants, fingerprint | **TESTED** | 12 unit tests (`javac -Xlint:all -Werror`), incl. 50,000-record cyclic chain under 512 MB / 512 KB stack; 2 negative controls fail as expected |
| Experiment: precision/recall vs an edge-independent oracle, naive-FK and full-restore baselines, edge loss; 1k/10k/100k orders × 3 seeds × 2 scenarios | **VERIFIED** (synthetic) | `docs/experiments/phase5-impact-analysis.md`: PASCAR 1.000/1.000 in all 18 runs; 0 value mismatches over 137,509 orders and 25,030 compensations; CI guard `ImpactExperimentTest` |
| Scale bug found and fixed: planning was quadratic (129 s → 3 s at 100k orders) | **VERIFIED** | Before/after raw results both committed |
| V6 schema: incidents (one active per change, tenant FK to change event), immutable plans with status state machine, INCIDENT numbering | **VERIFIED** | 11 new assertions (50 total) |
| Recovery SQL (14 statements): recursive graph loader, incidents, plans | **TESTED** | 18 new checks (124 total, all 82 statements); loader returns exactly reached records + siblings; terminates on a cyclic graph |
| RecoveryService / Controller (open incident, impact, plan, list, get), REPEATABLE READ snapshot, 409 on serialization failure | IMPLEMENTED | Parses; `RecoveryIT` written, not yet run |
| Recovery center UI | MOVED to Phase 6 | Done there (below) |

## Phase 6 items

| Item | Status | Evidence |
|---|---|---|
| Workflow (framework-free): approval bound to fingerprint, author cannot approve, stale-plan check at approval and execution, one transaction per step, version checks, blocking, manual steps, resume, replay, verification with 7 check kinds | **TESTED** | 18 unit tests on an in-memory stand-in (`RecoveryWorkflowTest`); crash injected after every commit and inside every write, each run resumes, applies every step once and verifies PASSED; 11/11 mutations caught |
| Engine changes: step inputs (for provenance of recovery writes), credits already issued deducted on re-plan, no step for an already-restored origin, invariants name their field | **TESTED** | `PascarTest` extended; a fresh analysis after a full recovery plans nothing |
| Stored plan JSON round trip | **TESTED** | `PlanJsonRoundTripTest` with Jackson 2.16 (not Spring Boot's configured mapper) |
| V7 schema: decisions (author and fingerprint checked by trigger), actions (one outcome per step, only acknowledgement may change), verifications (PASSED cannot contain a failure), plan states incl. EXECUTING/FAILED | **VERIFIED** | 22 new assertions (72 total) |
| Execution SQL (24 statements): plan lock, decisions, actions, guarded record writes, credit notes within what remains, writes attributed to a plan, verification storage | **TESTED** | 32 new checks (156 total, every statement exercised) on PostgreSQL 16 |
| Spring adapters (`JdbcRecoveryPorts`, `ExecutionRepository`, `ExecutionService`), endpoints | IMPLEMENTED | Parse; recovery package **type-checks** against hand-written stubs of the Spring APIs used (found and fixed one type error); not compiled against Spring or run |
| `RecoveryIT`: approve by a separate approver, admin cannot approve, execute, replay, values, credit note, RECOVERY actor, verification PASSED, incident RESOLVED, author-with-permission still refused | IMPLEMENTED | Not yet run |
| Recovery Center UI: incident list, incident detail (plan steps with before → after, prerequisites, outcomes, impact counts and records, earlier plans), approve / reject / execute (typed confirmation) / resume / verify / acknowledge, open incident from the audit drawer | **TESTED** | 11 new Vitest tests (46 total) incl. 6 for the action rules; 7 new screenshots (light, dark, 390 px), no JS errors, no overflow — fixture API responses |

## Phase 7 items

| Item | Status | Evidence |
|---|---|---|
| Benchmark on PostgreSQL 16: PASCAR (application loader SQL + engine) vs naive FK, record-level dependency closure, point-in-time restore (pg_dump/pg_restore); 1k/10k orders × 3 seeds, 100k × 1, three scenarios | **VERIFIED** (synthetic) | `docs/experiments/phase7-benchmark.md`, 21 runs in `raw/phase7-benchmark.jsonl`; PASCAR precision/recall 1.000 in all runs; 106,638 planned values checked, 0 mismatched |
| Execution + verification in the database at up to 64,064 steps | **VERIFIED** (SQL) | 0 records written outside truth, 0 total violations DB-wide, scope exact, chain intact, fresh re-plan 0 automatic steps, in all 21 runs. **Orchestration is a Python replay** of the workflow using the application's SQL; the Java path runs in `RecoveryIT` once the backend builds |
| Concurrent edits during execution | **VERIFIED** (SQL) | 18 runs × 5 edits: 5/5 conflicts detected, 0 updates lost, dependents blocked, fingerprint changed |
| Bug: execution quadratic in plan size (plan JSON re-read on every step lock) | **FIXED** | 97 ms → 0.5 ms per lock on a 6,273-step plan; `LOCK_PLAN_STATUS` (157 SQL checks); adapter type-checks against stubs; pre-fix raw data kept |
| Limit: bulk-appending change events in one transaction is quadratic (chain-head row) | DOCUMENTED | Not on any application path; bulk import must batch commits (275 s → 27 s for 86k events) |

## Phase 8 items

Details and evidence: `docs/security/hardening.md`.

| Item | Status | Evidence |
|---|---|---|
| Security review (STRIDE); open redirect after sign-in fixed (`safeNext`) | **TESTED** | Exploit reproduced in Chromium before the fix; 12 Vitest tests |
| Spring Boot 3.5.6 → 3.5.16 (published Boot / Security advisories) | IMPLEMENTED | Manual advisory check (Maven Central and OSV blocked here); not built |
| Committed JWT test secret removed; random key per test run | IMPLEMENTED | `TestJwtSecret`; old value allow-listed by fingerprint |
| gitleaks over full history + working tree | **VERIFIED** | 0 findings; planted-secret control caught; CI job |
| npm advisories (4 high) fixed | **VERIFIED** | `npm audit` 0; 58 Vitest tests pass; build passes |
| Strict CSP for the SPA; security headers on the API | **TESTED** (SPA) / IMPLEMENTED (API) | `npm run csp-check` in Chromium, both themes, injected-script control blocked; API headers asserted in `ApplicationIT` (not yet run) |
| Authorization static check | **VERIFIED** | 52 endpoints, 0 problems; `docs/security/endpoint-permissions.md`; CI |
| EXPLAIN ANALYZE of 22 hot queries at 100k orders / 850k events; V8 indexes | **VERIFIED** (synthetic) | Audit time window 174 ms → 0.13 ms; invoices list 7.5 ms → 0.04 ms; 170 SQL checks pass |
| External anchoring of the audit chain (Ed25519, external log, V9) | **VERIFIED** (SQL + domain code) | 12 unit tests; end-to-end on PostgreSQL 16: superuser rewrite fools `verify_audit_chain`, anchors report REWRITTEN #1–#20 |
| Anchoring Spring wiring, scheduled job, verification endpoint | IMPLEMENTED | Type-checks against stubs; not run |
| Rate limits (token bucket, 429 + Retry-After) | **TESTED** (limiter) / IMPLEMENTED (filter) | 9 unit tests incl. Retry-After property; `HardeningIT` not yet run |
| Prometheus metrics (protected), OpenTelemetry tracing, trace id in logs | IMPLEMENTED | Not run; no Grafana dashboard committed |
| k6 load test vs NFR-PERF-1 | IMPLEMENTED (script) | `k6 inspect` passes; **not run against ATLAS** — NFR-PERF-1 unverified |
| Dependabot (Maven, npm, Actions, Docker); hadolint | IMPLEMENTED / **TESTED** | hadolint clean |
| ZAP baseline, container scan, backend dependency scan | NOT STARTED | Need a running build |
| Redis | Not added | No measured need (limits are per instance; see hardening §5) |

## Phase 9 items

Nothing in this phase is deployed. "Runs in CI" means the job exists and has not yet run,
because the repository has not been pushed to GitHub.

| Item | Status | Evidence |
|---|---|---|
| Accessibility: axe-core (WCAG 2.1 A/AA) on 20 rendered states (light, dark, 390 px, dialogs) + keyboard-only sign-in | **TESTED** (fixture data) | 0 violations after fixes; found and fixed: badge contrast 4.2–4.4:1 → ≥ 5.6:1, unnamed sign-out button on mobile, wide tables not keyboard-scrollable, **every dialog unnamed** (`aria-labelledby` built from the title text); 3 new unit tests fail on the old code |
| Production web tier (Caddy): SPA, strict CSP, `/api` proxy, `/healthz`, caching, headers | **TESTED** | `scripts/web-tier-check.sh`: 12 checks + the CSP check in Chromium against the real Caddyfile; runs unprivileged on a read-only filesystem |
| Client address for rate limits behind the load balancer | **FIXED + TESTED** | A spoofed `X-Forwarded-For` prefix would have chosen the address Spring rate-limits by; Caddy now resolves the client right to left through trusted proxies and passes one address (3 cases tested) |
| Demo data: `scripts/seed-demo.py` (synthetic, labelled, opt-in, through the API) | **TESTED** (local stack) | 2026-11-06, Docker Compose on the owner's Windows laptop, real backend + PostgreSQL: 1,032 API calls accepted, 180 orders / 123 invoices / 110 issued, planted mistake reaches 29 confirmed orders and 20 issued invoices, audit chain intact over 1,661 events. Earlier flow test against a strict mock caught a 403 bug. Found on Windows: `localhost` stalls (IPv6 first) and system proxies intercept urllib; both fixed |
| **First real build and start of the backend** | **VERIFIED** (start-up only) | 2026-11-06, `docker compose --profile app up --build` on the owner's laptop: compiles, Flyway applies V1–V9, application starts in 6.2 s, serves the SPA through the web tier. The image build skips tests: **`mvn verify` (unit + integration tests, coverage gate) has still not run** |
| End-to-end recovery smoke test through the API (incident → plan → separate approver → execute → verify) | IMPLEMENTED | Runs in CI after the seed; not yet run |
| k6 load test (NFR-PERF-1) through the web tier on demo data | IMPLEMENTED | CI `stack` job; not yet run — **NFR-PERF-1 still unverified** |
| ZAP baseline, Trivy (images + Maven/npm manifests) | IMPLEMENTED | CI; not yet run (scanner databases are blocked in this sandbox) |
| JaCoCo gate: ≥ 80 % lines in recovery, audit, provenance (NFR-QA-1) | IMPLEMENTED | In `mvn verify`; never run (no Maven here). Was listed for Phase 5 but missing from the pom until now |
| AWS infrastructure (`infra/terraform`): ECS Fargate (web + API in one task), RDS PostgreSQL 16 (TLS forced, managed password), ALB (TLS 1.3/1.2) + WAF, Secrets Manager, KMS, EFS anchor log + locked backup vault, flow logs, access logs, CloudWatch alarms, ECR, OIDC deploy role | **VERIFIED** (static only) | `tofu validate` against AWS provider 6.70.0; Checkov 278 passed / 0 failed / 15 skipped with reasons; **never applied, no live URL** |
| Deploy workflow (manual, OIDC, immutable SHA tags, circuit-breaker rollback) | IMPLEMENTED | actionlint clean; never run |
| Prometheus config, 6 alert rules, Grafana dashboard (12 panels), compose `observability` profile | **TESTED** (config) | promtool: config valid, alert unit tests pass (fires and stays quiet on synthetic series), all 12 panel queries parse; never scraped a running backend |
| CI tool downloads (`scripts/ci/fetch-tool.sh`: 8 tools, checksum-verified) | **TESTED** | All 8 fetched and verified here; a tampered download is rejected |
| Committed endpoint-permission table was stale (anchors endpoint missing) | **FIXED** | Regenerated; CI now fails when it drifts |
| Spring Boot 4.1 migration | PLANNED | `docs/architecture/spring-boot-4-migration.md`; needs a green Maven build first |

## ATLAS X items (Milestones 2–9)

Plan: `docs/architecture/atlas-x-integration-plan.md`. Decisions taken with the
plan's recommended defaults:
- provenance confidence as in its §3.3;
- in-memory simulation;
- a delivery-note field on orders;
- no inventory module;
- ablations as redefined in its §3.1.

As with every phase, **`mvn verify` has not run**. The ATLAS X Spring code is
type-checked against stubs only, and `AtlasXIT` has never run.

| Item | Status | Evidence |
|---|---|---|
| V10–V13 migrations (operation column, field-change view, delivery note, conflict reviews, simulation runs/results, integrity rules/versions/runs/incidents/events, permissions) | **TESTED** (SQL) | Applied to PostgreSQL 16; `scripts/verify-schema.sh` passes; `scripts/verify-atlasx-sql.py` runs every ATLAS X statement of the Java source against real tables: 86/86, including append-only triggers, tenant isolation, de-duplication of open findings, and one RUNNING simulation per incident |
| M2 Provenance: `operation` on every new edge; multi-hop lineage with confidence (VERIFIED / DECLARED / INFERRED; required-but-missing = UNKNOWN via `/gaps`); 12-hop and 2,000-edge bounds | **TESTED** (SQL + UI) | SQL harness covers the recursive walk, cycles, minimum depth and confidence by value checks; Spring endpoint type-checked only |
| M3 Field history and timeline (`field_changes` view, `/history` API) | **TESTED** (SQL + UI) | SQL harness; History page shows the field history |
| M3 Temporal conflicts: 7 kinds per field (`ConflictAnalyzer`), append-only reviews, unreviewed conflicts block execution | **TESTED** (engine + SQL) | `ConflictAnalyzerTest` 9 tests; SQL harness for reviews; the execution gate is type-checked only (`AtlasXIT` not run) |
| M3 Delivery note on orders (a real "different field" later update) | **TESTED** (SQL + UI) | SQL harness; 3 UI tests, including a 409 that is not overwritten |
| M4 Counterfactual simulation of 6 strategies, in memory, on one REPEATABLE READ snapshot | **TESTED** (engine) | `CounterfactualSimulatorTest` 10 tests: no input mutation, determinism, size limit, held groups; snapshot loading and persistence are SQL-tested; `SimulationService` type-checked only |
| M5 Optimizer: 8 hard constraints, J = 10·C + 10·U + 2·R + 0.1·T among feasible candidates, risk LOW–CRITICAL with explained factors | **TESTED** (engine) | `RecoveryOptimizerTest` 5 tests |
| M6 Integrity monitoring: 5 rule kinds (order totals, invoice totals, derived values, required provenance, audit chain), versioned rules, scheduled + manual runs, de-duplicated findings that clear themselves, acknowledge/resolve with notes; checks never write business data | **TESTED** (SQL + UI) | SQL harness evaluates every rule on planted violations and clean data; scheduler and service type-checked only; no Prometheus alert rule for findings yet (a counter is exported) |
| M7 Frontend: lineage explorer (graph + table, confidence filter, zoom), field history, incident conflicts tab with reviews, simulation comparison + assessment, Integrity page, delivery note | **TESTED** (fixture data) | `tsc` clean; vitest 76/76 (16 files); axe 0 violations over 25 rendered states (5 new); found and fixed: graph nodes whose accessible name did not contain their visible text, a graph that widened the page on mobile, a link distinguished by colour only |
| M8 Experiment: strategies A–F as ablations, 30 runs + 6 scale runs, oracle cross-checks | **VERIFIED** (synthetic, in memory) | `docs/experiments/atlasx-milestone8.md`; F met every hard constraint in 36/36 runs, no other strategy in any; CI guard `AtlasXExperimentTest` |
| Defect found by M8: a total could disagree with its lines while a review was pending | **FIXED + TESTED** | `PlanGenerator.holdConsistencyGroups`; the simulator and executor now share `waitingSteps` |
| Defect found by M8: simulation was quadratic (≈ 9 s for one strategy at 2,000 orders) | **FIXED + TESTED** | One-pass index: 0.12 s; identical results in all 30 runs; 20,000 orders in ≈ 8 s for all six |
| M9 Prior-art review extended (W3C PROV, why/where provenance, SQL:2011 temporal, Mahif historical what-if, data repair) | IMPLEMENTED | `docs/research/prior-art.md`; no novelty claimed |
| Endpoint permissions (73 endpoints) | **TESTED** | `check-authorization.py`: 0 problems; committed table matches |
| End-to-end through HTTP (`AtlasXIT`: lineage, field history, conflicts, simulation, assessment staleness, integrity, tenant isolation, 403s) | IMPLEMENTED | **Not run** (needs Maven + Docker) |

## Graph Intelligence items (approved decisions, steps 1–4)

Plan: `docs/architecture/graph-intelligence-assessment.md` (all five decisions approved
2026-11-06). As before, **`mvn verify` has not run**: Spring code is type-checked against
stubs, and `GraphIT` plus the new parts of `AtlasXIT` and `RecoveryIT` have never run.

| Item | Status | Evidence |
|---|---|---|
| V14: `platform_audit_events` (append-only), `schema_snapshots` (append-only, one per distinct structure) | **TESTED** (SQL) | `scripts/verify-graph-sql.py` 30/30 on PostgreSQL 16 |
| ER Schema Explorer access: platform admins only, checked in the `users` table on every request (`SchemaAccessPolicy`), 403 + audited denial otherwise; views, version lists, diffs and export requests audited | **TESTED** (SQL + UI) / IMPLEMENTED (HTTP) | SQL harness incl. disabled admins; 3 UI tests (no API call for non-admins, a 403 shown as permission denied); `GraphIT` written, not run. Exports are made in the browser: the audit proves an export was requested, not what was saved |
| Schema introspection from `pg_catalog` (tables, views, columns, keys, indexes incl. partial predicates, checks, triggers without bodies), relationship kinds with evidence, snapshot diff | **TESTED** | Real-catalog check on V1–V15: 41 tables, 63 relationships (56 one-to-many, 2 one-to-one, 3 at-most-one-active, 2 many-to-many INFERRED); 8 unit tests; the ER diagram itself is a later milestone (the page lists real tables and relationships) |
| V15: per-strategy record details (≤ 5,000 per strategy, priority order), exact totals, truncation columns, immutable pairwise comparisons with SHA-256, retention by cascade; identifiers and field names only | **TESTED** (SQL + engine) | ATLAS X harness 106/106 (20 new); 4 new simulator tests incl. limit 3 vs unlimited (identical counts and hashes); experiment's 30 runs unchanged; at 20,000 orders every strategy's details are truncated and labelled partial |
| Record-details and comparison API + UI ("Partial: N of M stored") | **TESTED** (UI) / IMPLEMENTED (HTTP) | 80 endpoints, 0 authorization problems; UI tests; `AtlasXIT` additions not run |
| Simulation cost of record details | MEASURED | 20,000 orders: about 18–28 s per full simulation (8 runs) against 15–19 s before (3 runs), 2-vCPU sandbox, noisy |
| Fixed: strategy D counted the faulty product twice in "records inspected" | **FIXED + TESTED** | No published result used that column |
| `UNAFFECTED` split into `NO_LONGER_AFFECTED`, `NOT_REACHED_PROVENANCE_COMPLETE`, `NOT_REACHED_PROVENANCE_INCOMPLETE` (gaps from the database loader; old stored plans shown as legacy) | **TESTED** (engine + SQL + UI) | 6 new engine tests; drift test keeps the Java, loader-SQL and gaps-endpoint requirement lists identical; loader gap query checked with a planted missing input; `RecoveryIT` updated, not run |
| ER diagram (Schema page, platform admins): one node per catalog table, one edge per relationship (declared solid, inferred dashed, kind labels), ELK layout, key columns with "show all" per table, search, kind filter, hide inferred, focus on a table and its neighbours, relationship inspection, minimap, changes since a stored version marked, SVG/PNG export recorded in the platform audit BEFORE the image is made (no image when that is refused) | **TESTED** (UI) | 3 new tests; Chromium under the production CSP on the real V1–V15 schema (41 tables, 63 relationships): 0 errors, axe 0 violations over 34 states, both exports valid; found and fixed: exported files had no relationship lines (SVG strokes not copied by html-to-image), and an expand button whose accessible name hid its visible text. A full-schema SVG is about 8 MB (embedded fonts) |
| Lineage graph on React Flow 12.12.0 (ELK 0.12.0 layout, html-to-image 1.11.13 export, all lazy-loaded): table, filters, search, details, upstream/downstream kept; graph/table selection synced; progressive 2-hop expansion; 300-node cap with "showing N of M"; minimap; SVG/PNG export | **TESTED** | Existing History tests pass unchanged; 3 new tests (ELK in jsdom, selection sync, expansion + undo, cap); Chromium under the production CSP: 0 console/CSP errors, both exports are valid files, axe 0 violations over 29 states; lockfile committed |

## Later phases

| Phase | Status |
|---|---|
| 2 Identity, tenancy, commerce backend | IMPLEMENTED — 2a and 2b written (tables above); becomes TESTED when CI runs the integration tests |
| 3 Frontend core | **TESTED** (frontend side) — see Phase 3 table; end-to-end against the real API waits on the backend's first build |
| 4 Provenance and audit UI | **TESTED** (frontend + SQL) — see Phase 4 table; outbox deferred |
| 5 PASCAR analysis and planning | **TESTED** (engine, SQL, experiment) — see Phase 5 table; Spring wiring not yet compiled |
| 6 PASCAR execution and verification | **TESTED** (workflow, SQL, UI) — see Phase 6 table; Spring adapters type-checked against stubs only, `RecoveryIT` not yet run |
| 7 Benchmarks | **VERIFIED** (synthetic, PostgreSQL) — see Phase 7 table; execution orchestration replayed in Python |
| 8 Hardening | **TESTED** (tooling, SQL, anchoring, limiter, frontend) — see Phase 8 table; Spring wiring and `HardeningIT` not yet run; k6, ZAP and image scans not run |
| 9 Deployment | IMPLEMENTED (written, statically verified) — see Phase 9 table; **nothing deployed**; every running-stack check waits on CI |

Note on numbers: the pgbench run printed throughput (~1,200 tps) on a
sandbox VM. It is not a benchmark result and must not be quoted as one.
