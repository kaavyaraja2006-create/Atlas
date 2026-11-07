# ATLAS — API Contract Plan

Status: ✔ = built in Phase 1; ✎ = written in Phases 2–6, ATLAS X ("X") and Graph Intelligence ("G") (SQL verified on PostgreSQL, Spring wiring awaiting its first CI build).
Everything else below is the contract to implement, phase by phase. The
generated OpenAPI document (springdoc, Phase 2) becomes the source of truth.

## Conventions

| Concern | Rule |
|---|---|
| Base path | `/api/v1` |
| Auth | `Authorization: Bearer <JWT>` (Phase 2). Tenant from `X-Organization-Id`, checked against the caller's memberships on every request |
| Success body | The resource itself, or `{ "items": [...], "page": {"number","size","totalItems","totalPages"} }` for lists |
| Errors | RFC 9457 `application/problem+json`: `type, title, status, detail, instance, correlationId, timestamp`, plus `errors[]` for field validation |
| Status codes | 200/201/204; 400 validation; 401 unauthenticated; 403 lacks permission; 404 not found **or other tenant**; 409 version conflict / stale plan; 422 business rule; 429 rate limited (implemented Phase 8: `problem+json` with `Retry-After` seconds; limits in `docs/security/hardening.md` §5) |
| Concurrency | Mutable resources return `ETag: "<version>"`; updates require `If-Match`. Mismatch → 409 |
| Idempotency | `Idempotency-Key` header accepted on POSTs that create or execute; same key + same body replays the first response |
| Correlation | `X-Correlation-Id` accepted (if safe) and always returned |
| Pagination | `?page=0&size=20&sort=createdAt,desc`; `size` capped at 100 |

## Endpoints

