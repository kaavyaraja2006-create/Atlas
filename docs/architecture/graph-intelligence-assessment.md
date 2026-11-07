# ATLAS Graph Intelligence Engine: architecture assessment (first task)

**Status: APPROVED 2026-11-06; steps 1–4 and the ER diagram (G6 front end) implemented 2026-11-07** (permissions and
schema snapshots as V14, strategy record details as V15, the not-reached classes, the
React Flow lineage). Evidence and what is not yet run: `docs/STATUS.md`, "Graph
Intelligence items". The rest of this document is the original assessment. Two
deviations: migrations are numbered in build order (V14 = audit/snapshots, V15 = record
details), and the layout library is ELK 0.12.0 (as specified at approval), not dagre.

Every fact about the current system below was checked against the
repository at commit `d48e874` (ATLAS X Milestones 2–9) and, for the schema,
against a scratch PostgreSQL 16 database built from migrations V1–V13.

## 0. Summary

- **About half of the brief is already built.** ATLAS already has:
  - field-level provenance between record versions, with
    VERIFIED / DECLARED / INFERRED confidence and UNKNOWN gaps;
  - a multi-hop lineage API, including the brief's upstream/downstream,
    multiple inputs and transformation kinds;
  - BFS taint spreading, iterative Tarjan cycle detection and Kahn
    topological ordering inside PASCAR;
  - a stored impact analysis with a path and a reason for every record;
  - field-level conflicts;
  - a counterfactual simulator whose six strategies match Module 5's six
    names one for one;
  - an SVG lineage graph in the UI.
- **What is missing:**
  - a reusable graph library with tested, explainable analytics;
  - graph-shaped APIs (nodes, edges, paging, expansion, paths, comparison);
  - per-strategy *record sets* from the simulator, which today returns counts
    only;
  - schema introspection and schema snapshots;
  - a shared React Flow canvas and five explorers on it.
- **No new database engine is needed now.** The recursive SQL that loads a
  whole incident graph at 100,000 orders (850,000 versions) took 6.0–6.9 s in
  Phase 7, and graph queries for the UI are bounded far below that. Neo4j is
  not proposed (§8).
- **Database changes are small:**
  - one migration adds per-strategy record sets and three result metrics for
    simulations;
  - one adds schema snapshots;
  - possibly one permission.

  No graph data is materialized; graphs are built from the existing tables
  on request.
- Five decisions are needed before implementation (§10).

## 1. Current database schema (discovered from the catalog, not from docs)

Built from V1–V13 in a scratch database:
- **36 tables and 1 view** (`field_changes`).
- **57 foreign keys:**
  - 52 are composite `(organization_id, …)`, so a row can only point inside
    its tenant;
  - 2 are DEFERRABLE (provenance edges → change events).
- **Constraints:** 36 primary keys, 31 unique constraints, 111 checks.
- **101 indexes and 16 user triggers** (mostly append-only guards).

| Area | Tables |
|---|---|
| Identity and tenancy | `users`, `organizations`, `organization_members`, `roles`, `permissions`, `role_permissions`, `member_roles`, `refresh_tokens`, `login_events`, `idempotency_keys` |
| Commerce | `products`, `inventory_items` (table exists since V2; no application code uses it), `customers`, `orders`, `order_lines`, `invoices`, `invoice_lines`, `credit_notes`, `document_counters` |
| Audit and provenance | `change_events` (hash-chained, one row per record version), `audit_chain_heads`, `audit_anchors`, `provenance_edges` (field → field between two versions; relation, evidence, operation), view `field_changes` |
| Recovery | `recovery_incidents`, `recovery_plans` (impact + plan JSON), `recovery_decisions`, `recovery_actions`, `recovery_verifications`, `recovery_conflict_reviews`, `simulation_runs`, `simulation_results` |
| Integrity | `integrity_rules`, `integrity_rule_versions`, `integrity_check_runs`, `integrity_incidents`, `integrity_incident_events` |

