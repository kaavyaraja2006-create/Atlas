# ATLAS X Milestone 8: strategy comparison and ablations (synthetic data)

**Dataset: synthetic.** Every number here comes from
`AtlasXExperiment` (`backend/src/test/java/com/atlas/recovery/engine/`).
It ran on generated commerce histories, not on real business data. The raw
output is in `docs/experiments/atlasx/sweep.jsonl` (30 runs) and
`scale.jsonl` (6 runs); the tables below are produced from those files by
`scripts/bench/atlasx_report.py`. A smaller version runs in CI as
`AtlasXExperimentTest`.

## What was measured

### Generator and fault

The Phase 5 generator: products, orders of one to four lines, 70 %
confirmed, 60 % of those invoiced, half of those invoices issued. Part-way
through the history, one popular product's price is mistyped. Three
scenarios:

- `LIVE`: the price is ÷10 and still wrong;
- `OVERWRITTEN`: someone later set a new legitimate price on the same field;
- `OVERBILLED`: the price is ×10, so issued invoices need credit notes.

### Later work injected after the fault

This experiment adds two kinds of later work. Each one is known to the
generator without reading provenance.

- **Legitimate later updates.** 20 % of all orders, faulty or not, get a
  delivery note. A correct recovery keeps every one of them.
- **Same-field conflicts.** 10 % of the order lines that carry the faulty
  price are re-priced by a person. A correct recovery must not silently
  overwrite these; each one goes to a person.

### Strategies and ablations

Strategies A–F are simulated on the same snapshot. They double as the
ablations agreed in the integration plan (§3.1):

| Strategy | What it drops compared with F |
|---|---|
| A. Point-in-time restore | everything: rewinds every record written after the fault |
| B. Foreign keys, whole rows | provenance (follows foreign keys) and field granularity |
| C. Provenance, whole rows | field granularity (restores whole earlier row versions) |
| D. Foreign keys, single fields | provenance |
| E. Provenance, conflicts ignored | conflict handling |
| F. PASCAR | nothing: field-level provenance, conflict handling, compensation |

### How the measurements are checked

The simulator measures each strategy using recorded provenance. The
*oracle* counts come from the generator's own facts:
- which lines were priced from a faulty version;
- which records were edited later.

Where the two can be compared, they agree (last table).

### Grid and machine

- Orders: 500 and 2,000; seeds 1 to 5; all three scenarios (30 runs).
- Scale runs: 8,000 and 20,000 orders, seed 1.
- Machine: a 2-vCPU sandbox VM (Intel Xeon 2.10 GHz), OpenJDK 21, one JVM
  per sweep.

## Results

Runs: 30 (orders [500, 2000], seeds 1–5, scenarios LIVE, OVERBILLED, OVERWRITTEN); note rate 0.2, conflict rate 0.1. Dataset: synthetic.

| Strategy | Repaired / faulty | Collateral records | Later values lost | Deleted | Rule breaks | Conflicts surfaced / ignored | Field writes | Manual steps | Meets every constraint |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| A. Point-in-time restore | 0 / 21533 (0.0%) | 111185 | 661994 | 130623 | 23358 | 0 / 822 | 885090 | 0 | 0 of 30 runs |
| B. Foreign keys, whole rows | 0 / 21533 (0.0%) | 4410 | 133263 | 25998 | 26061 | 0 / 822 | 172384 | 0 | 0 of 30 runs |
| C. Provenance, whole rows | 20 / 21533 (0.1%) | 0 | 95484 | 15126 | 13843 | 0 / 822 | 128985 | 0 | 0 of 30 runs |
| D. Foreign keys, single fields | 16381 / 21533 (76.1%) | 3673 | 822 | 0 | 4773 | 0 / 822 | 39963 | 0 | 0 of 30 runs |
| E. Provenance, conflicts ignored | 20956 / 21533 (97.3%) | 0 | 0 | 0 | 794 | 0 / 822 | 26372 | 1051 | 0 of 30 runs |
| F. PASCAR (provenance + conflicts) | 19646 / 21533 (91.2%) | 0 | 0 | 0 | 0 | 822 / 0 | 24671 | 1873 | 30 of 30 runs |

Recommended by the optimizer: PROVENANCE_CONFLICT_AWARE: 30

| Oracle cross-check (all runs) | Value |
|---|---:|
| Later delivery notes injected (on faulty records) | 7343 (1445) |
| Same-field re-prices injected (conflicts) | 812 |
| F: conflicts surfaced (incl. origin conflicts in OVERWRITTEN) | 822 |
| F: later values lost | 0 |
| Runs where F's repair target = oracle faulty − conflicts (LIVE, OVERBILLED) | 20 of 20 |

