# ATLAS X: integration plan (Milestone 1)

**Status: implemented (Milestones 2–9), 2026-11-06.** The plan was carried out with its recommended defaults (§10). What is tested and what is not is tracked in `docs/STATUS.md` ("ATLAS X items"). The green baseline (Milestone 1.5) and the Spring Boot 4.1 migration were **not** done first: `mvn verify` still has not run. The text below is the original plan, kept unchanged.

This plan maps the ATLAS X brief onto the ATLAS repository as it stands at commit
`b1289cb`, after reading the schema (V1–V9), the provenance, audit and recovery packages,
the PASCAR engine, the API, the frontend and the experiments. One finding shapes everything
below: **about half of what the brief asks for already exists under other names**, and the
brief's own rules forbid duplicating it ("reuse existing tables", "do not duplicate audit
logging or record version history"). This plan therefore *extends* what is there, and says
plainly where a request is already met, where it would be duplication, and where the
brief's assumptions do not match this codebase.

---

## 1. Current architecture

| Layer | What exists |
|---|---|
| Shape | One Spring Boot 3.5 service: a modular monolith (`identity`, `organization`, `catalogue`, `sales`, `billing`, `audit`, `provenance`, `recovery`, `common`), JdbcClient over PostgreSQL 16, Flyway V1–V9. React 18 + TypeScript SPA behind a Caddy web tier |
| Change capture | `ChangeRecorder` writes one `change_events` row per record version: full `before_state` / `after_state` (jsonb), `changed_fields`, actor kind and user, `txn_id`, `correlation_id`, reason, time. Hash-chained per tenant, append-only (triggers), and externally anchored (Ed25519, V9) |
| Provenance | `ProvenanceRecorder` writes `provenance_edges` **at write time, in the same transaction**: source (type, id, **version**, **field**) → target (type, id, **version**, **field**), `relation` ∈ {DERIVED_VALUE, AGGREGATED_INTO, COPIED_INTO}, `evidence` ∈ {RECORDED_AT_WRITE, BACKFILLED}, `txn_id`. Deferred FKs make both endpoints real recorded versions, so a fabricated edge cannot commit. Captured by real operations: pricing an order line (product.unit_price → line.unit_price), order totals (line.line_total → order.total_amount), invoicing (line → invoice line → invoice total), and every recovery write |
| PASCAR engine (`recovery/engine`, framework-free) | `ImpactAnalyzer`: field-level taint search along edges, same-version formulas and version carry-forward; cycle-safe (each taint visited once); classifications ORIGIN, DIRECTLY/INDIRECTLY_AFFECTED, UNAFFECTED, **CONFLICTED** (a tainted field was later changed by a person), **REQUIRES_MANUAL_REVIEW** (only backfilled edges, a cycle, or no repair rule), NON_REVERSIBLE (issued invoices → credit notes). `PlanGenerator`: **field-level** steps (RESTORE_FIELDS, UPDATE_FIELDS, ISSUE_CREDIT_NOTE, MANUAL_REVIEW, ...), each with `expectedVersion`, before → after per field, inputs, dependencies, risk (LOW / MEDIUM / HIGH) and reason; invariants; a plan fingerprint |
| Workflow (`recovery/execution`) | Approval bound to the fingerprint; the author cannot approve; stale-plan check at approval and at execution; one transaction per step with optimistic version checks; blocking of dependants; manual-step acknowledgement; crash-safe resume; idempotent replay; verification (FIELD_EQUALS, TOTAL_EQUALS_SUM_OF_LINES, CREDITED_AT_LEAST, LARGE_COMPENSATION_REVIEWED, SCOPE, and a fresh re-plan that must find nothing) |
| Experiments | Phase 5 (in memory, 18 runs) and Phase 7 (PostgreSQL, 21 runs, up to 100k orders): PASCAR vs **naive FK traversal**, **record-level dependency closure** and **point-in-time restore**; precision/recall against an oracle that never reads provenance; contention runs (people editing during execution). All synthetic and labelled |
| Frontend | Audit log with diff drawer, History page (version timeline + **one-hop** upstream/downstream provenance), Recovery list, Incident page (impact, plan steps, approve/execute/verify, earlier plans) |
| Evidence status | Engine, workflow, SQL and frontend: TESTED. Spring adapters: compiled and started for the first time on 2026-11-06 (local Docker); **`mvn verify` (integration tests) has never run** |

## 2. What already supports each module