### What the catalog says about relationship kinds

A first discovery query shows Module 1 needs care.
- **True one-to-one (2):**
  - `inventory_items → products`: the foreign key is covered by a full
    unique constraint;
  - `audit_chain_heads → organizations`: the primary key is the foreign key.
- **"At most one active", not one-to-one (3):**
  - `invoices → orders` (`WHERE status <> 'VOID'`);
  - `recovery_incidents → change_events` (one active incident per change);
  - `simulation_runs → recovery_incidents` (one RUNNING run).

  A naive "unique index covers the foreign key" test labels all three as
  one-to-one, which is wrong. The explorer must read `pg_index.indpred` and
  show them as "one-to-many, at most one where `<predicate>`".
- **Many-to-many (2 junction tables):**
  - `member_roles`: memberships ↔ roles;
  - `role_permissions`: roles ↔ permissions.

  Nothing in the catalog *declares* a junction table, so this is a
  structural inference. It is shown as **inferred**, never as declared.
- **Polymorphic references.** `change_events.entity_type/entity_id` and both
  ends of `provenance_edges` point at business tables through a type column,
  with no foreign key. These are the only places a reference can be
  "orphaned", and they are application-level, not catalog-level. Every
  enforced foreign key makes catalog orphans impossible (unless a constraint
  is `NOT VALID`; none is today).
- **Schema versions.** At runtime Flyway's `flyway_schema_history` records
  which migrations ran, but it does not hold the resulting schema. To compare
  schema versions, a catalog snapshot has to be stored per version (§4).

## 2. Existing provenance and dependency capabilities

