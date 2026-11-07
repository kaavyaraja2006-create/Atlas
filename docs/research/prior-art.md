# Prior art and what ATLAS does (and does not) add

Status: **first review, 2026-11-05.** Based on each work's abstract, the
author pages and, for Akkuş & Goel, the full paper. Warp, Retro and Ammann et
al. still need a full read before any paper is written. Citations were
checked against the publisher or the author's institution.

## Bottom line

Selective recovery from bad writes is an established research area. These
ideas are **not new** in ATLAS:

- tracking dependencies between writes;
- repairing only damaged data instead of restoring everything;
- keeping legitimate later changes;
- compensating where undo is impossible.

ATLAS must not be called "novel" or "first". What can be claimed honestly is
an **engineering combination, built and evaluated**, for business
applications that are willing to record provenance themselves (see "What
ATLAS adds" below).

## Works reviewed

### Ammann, Jajodia, Liu — *Recovery from Malicious Transactions*, IEEE TKDE 14(5):1167–1185, 2002. [doi:10.1109/TKDE.2002.1033782](https://pure.psu.edu/en/publications/recovery-from-malicious-transactions/)

Databases under attack. The paper argues that full rollback undoes
legitimate work, and that compensating transactions depend on application
semantics. It presents algorithms that repair **only the damaged part** of
the database, including damage spread through benign transactions that read
malicious writes, and specifies what information must be kept to do this.
Later algorithms allow new transactions to run during repair.

**Overlap with ATLAS:** the core problem statement and the idea of tracing
damage through dependent transactions. **Difference:** dependencies there are
transaction read/write dependencies. ATLAS records field-level derivations
between specific record versions, written by the application.

### Akkuş, Goel — *Data Recovery for Web Applications*, IEEE/IFIP DSN 2010. [paper](https://www.eecg.utoronto.ca/~ashvin/publications/data-recovery-webapps.pdf)

Recovers web-application data corrupted by bugs or misconfiguration,
**without changing the application**:

- dependencies come from database undo logs, conservative query parsing, and
  dynamic taint tracking in a modified PHP interpreter;
- recovery applies compensating transactions that revert the writes of
  tainted requests;
- administrators steer the process with whitelists and five dependency
  policies.

The program-field policy gave the fewest false positives in their evaluation
(five real bugs in WordPress, Drupal and Gallery2).

**Overlap:** field-level taint, selective compensating repair, and the
precision/recall framing (false positives and false negatives). **Difference:**

- They *infer* dependencies transparently. ATLAS *declares* them in
  application code: more precise (it is told that `unit_price` was copied,
  not that a table was read), but every write path must be instrumented.
- Their repair reverts writes. ATLAS recomputes derived values with domain
  rules and turns legally final records into business compensations.

### Kim, Wang, Zeldovich, Kaashoek — *Intrusion Recovery Using Selective Re-execution* (Retro), OSDI 2010. [MIT DSpace](https://dspace.mit.edu/handle/1721.1/61699)

OS-level recovery. Retro records an action history graph, rolls back the
objects an attack affected, and **selectively re-executes** only the actions
that depended on them.

**Overlap:** the dependency graph plus selective repair, and preserving
unaffected work. **Difference:** OS objects and re-execution versus business
records and rule-based recomputation.

### Chandra, Kim, Shah, Narula, Zeldovich — *Intrusion Recovery for Database-backed Web Applications* (Warp), SOSP 2011, pp. 101–114. [MIT DSpace](https://dspace.mit.edu/handle/1721.1/73667)

The closest system to ATLAS:

- rolls back affected database rows using a time-travel database;
- replays later legitimate actions to keep users' changes;
- supports *retroactive patching* and browser-level replay;
- needs no changes to MediaWiki's source (24–27 % throughput overhead,
  2–3.2 GB/day of storage).

**Overlap:** preserving legitimate later changes, row-level selective
rollback, and concurrent repair. **Difference:** Warp is transparent and
replay-based, and built to undo attacks. ATLAS targets *business data
errors*, such as a mistyped price, where re-executing requests is not the
right fix. The right fix is recomputing derived amounts and compensating
issued documents, with a human approving the plan.

### Background

