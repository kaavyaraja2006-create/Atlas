# Phase 5 experiment — selecting what a faulty price change affected

**Data: synthetic** (seeded simulator, `ImpactExperiment` /
`SimulatedCommerce`). Raw results: `raw/phase5-impact-analysis.jsonl`
(18 runs). Reproduce: `scripts/run-impact-experiment.sh`.

## Question

Given one faulty change, here a popular product's price mistyped at a tenth
of its value part-way through the history, which records must be corrected?
Does PASCAR select exactly those, and how does it compare with simpler
approaches?

## Setup

- **Generator.** N orders over N/50 products; 1–4 lines per order; the
  faulty product is chosen for 15 % of lines.
  - 70 % of orders are confirmed; 60 % of confirmed orders are invoiced. Of
    the invoices, 50 % are issued, 10 % voided and 40 % left as drafts.
  - 5 % of steps also make an unrelated, legitimate price change on another
    product.
  - The fault happens at 30 % of the timeline. At 50 % the faulty product is
    renamed: its price stays wrong, so the fault must be carried forward.
  - **OVERWRITTEN** scenario: at 70 % someone sets a new legitimate price on
    the faulty product, so later orders are fine and the origin is in
    conflict.
- **Oracle.** Ground truth built only from domain facts the simulator keeps:
  which product version priced each line, and which order line each invoice
  line came from. **It never reads provenance edges.** It also computes each
  affected order's and invoice's correct total by arithmetic.
- **Methods.**
  - `PASCAR`: impact analysis on the recorded edges.
  - `NAIVE_FK`: every line with the product's id, plus their orders and
    invoices (what following foreign keys gives).
  - `FULL_RESTORE`: every record written after the fault (point-in-time
    restore).
  - `PASCAR_EDGE_LOSS_x%`: PASCAR with x % of edges randomly removed,
    simulating instrumentation gaps.
- Sizes 1,000 / 10,000 / 100,000 orders; seeds 1, 2, 3; both scenarios.
  The largest histories have about 849,000 record versions and 960,000
  edges.

## Results (mean over seeds; ranges in raw data)

| Orders | Scenario | Truth (records) | PASCAR precision / recall | Naive-FK precision (extra records) | Full-restore precision (records rewound needlessly) |
|---|---|---|---|---|---|
| 1,000 | LIVE | 664 | **1.000 / 1.000** | 0.686 (304) | 0.191 (2,814) |
| 1,000 | OVERWRITTEN | 373 | **1.000 / 1.000** | 0.385 (595) | 0.107 (3,105) |
| 10,000 | LIVE | 6,925 | **1.000 / 1.000** | 0.705 (2,903) | 0.198 (28,026) |
| 10,000 | OVERWRITTEN | 3,932 | **1.000 / 1.000** | 0.400 (5,896) | 0.113 (31,019) |
| 100,000 | LIVE | 69,177 | **1.000 / 1.000** | 0.700 (29,620) | 0.198 (280,601) |
| 100,000 | OVERWRITTEN | 39,495 | **1.000 / 1.000** | 0.400 (59,302) | 0.113 (310,284) |

All baselines reach recall 1.0. Their problem is collateral: records they
would change that never consumed the faulty value. When the price was later
fixed legitimately (OVERWRITTEN), naive FK traversal over-selects 2.5×,
because it cannot tell which version priced a line.

**Value accuracy.** Over all 18 runs the plans' proposed order and invoice
totals were checked against the oracle's arithmetic for **137,509 orders**,
and the compensation amounts for **25,030 issued invoices**: **0
mismatches**.

**Edge loss** (PASCAR recall; precision stayed 1.000 in every run):

| Edges missing | 1 % | 5 % | 10 % |
|---|---|---|---|
| Recall (all sizes) | 0.982–0.987 | 0.928–0.933 | 0.840–0.863 |

Recall falls faster than edges are lost (10 % missing → about 14 % of
affected records missed), because one missing edge hides everything
downstream of it. **PASCAR is only as complete as its instrumentation**,
which is why every write path needs a test asserting its edges
(`OrdersAndInvoicesIT`).

## Time (in memory, 2-vCPU sandbox VM, single run each — indicative only)

| Orders | Analysis | Planning |
|---|---|---|
| 1,000 | 0.2–0.25 s | 0.1–0.16 s |
| 10,000 | 0.6–0.8 s | 0.4–0.6 s |
| 100,000 | 3.7–6.2 s | 1.8–3.9 s |

Both grow about linearly. This is not a database benchmark: loading the
graph from PostgreSQL (Phase 7) is not included.

**Bug found by this experiment:** the first full run took **81–129 s** to
plan at 100,000 orders. Planning was quadratic: the topological sort removed
leftovers with `ArrayDeque.removeAll(list)`, an O(n²) membership test. It is
fixed with a hash set, giving 1.8–3.9 s. The pre-fix results are kept in
`raw/phase5-impact-analysis-before-plan-fix.jsonl` (same selections and
values, only planning times differ).

## What this does and does not show

- **Shows:** given complete, correctly recorded provenance, PASCAR's
  field- and version-level traversal selects exactly the records an
  independent oracle says consumed the faulty value. Its plans compute the
  correct totals and compensation amounts. Both cheaper baselines change many
  records that were never affected.
- **Does not show:**
  - that the real services record exactly these edges (the simulator mirrors
    them by construction; the integration tests check the real code and have
    not run yet);
  - behaviour on real customer data or other kinds of faults;
  - database-backed performance;
  - anything about execution, which is Phase 6.
- **Precision of 1.0 is partly by construction:** the simulator records
  edges perfectly, and the oracle and PASCAR agree on the domain's semantics.
  The informative results are the baselines' collateral, the value checks,
  and the edge-loss curve.