| Brief asks for | Already in ATLAS | Verdict |
|---|---|---|
| **M1 Causal provenance:** field + version lineage, captured from real operations | `provenance_edges` + `ProvenanceRecorder` + FK guarantees; multi-input (AGGREGATED_INTO), multi-step chains, both directions, cycle-safe traversal (in the engine) | **Mostly exists.** Missing: provenance confidence labels in the brief's sense, the business *operation* name, multi-hop lineage queries over the API, completeness detection (UNKNOWN), a graph explorer |
| Actor, transaction, correlation id, timestamp per edge | On `change_events`, joined by `txn_id` (every edge has one) | **Exists by join**; do not copy these columns onto edges |
| Source value or value reference | The edge points at a recorded version; the value is in that version's `after_state` | **Exists** (a reference, as the brief prefers); no value copies |
| Field-level history (entity, field, previous/new value and version, source, actor, time, txn, reason) | Every field of it is in `change_events` (full before/after per version + `changed_fields`) | **Exists. A `field_change_history` table would be the duplication the brief forbids.** Add a read-side SQL view/query instead |
| **M2 Temporal conflicts:** overlapping later edits, stale plans, field-level recovery with expected version, human review | CONFLICTED / REQUIRES_MANUAL_REVIEW; field-level steps with `expectedVersion`; later edits to *other* fields are preserved by construction (steps write only tainted fields); fingerprint staleness; per-step version check at execution | **Partly exists.** Missing: the brief's explicit conflict taxonomy (same-field vs different-field vs source-version mismatch vs concurrent vs unknown origin), per-action evidence ("observed value", "source of proposed value"), persisted review decisions, a timeline API and inspector UI |
| **M3 Counterfactual simulation** | Nothing in the product. The Phase 7 harness runs baselines on throwaway database copies, outside the application | **Missing**; the main new engineering |
| **M4 Risk-aware optimization** | One deterministic plan per incident; risk LOW/MEDIUM/HIGH per step with reasons; approval required above LOW or above 25 steps | **Partly exists.** Missing: multiple candidate strategies, hard-constraint validation as a separate stage, the J(P) score, CRITICAL, explanations as data, plan comparison |
| **M5 Continuous integrity monitoring** | The verifier's invariant kinds (TOTAL_EQUALS_SUM_OF_LINES, ...) and the audit-chain and anchor checks exist, but run only after a recovery or on demand | **Missing** as a product feature; the rule *logic* can reuse the invariant code |

## 3. Where the brief does not match this codebase

These need a decision, not a silent workaround.

1. **The "original ATLAS without causal provenance" ablation (A) does not exist.** ATLAS was
   built around field-level provenance from Phase 1. The honest ablations are
   *components of PASCAR switched off*: (A) naive FK traversal and record-level closure
   (both already measured), (B) PASCAR without carry-forward conflict detection,
   (C) PASCAR with conflict detection, (D) + simulation-based plan selection, (E) everything.
   I propose redefining the ablation table this way and saying so in the write-up.
2. **The brief's examples use fields the domain does not have.** Orders have no shipping
   address, and the `inventory` package is empty (no stock table). For the
   "different-field legitimate update" case to be real rather than staged, the domain needs
   at least one person-editable field on orders (e.g. `delivery_note` or a shipping
   address) and line quantity edits on drafts. **Integrity rule 2 (stock constraints) is not
   applicable** until an inventory module exists; I propose building it only if you want
   it, as its own milestone.
