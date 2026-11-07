# Phase 7 benchmark — PASCAR against baselines on PostgreSQL

**Data: synthetic** (the Phase 5 generator, fixed seeds). Raw results:
`raw/phase7-benchmark.jsonl` (21 runs). Reproduce:
`scripts/bench/run.sh` (needs JDK 21, Python 3 + psycopg 3, PostgreSQL 16;
no Maven). Tables below are generated from the raw file by
`scripts/bench/summarize.py`.

## What was measured, and how

Each run generates one history, loads it into a freshly migrated PostgreSQL
16 database, and then:

1. **Baselines, in the database**
   - **NAIVE_FK:** every line of the faulty product, plus their orders,
     invoices and invoice lines. This is what following foreign keys gives.
   - **RECORD_LEVEL:** dependency closure over the recorded provenance from
     the faulty version, at record granularity (fields ignored). This is the
     granularity at which transaction- or record-level undo systems track
     dependencies (an approximation of them, not an implementation).
   - **FULL_RESTORE:** records whose latest write is at or after the fault,
     which a point-in-time restore loses. Also the wall time of `pg_dump -Fc`
     and `pg_restore` of the whole database.
2. **PASCAR:** the application's loader SQL (`LOAD_INCIDENT_EDGES`,
   `LOAD_INCIDENT_VERSIONS`, extracted from the Java source) runs in one
   REPEATABLE READ transaction. Its output is fed to the application's engine
   (`DbGraphRun`), and the result is scored against the oracle's ground
   truth, which is built from domain facts and **never reads provenance**.
3. **Execution:** the plan is stored, approved by a second user, and executed
   one transaction per step with the application's SQL (`ExecutionRepository`,
   `ChangeRecorder`, `ProvenanceRecorder`).
   **The orchestration is a Python replay** of `RecoveryWorkflow` and
   `JdbcRecoveryPorts`, because the Spring application cannot be built in
   this environment. The statements are the application's; the control flow
   around them is a re-implementation.
