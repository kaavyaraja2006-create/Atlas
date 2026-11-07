# ATLAS — Risks and Key Technical Decisions

## Key decisions (details in `docs/architecture/adr/`)

| # | Decision | Why |
|---|---|---|
| 0001 | Modular monolith | ACID across business rows + history + provenance; one developer |
| 0002 | Commerce domain | Real derived data, real non-reversible steps, a real FK-but-not-derived distractor |
| 0003 | Provenance recorded at write time; FK ≠ derivation | Precision; no fabricated edges |
| 0004 | App-level tenant checks + composite FKs | Defence in depth without RLS complexity |
| 0005 | change_events as version store; DB-computed hash chain | One source of truth; app cannot forge history |
| 0006 | Defer Kafka/Redis/K8s/Terraform/MFA | Every tool arrives with a measured reason |

## Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| **Scope overrun** — coursework and other projects compete for time | High | High | MUST/SHOULD/LATER tiers; never start a SHOULD while a MUST is untested |
| **Novelty is weaker than hoped** — Warp (SOSP 2011), Akkuş & Goel (DSN 2010) and work on recovery from malicious transactions cover similar ground | High | Medium | Read them in Phase 5; frame the contribution as an implementation + evaluation of business-aware selective recovery (compensation, approvals, invariants); never claim "first" |
| **Missed provenance calls** leave silent gaps in the graph | Medium | High | Per-write-path edge assertions; recall measured against an independent oracle |
| Per-tenant audit chain lock limits write throughput | Medium | Medium | Measure in Phase 8; options: batch appends, per-entity-type chains |
| Snapshot storage growth in change_events | Medium | Low | JSONB compression; monthly partitions if measured growth demands |
| Synthetic benchmark data flatters the method | Medium | High | Publish generator + seeds; include adversarial cases (conflicts, cycles, issued invoices); report failures too |
| Superuser can rewrite the full chain | Low | Medium | External anchoring of chain head (Phase 8); stated in limitations |
| Pinned dependency versions drift or have CVEs | Medium | Low | Dependabot; dependency scanning in CI |
| Local build blocked by network policy (this session) | Occurred | Low | CI on GitHub runs `mvn verify`; schema verified independently with psql |