3. **"VERIFIED vs DECLARED provenance" needs a precise meaning**, or the labels become
   decoration. Proposal:
   - DECLARED: recorded at write time by the operation that computed the value (today's RECORDED_AT_WRITE).
   - VERIFIED: declared **and** a value check over the recorded snapshots confirms it (the target value equals the source value for DERIVED/COPIED, or the sum over its inputs for AGGREGATED). Computed, never asserted.
   - INFERRED: added afterwards by a backfill heuristic (today's BACKFILLED); never used for automatic recovery (already the rule).
   - UNKNOWN: a target field that a *provenance requirement* says must have inputs but has none at that version (a completeness gap, reported, never invented).
4. **Requested class names** (ProvenanceService, ProvenanceCaptureService, ProvenanceGraphService,
   DataLineageRepository, ProvenanceQueryService, ...: about 25 new classes across the brief).
   Several would be empty pass-throughs over existing classes (`ProvenanceRecorder` *is* the
   capture service). I will create a class where it holds real logic and reuse or rename
   otherwise, and list the mapping in each milestone's notes.
5. **Simulation on a full database copy per candidate is the wrong default** for an
   application (minutes and gigabytes per run, see the Phase 7 dump/restore timings).
   See §5; the default is an in-memory replay of the real workflow over a consistent
   read-only snapshot, with a database clone reserved for experiments.
6. **Novelty.** The existing prior-art review covers selective recovery (Ammann et al.,
   Akkuş & Goel, Retro, Warp). Simulation and plan optimisation need more: Warp already
   re-executes in a "time-travel" copy, and data-provenance work (why/where-provenance,
   W3C PROV) is directly relevant. The review must be extended before any claim; I will
   not use "novel" anywhere until then.

## 4. Database changes (proposed)

All through Flyway (V10 onwards), tenant-scoped (`organization_id` + composite FKs, as everywhere),
append-only where history matters.

| Proposed in the brief | Decision | Why |
|---|---|---|
| provenance_records / inputs / outputs | **Extend `provenance_edges`** instead: add `operation varchar(64)` (e.g. `order.add_line`, `invoice.create_from_order`, `recovery.step`), widen `evidence` to the confidence vocabulary via a mapping (keep the column values backward compatible). A "provenance record" = all edges of one `txn_id` + operation; a view exposes that grouping | A second graph table would duplicate the first |
| field_change_history | **SQL view** over `change_events` (one row per changed field per version, with previous/new value via jsonb) | Duplication otherwise |
| `provenance_requirements` (new, not in the brief) | Per organization: "target (type, field) produced by operation X must have inputs from (type, field)"; versioned | Needed to detect UNKNOWN provenance honestly |
| temporal_conflicts | **New** `recovery_conflicts`: per incident/plan and record field: classification, evidence (versions, actors, times), review status, reviewer, decision | Today conflicts live only inside the plan's impact JSON and cannot be reviewed individually |
| simulation_runs, simulation_scenarios, simulation_results (+ snapshots) | **New**: run (incident, snapshot point = chain index + txid, seed, config, status, timings, resource use), scenario per strategy, result metrics per scenario. No snapshot *data* table: the snapshot is identified by chain index and re-read, not copied | Reproducible without storing production data twice |
| recovery_plan_versions | **Exists** as `recovery_plans` (immutable, SUPERSEDED chain per incident). Add `strategy` and `score` columns | Duplication otherwise |
| recovery_risk_assessments, recovery_action_evaluations | **New** `recovery_plan_assessments` (plan-level score breakdown, constraint results, risk with factors) and per-step evaluation rows | Explanations as data |
| integrity_rules, integrity_rule_versions, integrity_check_runs, integrity_incidents | **New**, as proposed; incident uniqueness on (org, rule, subject, open) so a repeated finding updates `last_seen_at` instead of duplicating | As the brief asks |
| Domain additions (see §3.2) | `orders.delivery_note` (person-editable); optional inventory module | Real "different field" edits |

Retention: simulation results and integrity check runs are operational data (configurable
retention, default 180 days); conflicts, assessments and incidents are kept with the plans
they explain (no deletion, like the rest of the recovery trail).

## 5. Counterfactual simulation: proposed mechanism

**Default: deterministic in-memory replay over a consistent snapshot.**
1. Open one `REPEATABLE READ, READ ONLY` transaction and load the incident's subgraph
   (the existing loader SQL already does exactly this) plus the current state of every
   record a candidate could touch. Record the snapshot point (audit chain index).
2. For each candidate strategy, run the **real** `RecoveryWorkflow` and verifier against an
   in-memory `RecordStore` built from that snapshot. `RecoveryWorkflowTest` already runs the
   workflow on such a store; this turns that test double into a product component.
3. Production cannot be modified: the snapshot connection is read-only, and the simulator
   has no write port at all (enforced by a test that runs it on a read-only database role).
4. Reproducible: same snapshot point + config + seed → same results (checked by a test).
5. Limits: maximum records and steps per run, a time budget, one run per incident at a time.

**For experiments only: an isolated database clone** (`CREATE DATABASE ... TEMPLATE` or
`pg_restore` of a synthetic dataset), as the Phase 7 harness already does. It measures real
SQL execution cost, which the in-memory replay cannot. Never pointed at tenant data.

What is honest to compare per incident (the brief's A–F):
- A full restore: modelled (records that would be rewound), not executed.
- B naive FK, C record/version-level: from the existing baseline queries, re-implemented in Java.
- D field-level without provenance, E provenance-aware: PASCAR with or without its components.
- F conflict-aware optimised: E plus the optimiser.
Each reports "not applicable" when a strategy cannot express the incident (e.g. a full restore with issued invoices).

## 6. API changes (adapted to the existing conventions)

Existing endpoints stay unchanged. New ones (all tenant-scoped, permission-checked, and added to
`check-authorization.py` and the generated permission table):

| Endpoint | Permission | Notes |
|---|---|---|
| `GET /api/v1/provenance/{type}/{id}/lineage?version=&direction=&depth=` | audit.read | Multi-hop, depth-limited, cycle-safe; nodes + edges with confidence |
| `GET /api/v1/provenance/{type}/{id}?direction=` | audit.read | **Exists** (one hop); the brief's `/upstream` and `/downstream` map onto it |
| `GET /api/v1/history/{type}/{id}/fields`, `/timeline` | audit.read | From the field view |
| `GET /api/v1/recovery/incidents/{id}/conflicts`, `POST .../conflicts/{conflictId}/review` | audit.read / recovery.approve | Under incidents (matches the existing routes) rather than `/conflicts/{incidentId}` |
| `POST /api/v1/recovery/incidents/{id}/simulations`, `GET /api/v1/recovery/simulations/{id}`, `.../comparison` | recovery.create / audit.read | Plural nouns as elsewhere |
| `POST /api/v1/recovery/incidents/{id}/plans?strategy=optimized`, `GET /api/v1/recovery/plans/{id}/assessment` | recovery.create / audit.read | Optimisation produces a normal plan (same approval and execution path); risk and explanation in one resource |
| `GET/POST /api/v1/integrity/rules`, `POST /api/v1/integrity/checks`, `GET /api/v1/integrity/incidents[/{id}]`, `POST .../resolution` | new `integrity.read` / `integrity.manage` | Two new permissions |

## 7. Frontend plan

Same design system, navigation and data layer (TanStack Query, `api` client, zod-validated forms).
- **History page → Provenance Explorer**: multi-hop graph (React Flow or an SVG layered layout; library chosen at Milestone 7 by bundle size and accessibility), field-level edges, confidence legend, node drawer, depth/direction filters. Only edges the API returns.
- **Incident page → Conflict Inspector tab**: per-field timeline, diff, classification, evidence, review action.
- **Incident page → Simulations tab**: run, status, side-by-side metrics, invariant results, "not applicable" shown as such.
- **Plan view → assessment**: score breakdown, constraint pass/fail, risk factors, plan versions, stale warning (exists, extend).
- **New Integrity section**: rules (versioned), runs, incidents with severity filter, link into provenance and recovery.
Each page gets fixture-free tests against mocked API responses (as now), axe checks, and screenshots.

## 8. Risks

| Risk | Mitigation |
|---|---|
| **The integration tests have never run.** Building five modules on an unverified base breaks the brief's own rule ("do not move on with unresolved critical failures") | **Milestone 1.5: green baseline** (`mvn verify` on the laptop or in CI) before any backend change |
| Spring Boot 3.5 is out of open-source support; migrating after ATLAS X doubles the work, and Jackson 3 touches plan JSON and fingerprints | Decide the order (question 1 below) |
| Fingerprint compatibility: new fields in plans must not change the fingerprint of stored plans | Version the fingerprint input; test stored-plan fingerprints across the change |
| Simulation scale (100k-order incidents) | Limits per run; in-memory replay costs are measurable on the Phase 7 datasets |
| Scope: the brief is several months of work for one person | One milestone at a time, each ending green; stop points below |
| Overclaiming | Statuses as now; the prior-art review is extended before any claim; experiments before any "improvement" |

## 9. Order and complexity

| # | Milestone | Main work | Complexity |
|---|---|---|---|
| 1.5 | Green baseline | Run `mvn verify` + the CI stack job; fix what fails | S–M (unknown until run) |
| (opt.) | Spring Boot 4.1 | Per `spring-boot-4-migration.md` | M |
| 2 | Provenance confidence + lineage | `operation` column, confidence computation (value checks), requirements + UNKNOWN, lineage query and API, cross-tenant and cycle tests | M |
| 3 | Temporal conflicts | Field view, conflict taxonomy on top of the analyser, `recovery_conflicts` + review, domain field for real different-field edits, timeline API | M |
| 4 | Simulation | In-memory `RecordStore` from a read-only snapshot, strategy generators (B–F), metrics, persistence, isolation and reproducibility tests | L |
| 5 | Optimisation | Hard constraints, J(P) with documented weights, CRITICAL, explanations, deterministic selection | M |
| 6 | Integrity monitoring | Rules (reusing invariant code), scheduler, de-duplicated incidents, alerts as metrics + in-app list | M |
| 7 | Frontend | Five views above | L |
| 8 | Experiments | Ablations (redefined, §3.1), simulation overhead, conflict precision/recall with seeded legitimate edits, all synthetic | L |
| 9 | Docs and integration | Extended prior-art review, status, write-up | M |

S = a day or two of focused work, M = about a week, L = two weeks or more (for one person,
rough).

## 10. Decisions needed before Milestone 2

1. **Order:** green baseline → (Boot 4.1 migration?) → Milestone 2. Recommendation: green
   baseline first, then the Boot 4.1 migration, then ATLAS X.
2. **Provenance confidence definitions** as in §3.3.
3. **Simulation default** = in-memory replay over a read-only snapshot (§5); database clones for experiments only.
4. **Domain additions:** an editable order field for real different-field conflicts (recommended); an inventory module (only if you want integrity rule 2).
5. **Ablations** redefined as in §3.1.