4. **Verification, in the database:**
   - every planned field value is checked against the stored row;
   - **every** order and draft invoice total in the database is checked
     against its lines (not only the plan's);
   - no invoice may be credited beyond its total;
   - the plan may have written only its own records;
   - the audit hash chain must verify;
   - finally, a fresh load, analysis and plan of the repaired data must have
     **no automatic step left**.
5. **Contention** (1k and 10k orders), on a `pg_restore`d copy of the
   pre-recovery database. Right after step 1 commits, a person edits 5 order
   lines the plan has not reached yet.

Scenarios: **LIVE** (price typed at a tenth, still wrong when the incident
opens), **OVERWRITTEN** (later legitimately re-priced: origin conflict),
**OVERBILLED** (typed at ten times: issued invoices over-billed, so credit
notes). Sizes 1,000 and 10,000 orders × seeds 1–3; 100,000 orders × seed 1
(without the contention copy).

Machine: the 2-vCPU, 7 GB sandbox VM this was built in; PostgreSQL 16.15 with
default settings except a local socket. Every time is a single measurement
per run. Treat times as indicative and comparable within this report only.

## Results

### 1. Selection quality (records chosen for change vs the oracle)

| Orders | Scenario | Seeds | Truth | PASCAR P / R | Record-level P / R | Naive FK P (extra) | Full restore P (rewound needlessly) |
|---|---|---|---|---|---|---|---|
| 1,000 | LIVE | 3 | 664 (651–685) | 1.000 / 1.000 | 1.000 / 1.000 | 0.686 (0.667–0.702) (304 (291–327)) | 0.191 (0.187–0.195) (2814 (2791–2828)) |
| 1,000 | OVERWRITTEN | 3 | 373 (368–378) | 1.000 / 1.000 | 0.562 (0.543–0.576) / 1.000 | 0.385 (0.381–0.390) (595 (576–605)) | 0.107 (0.106–0.110) (3105 (3069–3135)) |
| 1,000 | OVERBILLED | 3 | 664 (651–685) | 1.000 / 1.000 | 1.000 / 1.000 | 0.686 (0.667–0.702) (304 (291–327)) | 0.191 (0.187–0.195) (2814 (2791–2828)) |
| 10,000 | LIVE | 3 | 6925 (6773–7139) | 1.000 / 1.000 | 1.000 / 1.000 | 0.705 (0.697–0.712) (2903 (2864–2949)) | 0.198 (0.195–0.203) (28026 (27911–28120)) |
| 10,000 | OVERWRITTEN | 3 | 3932 (3828–4094) | 1.000 / 1.000 | 0.568 (0.564–0.574) / 1.000 | 0.400 (0.394–0.408) (5896 (5854–5940)) | 0.113 (0.110–0.116) (31019 (30856–31110)) |
| 10,000 | OVERBILLED | 3 | 6925 (6773–7139) | 1.000 / 1.000 | 1.000 / 1.000 | 0.705 (0.697–0.712) (2903 (2864–2949)) | 0.198 (0.195–0.203) (28026 (27911–28120)) |
| 100,000 | LIVE | 1 | 69662 | 1.000 / 1.000 | 1.000 / 1.000 | 0.704 (29236) | 0.199 (280697) |
| 100,000 | OVERWRITTEN | 1 | 39754 | 1.000 / 1.000 | 0.571 / 1.000 | 0.402 (59144) | 0.114 (310605) |
| 100,000 | OVERBILLED | 1 | 69662 | 1.000 / 1.000 | 1.000 / 1.000 | 0.704 (29236) | 0.199 (280697) |

### 2. Time (ms, single run each per seed; 2-vCPU sandbox VM)

| Orders | Scenario | DB size MB | PASCAR load SQL (edges + versions) | Analysis | Planning | Naive FK query | Record-level query | pg_dump | pg_restore |
|---|---|---|---|---|---|---|---|---|---|
| 1,000 | LIVE | 25 (24–25) | 50 (41–58) | 240 (217–255) | 127 (103–144) | 5 (4–6) | 7 (6–10) | 206 (202–214) | 843 (620–966) |
| 1,000 | OVERWRITTEN | 25 (24–25) | 44 (37–53) | 171 (158–179) | 85 (75–92) | 8 (6–12) | 7 (6–9) | 242 (198–276) | 874 (684–1023) |
| 1,000 | OVERBILLED | 25 (24–25) | 55 (53–58) | 181 (151–203) | 90 (69–110) | 4 (4–5) | 8 (6–12) | 215 (209–219) | 782 (592–1050) |
| 10,000 | LIVE | 155 (154–158) | 534 (468–603) | 605 (571–661) | 463 (447–483) | 43 (38–48) | 88 (71–97) | 1320 (1228–1436) | 4271 (4150–4506) |
| 10,000 | OVERWRITTEN | 156 (156–157) | 462 (437–478) | 472 (412–528) | 383 (297–432) | 38 (27–45) | 75 (64–84) | 1275 (1202–1328) | 4365 (4000–4812) |
| 10,000 | OVERBILLED | 157 (155–159) | 438 (380–482) | 590 (463–736) | 416 (387–456) | 33 (27–40) | 63 (58–69) | 1242 (1175–1276) | 3991 (3680–4268) |
| 100,000 | LIVE | 1433 | 5994 | 3505 | 2418 | 493 | 883 | 11328 | 39041 |
| 100,000 | OVERWRITTEN | 1434 | 5997 | 2809 | 1728 | 496 | 886 | 11700 | 40869 |
| 100,000 | OVERBILLED | 1433 | 6906 | 4167 | 3062 | 476 | 857 | 13296 | 42447 |

### 3. Execution and verification (Python replay of the workflow, the application's SQL)

| Orders | Scenario | Steps | Written | Credit notes | Manual | Written outside truth | Step ms p50 / p95 | Total s | Totals violated (DB-wide) | Scope ok | Chain intact | Re-plan automatic steps | Outcome |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 1,000 | LIVE | 608 (591–621) | 545 (534–551) | 0 | 46 (39–54) | 0 | 3.6 (3.4–3.9) / 6.0 (5.8–6.3) | 2.3 (2.1–2.7) | 0 | yes | yes | 0 | INCOMPLETE |
| 1,000 | OVERWRITTEN | 342 (334–352) | 304 (299–312) | 0 | 27 (23–30) | 0 | 3.7 (3.3–4.1) / 6.4 (5.7–7.0) | 1.3 (1.2–1.5) | 0 | yes | yes | 0 | INCOMPLETE |
| 1,000 | OVERBILLED | 608 (591–621) | 591 (580–605) | 46 (39–54) | 0 | 0 | 3.2 (3.0–3.7) / 5.3 (4.6–5.7) | 2.1 (1.9–2.3) | 0 | yes | yes | 0 | PASSED |
| 10,000 | LIVE | 6399 (6273–6602) | 5730 (5612–5922) | 0 | 461 (442–479) | 0 | 3.4 (3.3–3.6) / 5.7 (5.3–6.3) | 22.7 (21.3–24.0) | 0 | yes | yes | 0 | INCOMPLETE |
| 10,000 | OVERWRITTEN | 3627 (3533–3790) | 3240 (3146–3401) | 0 | 268 (262–277) | 0 | 3.4 (3.3–3.5) / 5.6 (5.5–5.6) | 12.7 (12.3–13.1) | 0 | yes | yes | 0 | INCOMPLETE |
| 10,000 | OVERBILLED | 6399 (6273–6602) | 6191 (6054–6385) | 461 (442–479) | 0 | 0 | 3.4 (3.0–3.7) / 5.7 (5.0–6.3) | 23.5 (20.6–25.4) | 0 | yes | yes | 0 | PASSED |
| 100,000 | LIVE | 64064 | 57227 | 0 | 4789 | 0 | 3.4 / 5.6 | 225.2 | 0 | yes | yes | 0 | INCOMPLETE |
| 100,000 | OVERWRITTEN | 36593 | 32762 | 0 | 2709 | 0 | 3.6 / 5.8 | 136.3 | 0 | yes | yes | 0 | INCOMPLETE |
| 100,000 | OVERBILLED | 64064 | 62016 | 4789 | 0 | 0 | 4.2 / 6.9 | 286.8 | 0 | yes | yes | 0 | PASSED |

### 4. Contention: a person edits 5 unreached lines after step 1 commits

| Orders | Scenario | Seeds | Conflicts detected | Updates lost | Steps blocked | Fingerprint changed | Re-plan: conflicted / automatic left |
|---|---|---|---|---|---|---|---|
| 1,000 | LIVE | 3 | 5 / 5 | 0 | 6 (5–9) | all | 5 / 6 (5–9) |
| 1,000 | OVERWRITTEN | 3 | 5 / 5 | 0 | 7 (5–9) | all | 6 / 6 (5–9) |
| 1,000 | OVERBILLED | 3 | 5 / 5 | 0 | 6 (5–9) | all | 5 / 7 (5–9) |
| 10,000 | LIVE | 3 | 5 / 5 | 0 | 7 (5–9) | all | 5 / 6 (5–7) |
| 10,000 | OVERWRITTEN | 3 | 5 / 5 | 0 | 7 (5–9) | all | 6 / 6 (5–7) |
| 10,000 | OVERBILLED | 3 | 5 / 5 | 0 | 7 (5–9) | all | 5 / 7 (7–8) |

### 5. Value accuracy

Planned totals and compensation amounts checked against the oracle's arithmetic: 106,638 checked, 0 mismatched.

P = precision, R = recall against the oracle. "Extra" / "rewound needlessly" =
records the method would change that never consumed the faulty value. Every
method reached recall 1.0. "Written outside truth" counts records the
execution wrote that the oracle says were not affected (credit notes
excluded, since they are new records).

## Findings

1. **The real loader SQL reproduces the in-memory results exactly.** On
   graphs loaded from PostgreSQL, PASCAR selected exactly the oracle's set
   in all 21 runs. The truth sizes (mean 664 / 373 / 6,925 / 3,932 at 1k and
   10k) and every selection are identical to the Phase 5 in-memory
   experiment, which checks `LOAD_INCIDENT_EDGES` / `LOAD_INCIDENT_VERSIONS`
   end to end.
2. **Record-level dependency tracking is as good as PASCAR while the fault is
   live, and much worse once someone fixes the source.** In LIVE and
   OVERBILLED its selection equals PASCAR's: in this workload only
   `unit_price` carries edges, so field granularity adds nothing. In
   OVERWRITTEN its precision is about 0.57: it cannot see that a later
   version replaced the faulty field, so it also selects everything priced
   from the corrected version. That is the case PASCAR's carry-forward rule
   exists for. This workload has no test where field granularity matters on
   its own; see limits.
3. **Naive FK traversal and point-in-time restore over-select heavily.**
   - Naive FK: 30 % of what it selects is unaffected when the fault is live,
     60 % in OVERWRITTEN.
   - A restore to the fault point loses up to 310,605 records of legitimate
     work at 100k orders.
   - At 100k orders, `pg_dump` + `pg_restore` of the 1.4 GB database took
     about 50–56 s, before any lost work is redone.
   - PASCAR's whole read path (loader SQL 6.0–6.9 s, analysis 2.8–4.2 s,
     planning 1.7–3.1 s) took 10.5–14.1 s.
