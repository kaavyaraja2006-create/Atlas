# ADR-0002: Commerce domain instead of project/task management

**Status:** Accepted (2026-11-05)

**Context.** Selective recovery is only interesting where values are derived
from other values. In a task tracker almost nothing is derived; the original
plan papered over this with a generic `business_records` table and an
unrelated price/invoice example.

**Decision.** The application domain is catalogue → orders → invoices →
inventory. It has real derivations (price → line price → order total →
invoice), a real non-reversible step (issued invoices), a real compensating
action (credit notes), and a realistic distractor (inventory is FK-linked to
products but valued at cost, so it is *not* affected by price errors).

**Consequences.** Project/task management and the workflow builder move to
LATER. The resume story becomes one coherent product instead of two halves.