| Method | Path | Permission | Phase |
|---|---|---|---|
| GET | `/actuator/health` | public | 1 ✔ |
| GET | `/api/v1/system/info` | platform admin | 1 ✔ / 2a ✎ |
| POST | `/api/v1/auth/register` | public | 2a ✎ |
| POST | `/api/v1/auth/login` | public (rate-limited) | 2a ✎ |
| POST | `/api/v1/auth/refresh` | refresh token | 2a ✎ |
| POST | `/api/v1/auth/logout` | authenticated | 2a ✎ |
| GET | `/api/v1/users/me` | authenticated | 2a ✎ |
| POST | `/api/v1/organizations` | authenticated | 2a ✎ |
| GET | `/api/v1/organizations` | authenticated (own memberships) | 2a ✎ |
| GET/POST | `/api/v1/members` | member.manage | 2a ✎ |
| PUT | `/api/v1/members/{userId}/roles` | member.manage | 2a ✎ |
| GET/POST | `/api/v1/products` | catalogue.read / .write | 2a ✎ |
| GET/PATCH | `/api/v1/products/{id}` | catalogue.read / .write | 2a ✎ |
| GET/POST | `/api/v1/customers` | customer.read / .write | 2b ✎ |
| GET/POST | `/api/v1/orders` | order.read / .write | 2b ✎ |
| GET/PATCH | `/api/v1/orders/{id}` | order.read / .write | 2b ✎ |
| POST | `/api/v1/orders/{id}/lines` | order.write | 2b ✎ |
| POST | `/api/v1/orders/{id}/confirm` | order.write | 2b ✎ |
| POST | `/api/v1/orders/{id}/invoice` | invoice.write | 2b ✎ |
| POST | `/api/v1/invoices/{id}/issue` | invoice.write | 2b ✎ |
| POST | `/api/v1/invoices/{id}/void` | invoice.write | 2b ✎ |
| GET | `/api/v1/audit/events` (newest first: `occurred_at DESC, chain_index DESC` since Phase 8) | audit.read | 2a ✎ |
| GET | `/api/v1/audit/events/{id}` | audit.read | 2a ✎ |
| POST | `/api/v1/audit/verify` | audit.read | 2a ✎ |
| GET | `/api/v1/audit/anchors/verification` (chain checked against signed external anchors: INTACT / NO_ANCHORS / REWRITTEN / MISSING_EVENTS / BAD_SIGNATURE / BAD_ANCHOR_LOG) | audit.read | 8 ✎ |
| GET | `/api/v1/provenance/{entityType}/{entityId}?version=&direction=downstream|upstream` (one hop) | audit.read | 2b ✎ |
| POST | `/api/v1/orders/{id}/cancel` (DRAFT only) | order.write | 2b ✎ |
| GET/PATCH | `/api/v1/customers/{id}` | customer.read / .write | 2b ✎ |
| GET | `/api/v1/invoices`, `/api/v1/invoices/{id}` | invoice.read | 2b ✎ |
| GET/POST | `/api/v1/invoices/{id}/credit-notes` | invoice.read / .write | 2b ✎ |
| POST | `/api/v1/recovery/incidents` | recovery.create | 5 ✎ |
| GET | `/api/v1/recovery/incidents`, `/{id}` | audit.read | 5 ✎ |
| POST | `/api/v1/recovery/incidents/{id}/impact` (read-only analysis) | audit.read | 5 ✎ |
| POST/GET | `/api/v1/recovery/incidents/{id}/plans` | recovery.create / audit.read | 5 ✎ |
| GET | `/api/v1/recovery/plans/{id}` | audit.read | 5 ✎ |
| POST | `/api/v1/recovery/plans/{id}/approval` `{fingerprint, comment?}` | recovery.approve, not the plan author (403); fingerprint must match and still be current (409) | 6 ✎ |
| POST | `/api/v1/recovery/plans/{id}/rejection` `{comment}` | recovery.approve | 6 ✎ |
| POST | `/api/v1/recovery/plans/{id}/execution` (run, resume, or replay the finished result) | recovery.execute; approved unless LOW risk and ≤ 25 steps (409); not stale (409) | 6 ✎ |
| GET | `/api/v1/recovery/plans/{id}/execution` (step outcomes + decisions) | audit.read | 6 ✎ |
| POST | `/api/v1/recovery/plans/{id}/steps/{n}/acknowledgement` `{note}` | recovery.execute; step awaiting acknowledgement | 6 ✎ |
| POST/GET | `/api/v1/recovery/plans/{id}/verifications` | audit.read | 6 ✎ |
| PATCH | `/api/v1/orders/{id}` `{deliveryNote}` with `If-Match` (not for cancelled orders) | order.write | X ✎ |
| GET | `/api/v1/provenance/{type}/{id}/lineage?version=&direction=&depth=1..12` (multi-hop, default 6, each edge with depth, operation and confidence VERIFIED / DECLARED / INFERRED; ≤ 2,000 edges, `truncated` flag) | audit.read | X ✎ |
| GET | `/api/v1/provenance/gaps?entityType=` (required edges that were never recorded: UNKNOWN) | audit.read | X ✎ |
| GET | `/api/v1/history/{type}/{id}/fields?field=` and `/timeline` | audit.read | X ✎ |
| GET | `/api/v1/recovery/incidents/{id}/conflicts` (per field: kind, evidence, recommendation, latest review, open) | audit.read | X ✎ |
| POST | `/api/v1/recovery/incidents/{id}/conflicts/reviews` `{entityType, entityId, field, decision, note}`; unreviewed conflicts block execution (409) | recovery.create | X ✎ |
| POST/GET | `/api/v1/recovery/incidents/{id}/simulations` (run six strategies on one snapshot, in memory; nothing written but the comparison) | recovery.create / audit.read | X ✎ |
| GET | `/api/v1/recovery/simulations/{id}` | audit.read | X ✎ |
| GET | `/api/v1/recovery/plans/{id}/assessment` (latest simulation, `stale` when the data changed since) | audit.read | X ✎ |
| GET | `/api/v1/integrity/rule-kinds`, `/rules`, `/rules/{id}/versions`, `/checks`, `/incidents`, `/incidents/{id}` | integrity.read | X ✎ |
| POST/PUT | `/api/v1/integrity/rules`, `/rules/{id}` (`If-Match`) | integrity.manage | X ✎ |
| POST | `/api/v1/integrity/checks` (run now; only reads business data) | integrity.manage | X ✎ |
| POST | `/api/v1/integrity/incidents/{id}/transitions` `{status: ACKNOWLEDGED|RESOLVED, note}` (`If-Match`) | integrity.manage | X ✎ |
| GET | `/api/v1/graph/schema` (live catalog, relationships with evidence, latest snapshot, changed flag) | platform admin (audited; 403 + audited denial otherwise) | G ✎ |
| GET | `/api/v1/graph/schema/versions`, `/graph/schema/diff?from=<snapshot>&to=<snapshot or live>` | platform admin (audited) | G ✎ |
| POST | `/api/v1/graph/schema/exports` `{format: SVG|PNG, version, tables}` (records an export the browser makes) | platform admin (audited) | G ✎ |
| GET | `/api/v1/recovery/simulations/{id}/strategies/{strategy}/records?category=&page=&size≤500` (exact totals; stored details; `partial` when cut at 5,000) | audit.read | G ✎ |
| GET | `/api/v1/recovery/simulations/{id}/comparisons`, `/comparisons/{a}/{b}` (immutable, exact counts, SHA-256, stored differences, `truncated`) | audit.read | G ✎ |

## Example: impact analysis response (target shape)

```json
{
  "incidentId": "…",
  "faultyChange": { "entityType": "product", "entityId": "…", "version": 7,
                    "field": "unit_price", "before": "5000.0000", "after": "500.0000" },
  "summary": { "directlyAffected": 200, "indirectlyAffected": 340, "conflicted": 3,
               "nonReversible": 12, "manualReview": 0, "traversedEdges": 1210 },
  "records": [
    { "entityType": "order_line", "entityId": "…", "version": 0,
      "classification": "DIRECTLY_AFFECTED", "path": ["product:…@7", "order_line:…@0"] }
  ],
  "graphFingerprint": "sha256:…"
}
```

`graphFingerprint` hashes the set of (entity, current version) pairs the
analysis read. A plan records it; approval and execution recompute it, and a
mismatch means the plan is stale (409).