4. **Execution wrote only affected records and left the database
   consistent.**
   - Across 21 runs: 0 records written outside the truth set and 0 total
     violations anywhere in the database.
   - Scope matched exactly, the audit chain verified, and a fresh re-plan
     found 0 automatic steps.
   - OVERBILLED runs verify **PASSED**, with one credit note per over-billed
     issued invoice (4,789 at 100k).
   - LIVE and OVERWRITTEN runs verify **INCOMPLETE**: under-billed issued
     invoices need a supplementary charge, which recovery leaves to a person,
     and every other check passed.
5. **Concurrent edits are never overwritten.** In all 18 contention runs, the
   5 lines a person edited mid-execution were detected as conflicts (5/5).
   - 0 updates were lost, and their dependent steps (5–9) were blocked.
   - The fingerprint changed, so a stale approval would be refused.
   - A re-plan lists the edited lines as CONFLICTED (manual), plus the
     blocked remainder.
6. **Execution cost:** a median of about 3.4–4.2 ms per step (p95 under 7 ms),
   roughly linear: 286.8 s for 64,064 steps at 100k OVERBILLED. Each step is
   its own committed transaction, synchronous_commit on.

## Bugs found by this benchmark

- **Execution was quadratic in plan size (application bug, fixed).** Every
  step's lock re-read the whole plan JSON, which is 7.2 MB for a 6,273-step
  plan.
  - Measured on that plan: 97 ms per lock with the JSON, 0.5 ms without.
  - The first 10k run had spent more than 10 minutes executing when it was
    stopped.
  - Fix: `LOCK_PLAN_STATUS` locks only the status after the first full read
    (plan content is immutable by trigger), in `JdbcRecoveryPorts` and in the
    replay.
  - At 1k orders, median step time went from 10.4 ms to 3.5 ms. The pre-fix
    1k runs are kept in `raw/phase7-benchmark-before-lock-fix.jsonl`.
