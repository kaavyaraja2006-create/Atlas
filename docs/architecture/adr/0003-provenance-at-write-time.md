# ADR-0003: Record provenance explicitly at write time; FK ≠ derivation

**Status:** Accepted (2026-11-05)

**Context.** The recovery engine needs to know which records consumed a
specific faulty value. Options: (a) infer from foreign keys, (b) infer from
timestamps/co-occurrence, (c) have the code that computes a derived value
record the derivation.

**Decision.** (c). Services call `ProvenanceRecorder` in the same
transaction as the derived write, producing version-to-version edges with a
relation (`DERIVED_VALUE`, `AGGREGATED_INTO`, `COPIED_INTO`) and evidence
`RECORDED_AT_WRITE`. Foreign keys are never treated as derivation. Edges
inferred after the fact are tagged `BACKFILLED` and are never used for
automatic repair — only shown for manual review. Deferred composite FKs make
the database reject edges whose endpoints were never recorded.

**Consequences.** Precision: only records that consumed the faulty *version*
are selected. Cost: every derivation path in the code must call the
recorder — a missed call is a silent gap. Mitigation: integration tests
assert, per write path, that the expected edges exist; Phase 5 benchmarks
report recall against a ground truth that is computed independently.

## Amendment (2026-11-05, Phase 2b): which edges are written

| Write | Edges recorded in the same transaction |
|---|---|
| Add order line | `product@vN.unit_price –DERIVED_VALUE→ order_line@v0.unit_price` |
| Recompute order total (new order version vK) | `order_line@v.line_total –AGGREGATED_INTO→ order@vK.total_amount` for **every** line, because each order version's total depends on all its lines |
| Create invoice | `order_line@v.unit_price –COPIED_INTO→ invoice_line@v0.unit_price`, then `invoice_line@v0.line_total –AGGREGATED_INTO→ invoice@v0.total_amount` |

Versions that change only non-derived fields (confirming an order, issuing
an invoice) get **no** edges. Whether a later version still carries a
value forward is read from `change_events.changed_fields`: if
`total_amount` is not in the changed fields of order v3, v3 holds the same
total as v2. Recording carry-forward edges for every status change would
duplicate information the change log already holds exactly.

Verified (Phase 2b, `scripts/verify-repository-sql.py`): walking the edges
from a faulty product version reaches exactly its order line, the order
versions whose totals included it, the invoice line and the invoice — and
not a correctly priced line on the same order.
