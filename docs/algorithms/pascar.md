# PASCAR — Provenance-Aware Selective Change And Recovery

Specification of what is implemented in `com.atlas.recovery.engine`
(analysis and planning, Phase 5) and `com.atlas.recovery.execution`
(approval, execution and verification, Phase 6). Related work and what is
*not* new: `docs/research/prior-art.md`.

## Inputs

- **Faulty change:** one `UPDATE` change event, i.e. a record version `R@v`
  with the fields it changed and their before/after values. The before-values
  are taken as the *intended* values.
- **Incident graph:** loaded by `RecoveryRepository.loadGraph` in one
  REPEATABLE READ snapshot:
  - every provenance edge touching a record reachable from `R@v`
    (recursive CTE at record level; `UNION` makes it terminate on cycles);
  - the full version history (changed fields and after-state) of every
    reachable record, and of every record summed into one ("siblings").

## 1. Impact analysis (`ImpactAnalyzer`)

A **taint** is `(record version, field)`. A breadth-first search starts from
`(R@v, f)` for every faulty field `f` and spreads taint three ways:

| Rule | From | To | Condition |
|---|---|---|---|
| Edge | `S@i.f` | `T@j.g` | a recorded edge `S@i.f → T@j.g` exists (field-level: a `unit_cost` edge does not carry a `unit_price` fault) |
| Formula | `L@i.unit_price` | `L@i.line_total` | intra-record rule (`RepairRules.derivedFields`) |
| Carry-forward | `X@i.f` | `X@(i+1).f` | version `i+1` did **not** change `f` (a rename carries a price fault forward; a new price stops it) |

Each taint is visited at most once. A taint first reached through a
backfilled edge is upgraded once if a recorded path is found later. So the
search terminates on any graph, cyclic ones included, in
O(taints + edges examined).

### Classification (per record, first matching rule wins)

1. **ORIGIN**, the faulty record, unless a person changed the faulty field
   again afterwards: then **CONFLICTED** (restoring would overwrite their edit).
2. **REQUIRES_MANUAL_REVIEW** if the record sits on a dependency cycle
   (Tarjan, iterative).
3. **CONFLICTED** if a tainted field was later changed by a person.
4. **UNAFFECTED** if no tainted field reaches the current version (for
   example, already repaired by an earlier recovery).
5. **REQUIRES_MANUAL_REVIEW** if reached only through `BACKFILLED` edges.
6. **NON_REVERSIBLE** if legally final: an issued or void invoice and its
   lines, or a credit note.
7. **REQUIRES_MANUAL_REVIEW** if no repair rule exists for the type.
8. **DIRECTLY_AFFECTED** at depth 1, otherwise **INDIRECTLY_AFFECTED**.

Records summed into an affected record but not themselves tainted (a
correctly priced sibling line) are listed as **UNAFFECTED** context. They
are needed to recompute totals.

Each record keeps the last 8 hops of its shortest path from the fault. Its
depth gives the full length.

## 2. Plan generation (`PlanGenerator`)