- **Bulk-appending change events in one transaction is quadratic (a limit,
  not on any application path).** Every append updates the tenant's single
  chain-head row, and inside one transaction each update leaves a dead
  version that later lookups walk.
  - Appending 86,000 events in one transaction took 275 s; in committed
    batches of 2,000 the whole 10k load took 27 s.
  - The application writes a few events per transaction, so it is not
    affected, but **a bulk import must batch its commits**.

## What this does and does not show

- **Shows:**
  - on PostgreSQL, the application's loader SQL, engine and execution SQL
    select, correct and compensate exactly the affected records at up to
    100,000 orders (about 850,000 versions);
  - the database ends consistent and verifiable;
  - concurrent edits survive;
  - by how much simpler approaches over-select or rewind.
- **Does not show:**
  - the Java orchestration (`RecoveryWorkflow` + Spring adapters) running
    against the database: here it is a Python replay. The workflow's rules
    are unit-tested separately (Phase 6), and `RecoveryIT` will exercise the
    real path once the backend builds;
  - behaviour on real data or other fault types;
  - multi-field faults where field granularity alone decides (this workload
    records edges only for `unit_price`);
  - performance on production hardware, with tuning, or under concurrent
    load;
  - an implementation of published transaction-level undo systems: the
    RECORD_LEVEL baseline approximates their granularity only.
- **Precision of 1.0 is partly by construction:** the generator records
  edges perfectly (Phase 5 measured recall under edge loss). The informative
  results are the baselines' collateral, the database-wide consistency
  checks, the contention outcomes, and the two scale bugs.