| Capability | Where | Brief module |
|---|---|---|
| Field-level edges between record versions, written in the same transaction as the business write; DB rejects edges to versions that were never recorded | `ProvenanceRecorder`, V3, ADR-0003 | M2 |
| Relation kinds `DERIVED_VALUE`, `COPIED_INTO`, `AGGREGATED_INTO` (multiple inputs); business `operation` per edge | V3, V10 | M2 "transformation and calculation", "multiple inputs" |
| Confidence computed from the stored values: VERIFIED (values agree, sums included), DECLARED (recorded but values don't confirm), INFERRED (backfilled); UNKNOWN = a value written without its required provenance (`/provenance/gaps`) | `ProvenanceQueries` | M2 evidence classes |
| Multi-hop lineage (recursive CTE, min depth per edge, depth ≤ 12, ≤ 2,000 edges, tenant filter on every join) | `GET /provenance/{type}/{id}/lineage` | M2 upstream/downstream, direct/indirect |
| One-hop provenance | `GET /provenance/{type}/{id}` | M2 |
| Field history and timeline | `GET /history/{type}/{id}/fields`, `/timeline` (V10 view) | M4 node details |
| Impact analysis: BFS taint spread with parent pointers (= the path), classification with reason, untainted siblings, cycles by iterative Tarjan | `ImpactAnalyzer` | M3/M4 |
| Plan: Kahn topological order (deterministic, cycle members last), dependencies, consistency groups | `PlanGenerator` | M3 topological sort |
| Field conflicts (7 kinds) with reviews | `ConflictAnalyzer`, `ConflictService` | M4 "conflicting records" |
| Six strategies on an isolated in-memory snapshot (REPEATABLE READ, read-only), optimizer, risk | `CounterfactualSimulator`, `RecoveryOptimizer`, `SimulationService` | M5 |
| Integrity rules over totals, derived values, provenance and the audit chain | `IntegrityRepository`, `IntegrityService` | M6 explorer 5 |

**Module 5 names already match the simulator's strategies, one for one:**

| Brief | ATLAS |
|---|---|
| Full restoration | A `FULL_RESTORE` |
| Dependency-based restoration | B `NAIVE_FK_ROW` |
| Version-based restoration | C `VERSION_ROW_RESTORE` |
| Field-level selective recovery | D `FIELD_LEVEL_FK` |
| Provenance-aware selective recovery | E `PROVENANCE_CONFLICT_BLIND` |
| Conflict-aware optimized recovery | F `PROVENANCE_CONFLICT_AWARE` |

## 3. Graph functionality already implemented, and gaps against the brief

| Brief item | State today | Gap |
|---|---|---|
| BFS | Inside `ImpactAnalyzer.spread`; the lineage CTE is a BFS in SQL | Not reusable or separately tested |
| DFS | — | Missing (needed for path enumeration and DFS order explanations) |
| Cycle detection | Iterative Tarjan inside `ImpactAnalyzer` | Not reusable; cycles not returned by any API |
| Topological sort | Kahn inside `PlanGenerator` | Not reusable |
| Shortest dependency path | Impact `path` = BFS parent chain (a shortest path in hops) | No API for "path from A to B" |
| Connected components | — | Missing |
| Downstream impact / upstream sources | Lineage API (edges only); impact counts | No node-level aggregation, no explanation |
| Dependency depth | Per edge (`depth`) | No per-node/whole-graph summary |
| Blast radius | Impact counts per classification | No by-type / money summary; no "lower bound when gaps exist" |
| Graph version comparison | — | Missing (lineage as of two times; impact of plan N vs N+1; schema A vs B) |
| Path inspection, completeness | Gaps endpoint (tenant-wide) | Gaps not attached to the nodes of a lineage graph |
| Historical lineage comparison | — | Missing |
| Paging / progressive expansion | Hard `LIMIT 2000` + `truncated` flag | No cursor; no "expand this node" |
| Per-strategy record sets for graph comparison | Simulator returns **counts only** | Must return which records/fields each strategy touches |
| "Fields proposed", "unresolved dependencies", "preserved later changes" | `fieldWrites` exists; the other two are not stored | New metrics |
| UI graph | Hand-written SVG lineage graph (`LineageExplorer`) with zoom, confidence filter, table view | No pan, minimap, layout engine, export; one explorer only |
| ER explorer | — | Missing entirely (catalog introspection, snapshots, diff, export) |
| Integrity dependency explorer | — | Missing; rule → table/field dependencies are only implicit in SQL |

## 4. Missing backend components

1. **`com.atlas.graph` (framework-free).**
   - A generic directed graph with BFS, DFS, iterative Tarjan SCC, Kahn
     topological sort, BFS shortest path and k-shortest-by-hops enumeration
     (bounded), weakly connected components (union-find), depth layering,
     blast radius and a diff.
   - Every algorithm returns an *explanation*: the visit order, the witness
     path, or the cycle members.
   - Each algorithm is tested on known fixtures. Two of them, Tarjan and
     Kahn, are **moved out of** `ImpactAnalyzer` and `PlanGenerator`
     instead of duplicated (rule 3). The Phase 5 and Milestone 8
     experiments must give byte-identical results before and after the move.
2. **`GraphService` (Spring):** builds graphs from the existing tables.
   - **Lineage graph:** nodes = record versions, field ports on each node,
     edges with confidence. Includes keyset paging, node expansion, gaps
     attached to nodes, cycles, paths, and comparison "as of" two audit-chain
     positions.
   - **Impact graph:** built from the stored impact JSON, the plan, conflicts
     and gaps. Node details reuse the history, provenance and conflict
     services.
   - **Strategy graphs:** read from the stored simulation record sets.
   - **Integrity graph:** rule → declared dependencies → findings →
     subjects.
3. **`SchemaIntrospector`:** reads `pg_catalog` / `information_schema` for the
   application schema only: tables, columns, primary keys, foreign keys
   (including composite and deferrable), unique and partial-unique indexes,
   checks, triggers. It classifies relationships by the rules in §1.
   `SchemaSnapshotJob` stores a canonical snapshot, with its hash, whenever
   the Flyway version changes at start-up.
4. **Simulator extension:** each `StrategyResult` gains a bounded list of
   `{record, action, fields}`, where action is MODIFY / DELETE / COMPENSATE /
   REVIEW / HELD. It also gains `unresolvedDependencies` (steps held or
   blocked) and `preservedLaterChanges` (later legitimate values kept).
5. **Declared integrity dependencies:** each rule kind in `IntegrityService.KINDS`
   declares the tables and fields it reads. A test runs `EXPLAIN (VERBOSE)` on
   each rule's SQL and fails if the plan reads a relation the declaration
   omits, so the declaration cannot drift from the code.

## 5. Required database changes

| Migration | Change | Why |
|---|---|---|
| V14 | `simulation_result_records` (org, run, strategy, entity_type, entity_id, action, fields text[]); composite FK to `simulation_runs`; ON DELETE CASCADE; index (org, run, strategy). `simulation_results` gains `unresolved_dependencies`, `preserved_later_changes`, `records_truncated` (nullable for runs before V14) | Module 5 compares *which* records each strategy touches. The snapshot is not kept, so the sets must be stored when the simulation runs; they cannot be recomputed later after the data changes |
| V15 | `schema_snapshots` (global, not tenant data): flyway_version, captured_at, sha256, canonical catalog JSON; unique (sha256) | Module 1 schema-version comparison and change detection |
| V15 (decision 1) | Permission `schema.read`, or none if platform-admin-only | ER explorer access |
| — | No tables for graphs themselves | Graphs are computed from `provenance_edges`, `change_events`, the plan and the impact JSON. Materializing them would create a second source of truth |
| — | Index review in G8 | `EXPLAIN ANALYZE` of every new graph query at 100,000 orders before adding any index (as in Phase 8) |

**Storage bound for V14.** At 20,000 orders, strategy A touches about 100,000
records. The proposal is a cap per strategy (default 5,000 records), a
`records_truncated` flag, and counts that stay exact. Old runs are deleted
by the existing retention job.

## 6. Required API endpoints

Everything is new under `/api/v1/graph`. Existing endpoints are unchanged,
so `/provenance/.../lineage` keeps working (rule 9). All graph endpoints:
- are tenant-scoped (organization id taken from the authenticated tenant
  context, used in every SQL join);
- return 404 for another tenant's ids;
- are bounded (depth ≤ 12, page ≤ 500 nodes, statement timeout).

| Method | Path | Permission | Returns |
|---|---|---|---|
| GET | `/graph/lineage/{type}/{id}?version&direction&depth&field&cursor` | audit.read | nodes (record versions with field ports, gap markers), edges (relation, operation, confidence, values), analytics summary (depth, components, cycles, blast radius as a lower bound when gaps exist), next cursor |
| POST | `/graph/lineage/expand` `{nodes[], direction}` | audit.read | one more hop from the given nodes (progressive expansion) |
| GET | `/graph/lineage/{type}/{id}/paths?version&to=type:id@v&max=5` | audit.read | shortest path(s) by hops, with each edge's evidence; "no recorded path" when none |
| GET | `/graph/lineage/{type}/{id}/compare?version&asOfA&asOfB` | audit.read | edges added and removed between two audit-chain positions |
| GET | `/graph/incidents/{id}/impact?plan&cursor&class` | audit.read | impact nodes with classification, reason, path and conflicts, the recovery step per node, unknown-provenance markers |
| GET | `/graph/incidents/{id}/impact/nodes/{type}/{entityId}` | audit.read | fields, versions, change history, provenance paths, conflicts, planned action, and why |
| GET | `/graph/simulations/{id}/strategies/{strategy}?cursor` | audit.read | the strategy's record set as a graph over the same nodes, plus every metric of Module 5, including measured duration |
| GET | `/graph/simulations/{id}/compare?a&b` | audit.read | records only A touches, only B touches, both; field differences |
| GET | `/graph/integrity` | integrity.read | rules → declared tables/fields → open findings → subjects |
| GET | `/graph/schema?version` | decision 1 | tables, columns, keys, indexes, constraints, relationships with kind and evidence (declared by FK / inferred junction / partial unique) |
| GET | `/graph/schema/versions`, `/graph/schema/diff?from&to` | decision 1 | snapshots; added/removed/changed tables, columns, constraints, indexes |
| POST | `/graph/analytics` `{source, algorithm, args}` | per source | runs one algorithm on a bounded graph from one of the sources above, with its explanation. Exposes the library for inspection; never tenant-wide (§8) |

The authorization checker (`scripts/check-authorization.py`) and the endpoint
table are extended automatically; CI already fails on drift.

## 7. Frontend integration plan

- **Library.**
  - `@xyflow/react` (React Flow), pinned at **12.12.0** (released
    2026-09-24). Not 12.12.1, which is four days old, and not the 13.x
    pre-release.
  - `@dagrejs/dagre` 3.x for layered layout of the ER diagram.
  - Lineage and impact keep the existing tested column-by-hop layout
    (`layoutLineage`).
  - `html-to-image`, pinned, for PNG export; SVG export from the rendered
    graph.
  - All graph routes are lazy-loaded. This keeps the main bundle unchanged
    and also addresses the existing Vite chunk-size warning.
- **One `GraphCanvas` component** used by all five explorers:
  - pan and zoom, minimap, controls, legend;
  - search with highlight and focus;
  - filters by type, confidence and classification;
  - a details side panel (the existing `Modal side`);
  - edge labels with relation/operation, theme colours from the existing
    tokens (light and dark);
  - loading, empty, error and **permission-denied** states (a 403 becomes its
    own state, not a generic error).
- **Large graphs:**
  - server paging;
  - "expand" on a node;
  - React Flow `onlyRenderVisibleElements`;
  - a hard client cap (500 nodes rendered), with a visible "showing N of M"
    and a filter prompt.
- **Explorers:**
  1. **ER Schema Explorer**, at `/graph/schema` (admin).
  2. **Causal Lineage Explorer.**
     - It replaces the graph view of today's `LineageExplorer` on the History
       page (decision 3), keeping its table view, confidence filter and
       tests.
     - It adds paths, completeness, cycles and the as-of comparison.
  3. **Impact Analysis Explorer:** a new tab on the incident page.
  4. **Recovery Graph Comparison:** inside the existing Simulation tab, A–F
     side by side or as a diff.
  5. **Integrity Dependency Explorer:** a tab on the Integrity page.
- **Honesty in the UI:**
  - Confidence is drawn with both line style and label, never colour alone
    (as now).
  - INFERRED edges are never drawn solid.
  - UNKNOWN appears only as a **gap marker on a node** ("a required input
    of this field was never recorded"), never as an edge. An UNKNOWN edge
    would draw a relationship nobody recorded, which the brief's own rule 5
    forbids.
  - "Verified unaffected" becomes two classes:
    - *not reached, provenance complete for this record*;
    - *not reached, provenance incomplete*.

    No recorded path proves "unaffected" only when the record's required
    edges all exist (decision 5).
- **Accessibility:**
  - every graph has an equivalent table, as today;
  - keyboard focus moves through nodes;
  - the axe run is extended to every explorer, light and dark;
  - the CSP check runs on each explorer (React Flow sets styles through
    CSSOM, which `style-src 'self'` allows; this must be shown by
    `npm run csp-check`, not assumed).

## 8. Performance and security risks

| Risk | Mitigation |
|---|---|
| Tenant-wide analytics (all components of a large tenant) are expensive and tempting to "estimate" | Analytics run only on bounded graphs (a lineage neighbourhood, an incident's loaded graph, a simulation's record set) and say which. No tenant-wide statistic is shown unless computed exactly by a background job (not proposed now) |
| Deep or wide traversals as denial of service | Depth ≤ 12, page ≤ 500 nodes / 2,000 edges, `SET LOCAL statement_timeout` per graph query, the existing per-user rate limits; tests for each bound |
| Cross-tenant leakage through recursive SQL | Organization predicate on every join (as the lineage SQL already does); the SQL harness gets a two-tenant fixture with identical ids per graph query; HTTP tests for 404 on foreign ids |
| Schema metadata disclosure | The schema is the same for every tenant and holds no tenant data, but it is reconnaissance material (tables, triggers, constraint names). Default: platform administrators only (decision 1). Only the application schema is shown, never `pg_catalog` or other schemas. Function bodies are not shown |
| Exported SVG/PNG contains data | Export is client-side and contains only what the user can already see; no server-side rendering |
| Large simulation record sets | Per-strategy cap with exact counts and a truncation flag (§5); retention already deletes old runs |
| Moving Tarjan/Kahn out of PASCAR changes behaviour | Byte-identical Phase 5 and Milestone 8 outputs required before and after; existing 119 Java tests |
| Bundle size and mobile | Lazy-loaded routes; 390 px screenshots; table alternative |
| Neo4j or another graph database | **No measured need.** The whole incident graph at 100k orders loads in 6.0–6.9 s (Phase 7), and the UI never loads whole graphs. G8 measures each graph endpoint at 100k orders. A graph database would be documented as a decision only if a bounded query misses its target after indexing; the proposed target is a p95 under 1 s for a 6-hop lineage page |
| Still no green `mvn verify` | All Spring work so far is type-checked against stubs only. Recommend running it (G0) before G2 adds more Spring code |

## 9. Recommended implementation order

Each milestone ends with real results: tests run, screenshots, and measured
numbers where relevant.

| # | Milestone | Main work | Size |
|---|---|---|---|
| G0 | Green baseline (recommended, on your laptop) | `mvn verify`; fix what fails | S–M |
| G1 | Graph core | `com.atlas.graph` + fixture tests for every algorithm; move Tarjan and Kahn out of PASCAR with identical experiment output | M |
| G2 | Lineage graph API | Nodes/ports/edges, gaps on nodes, cycles, paths, keyset paging, expansion, as-of comparison; SQL harness with two tenants and cycles | M |
| G3 | GraphCanvas + Lineage Explorer | React Flow canvas, legend, minimap, search, filters, details, export, states; migrate the History page; CSP, axe, screenshots | M |
| G4 | Impact Analysis Explorer | Impact graph and node details from stored impact, plan, conflicts, gaps | M |
| G5 | Recovery Graph Comparison | V14, simulator record sets and new metrics, compare API, UI; experiment re-run to confirm unchanged metrics | M |
| G6 | ER Schema Explorer | Introspector with relationship rules, V15 snapshots, diff, explorer, SVG/PNG export | M |
| G7 | Integrity Dependency Explorer | Declared dependencies with an `EXPLAIN` drift test; graph API and UI | S–M |
| G8 | Scale, security and docs | `EXPLAIN ANALYZE` at 100k orders, large-graph tests, the Module 8 test list completed, the Neo4j decision recorded with numbers, STATUS | M |

## 10. Decisions needed before G1

1. **Who may see the ER Schema Explorer?**
   - Recommended: platform administrators only.
   - Alternative: a new `schema.read` permission granted to organization
     admins.
2. **Store per-strategy record sets?**
   - Recommended: yes, capped at 5,000 records per strategy, with exact
     counts and a truncation flag.
   - Without them, Module 5's graph comparison cannot be done: the snapshot
     is not kept.
3. **Replace the hand-written SVG lineage graph with React Flow?**
   - Recommended: yes, keeping the tested layout function, the table view
     and the filters, so all five explorers share one canvas (minimap,
     pan, export).
4. **Libraries:** pin `@xyflow/react` 12.12.0, `@dagrejs/dagre` 3.x and
   `html-to-image`, all lazy-loaded. Recommended.
5. **Wording of "unaffected":**
   - Recommended: replace "verified unaffected" with "not reached,
     provenance complete" and "not reached, provenance incomplete";
   - UNKNOWN appears only as a gap marker on a node, never as an edge.