Records are processed in topological order of the record-level edge graph
(Kahn's algorithm, deterministic tie-break, cycle members last). Corrected
values:

- **Origin:** faulty fields go back to their before-values.
- **DERIVED_VALUE / COPIED_INTO:** the target field takes the source's
  *corrected* value.
- **AGGREGATED_INTO:** the target field is the sum over **all** inputs of the
  version that last defined it. Corrected values are used where a source was
  affected, current values otherwise.
- **Formulas** are then re-applied (`line_total = quantity × unit_price`).

Consumers of the faulty version are corrected to the value intended *at the
time* (the before-value), even if the origin was legitimately changed later.

| Classification | Step | Risk |
|---|---|---|
| ORIGIN | `RESTORE_FIELDS` | LOW |
| DIRECTLY / INDIRECTLY_AFFECTED | `UPDATE_FIELDS` (omitted if nothing changes) | LOW; MEDIUM for confirmed orders and invoices |
| NON_REVERSIBLE, issued invoice | `ISSUE_CREDIT_NOTE` (over-billed) or `SUPPLEMENTARY_CHARGE` (under-billed), amount = \|billed − correct\| | HIGH |
| NON_REVERSIBLE, void invoice | `NO_ACTION` | LOW |
| NON_REVERSIBLE, line of issued invoice | none (compensated through the invoice) | — |
| CONFLICTED / REQUIRES_MANUAL_REVIEW | `MANUAL_REVIEW` with the computed proposal for information | HIGH |

Every step names the record version it expects (`expectedVersion`) and the
steps it depends on. Plan risk is the maximum step risk. Approval is
required unless the risk is LOW and there are at most 25 steps.

Every step also lists its **inputs** (source record, source field, target
field, relation), so the executor can record fresh provenance for what it
writes.

A plan for an incident that was already partly executed deducts credits
earlier executions of the same incident issued, so an invoice is never
credited twice; an origin already restored produces no step.

**Consistency groups (ATLAS X).** A `MANUAL_REVIEW` step holds back the steps
that depend on it. When the held step is an order or invoice total, the
automatic corrections of that total's *other* lines also gain a dependency on
the review. Without this, execution would correct those lines but not the
total, and the stored total would disagree with its lines until a person
acted. `PlanGenerator.waitingSteps` defines which steps wait, and the
counterfactual simulator uses the same definition as the executor:
- a review or a supplementary charge awaits acknowledgement;
- a review of the origin itself does not block.

The Milestone 8 experiment found this case; see
`docs/experiments/atlasx-milestone8.md`.

**Invariants** for the verifier:
- each changed field equals its proposal;
- order and invoice totals equal the sum of their lines;
- credit notes cover each credited amount;
- only the plan's records change.

**Fingerprint:** SHA-256 over `type:id@latestVersion` of every record read.
Any new version of any of them makes the plan stale.

## 3. Persistence (V6)

- `recovery_incidents`: at most one active incident per faulty change; it
  must reference a change event in the same tenant.
- `recovery_plans`: content is immutable (trigger). Status moves only
  PROPOSED → APPROVED/REJECTED/SUPERSEDED, then APPROVED →
  EXECUTED/SUPERSEDED. At most one PROPOSED plan per incident; rows are
  never deleted.

## 4. Approval, execution and verification (`RecoveryWorkflow`, V7)

The rules live in one framework-free class behind five small ports (ledger,
record store, transactions, fingerprints, re-planner), so they are
unit-tested against an in-memory stand-in with fault injection. The
PostgreSQL adapters (`JdbcRecoveryPorts`, `ExecutionRepository`) only
translate.

**Approval.** A person with `recovery.approve` who is **not the plan's
author** approves a specific plan *and the fingerprint they were shown*. The
fingerprint is recomputed from current data on approval and again when an
execution starts; any difference is a stale plan (409). Plans with risk LOW
and at most 25 steps need no approval. Rejection needs a reason. The
database enforces the same rules: a trigger refuses an approval by the author
or for another fingerprint, and a plan cannot become APPROVED without one.

**Plan states:** PROPOSED → APPROVED → EXECUTING → EXECUTED | FAILED;
PROPOSED → EXECUTING only if no approval is needed; PROPOSED/APPROVED →
REJECTED | SUPERSEDED. One execution in progress per incident.

**Execution, one transaction per step.** For each step in plan order:

1. lock the plan row (serialises concurrent executors) and skip the step if
   it already has an outcome;
2. if a prerequisite step did not complete, record **BLOCKED** (a manual step
   on the origin does not block: consumers' corrected values come from the
   faulty change's before-values, not from what a person does to the origin);
3. lock the record and compare its latest recorded version with the step's
   `expectedVersion`; if it changed, record **FAILED** ("changed since
   planning … not overwritten");
4. otherwise write it, record a change event (actor `RECOVERY`, on behalf of
   the executor) and a provenance edge per input, and record **DONE**; all in
   the same transaction.

Manual steps (MANUAL_REVIEW, SUPPLEMENTARY_CHARGE) are recorded
**AWAITING_ACKNOWLEDGEMENT**; a person later records what they did
(ACKNOWLEDGED). Void invoices are **SKIPPED**.

Write-level guards in SQL: a recovery step may change only listed columns
per type; an order or invoice total is written only if it equals the sum of
its lines in the same statement; a credit note only against an ISSUED
invoice and within what is not yet credited (the V2 triggers still refuse
edits to issued invoices). A refusal is deterministic, so the step is
recorded FAILED. Any other exception (a lost connection) rolls the step back
and records nothing; running the execution again **resumes** and retries
it. Running it after it finished replays the stored result and writes
nothing.

A failed execution moves the incident back to OPEN. A new plan then covers
only what is left: repaired records are no longer affected (their new
versions changed the tainted fields, by `RECOVERY`), and credits already
issued for the incident are deducted.

**Verification** (by anyone with `audit.read`; every run is kept):

| Check | Passes when |
|---|---|
| STEP_DONE | every automatic step is DONE |
| FIELD_EQUALS | each changed field's *stored row* value equals the plan's proposal |
| TOTAL_EQUALS_SUM_OF_LINES | each corrected order/invoice total equals the sum of its current lines |
| CREDITED_AT_LEAST | the step's credit note exists with at least the planned amount |
| LARGE_COMPENSATION_REVIEWED | a large credit was approved by someone other than the author |
| SCOPE | the records written by `RECOVERY` in the plan's step transactions are exactly the DONE steps' records and credit notes |
| NOTHING_LEFT_TO_REPAIR | a fresh analysis and plan of the current data has no automatic step |

Outcome **PASSED** only if every check passes and no manual step awaits a
person; **INCOMPLETE** if all pass but manual steps remain; otherwise
**FAILED**. A database check refuses to store a PASSED result with a failed
check or a pending manual step. PASSED resolves the incident.

### How this was tested

- `RecoveryWorkflowTest` (18 tests) on the in-memory stand-in built on the
  simulator: approval rules, stale fingerprints, rejection, low-risk plans,
  version conflicts (not overwritten, dependents blocked), deterministic
  rejections, transient failures, manual steps, verifier failures on tampered
  values and stray writes, re-plan after a partial run without a second
  credit.
- **Crash injection after every commit** of a full run, and **in the middle
  of every write** (partial work, then the process dies): each resumes to
  EXECUTED, applies every step exactly once, and verifies PASSED.
- **Mutation check:** 11 hand-made mutations of the workflow (version check
  removed, author check removed, origin exemption removed, INCOMPLETE
  outcome removed, idempotency check removed, blocking removed, approval
  fingerprint check removed, approval requirement removed, scope check
  disabled, fingerprint recheck disabled, verifier ignoring checks): each is
  caught by at least one test.
- `PlanJsonRoundTripTest`: a stored plan reads back identical with Jackson,
  so the executor runs exactly what was approved.
- SQL: every V7 statement runs on PostgreSQL 16 in
  `scripts/verify-repository-sql.py`; 22 new schema assertions.
- Not yet run: the Spring adapters and `RecoveryIT` (needs Maven + Docker).
  The adapters type-check against hand-written signature stubs of the
  Spring APIs they call; that checks types, not behaviour.

## Known limits

- The faulty change must be an UPDATE of recorded fields. A faulty INSERT
  (a whole record that should not exist) is not modelled yet.
- One faulty change per incident. Several related mistakes need several
  incidents, and their plans may interact; executions are one at a time per
  incident, not across incidents.
- Execution runs in the request thread, one transaction per step. Fine for
  thousands of steps; a very large plan should become a background job.
- Each step locks the plan row by status only (`LOCK_PLAN_STATUS`); the
  plan JSON is read once per request. Re-reading it per step made execution
  quadratic (found by the Phase 7 benchmark).
- Bulk-importing history (many change events in one transaction) must commit
  in batches: each append updates one chain-head row per tenant.
- The stale-plan check reloads the incident graph with two queries in
  READ COMMITTED; a write between them can only cause a spurious "stale",
  never a missed one.
- Recall depends on complete instrumentation: every missing edge hides that
  record and everything downstream of it (measured below).
- Supplementary charges need billing support that does not exist yet, so
  they are manual steps.
- Correctness is shown on synthetic data from a simulator that mirrors the
  services. Whether the real services record exactly those edges, and the
  adapters write exactly what the stand-in writes, is checked by
  `OrdersAndInvoicesIT` and `RecoveryIT` (not yet run).