- Buneman, Khanna, Tan — *Why and Where: A Characterization of Data
  Provenance*, ICDT 2001 ([UPenn repository](https://repository.upenn.edu/cis_papers/210)).
  Vocabulary for "where" provenance (which source location a value was copied
  from), which ATLAS's `COPIED_INTO` and `DERIVED_VALUE` edges record
  explicitly.
- Garcia-Molina, Salem — *Sagas*, SIGMOD 1987. Compensating transactions are
  the basis for credit-note compensation. (Not re-checked in this review.)

### ATLAS X additions (reviewed November 2026)

These cover the new ATLAS X pieces: lineage with confidence, field history,
counterfactual simulation and integrity monitoring. Each entry says what was
checked; none of them was read in full for this review.

- **W3C PROV-DM** (W3C Recommendation, 30 April 2013,
  [w3.org/TR/prov-dm](https://www.w3.org/TR/prov-dm/)). A standard model of
  entities, activities and agents, with relations such as `wasDerivedFrom`
  and `wasGeneratedBy`. ATLAS's edges map onto it closely enough to export:
  a record version is an entity, a write is an activity, the actor is an
  agent, and `DERIVED_VALUE`, `COPIED_INTO` and `AGGREGATED_INTO` are kinds
  of `wasDerivedFrom`. ATLAS does **not** export PROV today. Its confidence
  levels (`VERIFIED`, `DECLARED`, `INFERRED`, `UNKNOWN`) are ATLAS's own and
  are not PROV terms.
- **Cheney, Chiticariu, Tan — *Provenance in Databases: Why, How, and
  Where***, Foundations and Trends in Databases 1(4), 2009
  ([now publishers](https://nowpublishers.com/article/Details/DBS-006)). The
  standard survey. ATLAS records *where*-style provenance (which field of
  which version a value came from) eagerly, at write time. It does not
  compute *why*- or *how*-provenance for queries.
- **Kulkarni, Michels — *Temporal features in SQL:2011***, SIGMOD Record,
  September 2012
  ([SIGMOD Record](https://sigmodrecord.org/2012/09/30/temporal-features-in-sql2011/);
  overview on [Wikipedia](https://en.wikipedia.org/wiki/SQL:2011)). SQL:2011
  standardises system-versioned tables, which keep every row version with
  its system time, and application-time periods. The ATLAS field history (a
  view over the hash-chained `change_events`) gives similar "as it was"
  answers per field. It does not use SQL:2011 syntax, because PostgreSQL 16
  does not implement system-versioned tables natively. A system-versioned
  table also records *that* a row changed, not *where its values came from*;
  that second part is what the provenance edges add.
- **Campbell, Arab, Glavic — *Efficient Answering of Historical What-if
  Queries*** (arXiv:2203.12860, 2022, [arXiv](https://arxiv.org/abs/2203.12860);
  the Mahif system). Answers "what would the current state be if this past
  update had been different?" by *reenacting* the transaction history, using
  program and data slicing to skip irrelevant work. This is the closest
  prior art to ATLAS X's counterfactual simulator, and it is more general:
  arbitrary SQL updates, with no instrumentation needed.
  - What ATLAS does differently: it does not reenact SQL. It replays a
    *recovery plan* (field restores, recomputation by declared rules,
    compensation of final documents) over an in-memory copy of one
    consistent snapshot.
  - It compares six repair *strategies* against hard constraints: no
    collateral changes, later updates preserved, no deletions, conflicts
    surfaced, business invariants hold.
  - It is narrower than Mahif and relies on recorded provenance. Whether the
    six-strategy constraint comparison is new is **not established**: the
    data-repair literature below has not been searched for it.
- **Data repair and cleaning** (for example Bohannon et al., *A Cost-Based
  Model and Effective Heuristic for Repairing Constraint Violations*,
  SIGMOD 2005). Not re-checked in this review. That line of work repairs
  data to satisfy integrity constraints with minimum cost and no knowledge
  of the faulty change. ATLAS starts from a *known* faulty change. Its
  integrity monitor only *detects* violations (orders and invoices whose
  totals disagree with their lines, derived values that disagree with their
  sources, required provenance missing, a broken audit chain). It never
  repairs them automatically; repairs go through a recovery incident.

**Position after this review.** ATLAS X does not claim any new
algorithm. It defends an engineering combination, evaluated honestly:
- field-level write-time provenance with confidence;
- conflict-aware selective repair with compensation;
- governed execution;
- a constraint-based comparison of repair strategies on a consistent
  snapshot.

Before any novelty claim, these need a proper search: Mahif and the
follow-up reenactment work (Arab, Glavic et al.), Warp, and repair work in
accounting and ERP systems.

## What ATLAS adds (the claim it will defend)

1. **Explicit, version-to-version, field-level provenance recorded in the
   same transaction as the write**, with the database rejecting edges whose
   endpoints were never recorded (ADR-0003, deferred composite foreign keys).
   Trade-off: precise, but completeness depends on instrumentation.
   Phase 5 measures what missing edges cost.
2. **Business-aware repair:**
   - derived fields are *recomputed* by declared rules (copy, sum, line
     formula), not reverted;
   - legally final records (issued invoices) become *compensation* steps
     (credit note or supplementary charge);
   - nothing is ever rewritten in the history.
3. **Field-level conflict detection:** a later legitimate edit to the same
   *field* blocks automatic repair of that record. An edit to a different
   field (renaming the product) does not.
4. **Governed execution (Phase 6):** an approval is tied to a plan
   fingerprint over every record version read, so any concurrent change
   invalidates it. After execution, invariants are checked, and a failed
   check is reported as a failure.
5. **An honest evaluation:** precision and recall against an oracle that
   never reads the provenance edges, compared with baselines, plus an
   edge-loss experiment.

## Open questions to settle before writing a paper

- Read Warp and Retro in full: how do they define precision, and what do they
  do with irreversible external effects (Warp's emails, for example)?
- Is there work on provenance-driven repair in ERP or accounting systems
  specifically? Search: "provenance-based repair accounting", "data lineage
  error correction ERP".
- Compare with event sourcing / CQRS projection rebuilds, which fix
  downstream read models by replaying events.