| Orders | Records | Versions | Edges | F simulate (ms) | All six (ms) |
|---:|---:|---:|---:|---:|---:|
| 500 | 2598 | 4516 | 4994 | 33 | 249 |
| 2000 | 10125 | 17652 | 19477 | 80 | 638 |
| 8000 | 39784 | 69521 | 76379 | 453 | 3879 |
| 20000 | 99641 | 173978 | 191095 | 1216 | 8096 |

Timings are wall-clock on one machine and one JVM run each; they show the order of magnitude only.


## How to read this

- **"Repaired / faulty" in this table is not recall.**
  - "Faulty" counts the records that still carry the faulty value and have
    not since been changed by a person. Records a person re-priced count as
    conflicts instead. That is why F's target equals the oracle's faulty set
    minus the injected conflicts in every LIVE and OVERBILLED run. In
    OVERWRITTEN, the product itself is also a conflict.
  - "Repaired" counts records left correct automatically.
  - F repairs fewer records than E (91.2 % against 97.3 %) **on purpose**.
    When a person must decide on one line of an order, F holds the other
    lines of that order and the total too. E repairs more because it
    ignores 822 conflicts, and that breaks 794 order or invoice totals.
- **Whole-row strategies (A, B, C) repair almost nothing** under this
  definition. A record restored to an earlier version loses every later
  legitimate change, so it is not "correct". Most of these strategies'
  records are deleted, not repaired, because they were created after the
  fault. "Later values lost" counts every field value written after the
  fault that the strategy overwrites or deletes, not only the injected
  delivery notes. That is why A's count (661,994) is far larger than the
  7,343 notes.
- **A's footprint is a lower bound.** The simulator models only the records
  loaded for the incident. A real point-in-time restore rewinds the whole
  organization.
- **F meets every hard constraint in all 36 runs** (30 in the sweep and 6 at
  scale):
  - no collateral changes;
  - no later value lost;
  - no deletion;
  - no rule broken;
  - every conflict surfaced.

  No other strategy met them all in any run. These constraints were chosen
  by ATLAS's own design. The result shows the implementation does what that
  design requires; it is not an independent measure of quality.

## Two defects the experiment found (both fixed, regression tests added)

1. **A total could disagree with its lines while a review was pending.**
   Take an order with two faulty lines, where a person has re-priced one of
   them.
   - Before the fix, the plan corrected the other line automatically, but
     the order total waited for the review. Execution would have left the
     stored total different from the sum of its lines until someone acted.
     The simulator showed 4 broken totals for F in the first 500-order run.
   - Fix (`PlanGenerator.holdConsistencyGroups`): when a total waits for a
     person, the automatic corrections of its other lines wait too.
   - The simulator and the executor now share one definition of "waiting"
     (`PlanGenerator.waitingSteps`). This also corrected a mismatch: the
     simulator used to treat a review of the *origin* as blocking, which the
     executor does not.
   - Test: `CounterfactualSimulatorTest.aTotalWaitingForAPersonHoldsItsOtherLinesToo`.
2. **Simulation was quadratic.**
   - Before the fix, checking each order total scanned the whole state:
     about 9 s for F alone at 2,000 orders, and about 42 s for all six
     strategies.
   - After a one-pass index, the same run takes 0.12 s for F and 0.64 s for
     all six. All 30 sweep runs produced identical results before and after
     the change, apart from timing.
   - 20,000 orders (174,000 versions) now simulate in about 8 s for all six
     strategies.

## Limits

- **Synthetic data only.**
  - There is one kind of fault (a product price) and one kind of later
    legitimate update (a delivery note).
  - Conflicts are injected as direct edits of a line's unit price that do
    not recompute its line total. A real ATLAS line edit would recompute it.
  - Real data errors and real editing patterns may differ.
- **No measure of plan quality from people.** Nobody reviewed the plans.
- **Edge loss is not measured here.** Under missing edges the simulator's
  own notion of "faulty" changes too, so its repair counts would not be
  comparable. The Phase 5 experiment measures edge loss against the oracle
  (`phase5-impact-analysis.md`).
- **Timings are rough.** Each is one run on a 2-vCPU VM, so they show only
  the order of magnitude. They are not benchmarks.
- **Database round trips are not included.** The in-app simulation also
  loads the snapshot from PostgreSQL (`SimulationService`). That part was
  not timed, because the Spring application was not run here.

## Reproduce

```bash
cd backend
# compile the engine and the experiment (or run AtlasXExperimentTest through mvn test)
java ... com.atlas.recovery.engine.AtlasXExperiment sweep 500,2000 1 5 0.2 0.1 > ../docs/experiments/atlasx/sweep.jsonl
java ... com.atlas.recovery.engine.AtlasXExperiment sweep 8000,20000 1 1 0.2 0.1 > ../docs/experiments/atlasx/scale.jsonl
python3 ../scripts/bench/atlasx_report.py ../docs/experiments/atlasx/sweep.jsonl ../docs/experiments/atlasx/scale.jsonl
```
