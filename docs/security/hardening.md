# Phase 8 — Hardening

What was reviewed, what was found, what was changed, and what was measured. Every
number below comes from a command in this repository. Where something was **not**
run, this document says so.

Status words follow `docs/STATUS.md`: NOT STARTED, IN PROGRESS, IMPLEMENTED,
TESTED, VERIFIED.

---

## 1. Threat model (STRIDE, per trust boundary)

Trust boundaries: browser ↔ API, API ↔ PostgreSQL, API ↔ anchor store,
API ↔ Prometheus / OTLP collector, CI ↔ repository.

| Threat | Where | Control | Status |
|---|---|---|---|
| **S**poofing a user | Sign-in, refresh | BCrypt passwords (delegating encoder, upgradable), lockout after 5 failures, short-lived JWTs, rotated refresh tokens (Phase 2a); **per-address sign-in limit 10/min** (Phase 8) | TESTED (unit) / IMPLEMENTED (HTTP) |
| Spoofing via a crafted `next=` link | Sign-in redirect | `safeNext()` keeps only same-origin paths | **TESTED** (12 unit tests; exploit reproduced in Chromium before the fix) |
| **T**ampering with records | API → DB | Server-side authorization on every endpoint; optimistic versions; conflicting updates rejected, never overwritten | TESTED (SQL), checked by `check-authorization.py` |
| Tampering with audit history by the application | DB | Append-only triggers, hash chain, `verify_audit_chain` | VERIFIED (Phase 1) |
| Tampering with audit history by a **database superuser** | DB | Triggers can be bypassed and every later hash recomputed. **External signed anchors** (§4) | **TESTED** end to end on PostgreSQL 16 |
| **R**epudiation of an approval | Recovery | Author cannot approve; approval bound to plan fingerprint; every action audited | TESTED (Phase 6) |
| **I**nformation disclosure across tenants | All reads | `organization_id` in every query and FK; per-endpoint tenant tests | IMPLEMENTED (integration tests not yet run) |
| Disclosure through errors | API | `problem+json` without stack traces | IMPLEMENTED |
| Disclosure through metrics | `/actuator/prometheus` | Separate filter chain, HTTP Basic, **denyAll when no credentials are configured** | IMPLEMENTED (`HardeningIT`, not yet run) |
| Disclosure through framing / sniffing / referrer | Browser | CSP on API and SPA, `frame-ancestors 'none'`, `nosniff`, `Referrer-Policy: no-referrer` | **TESTED** for the SPA (CSP check in Chromium); IMPLEMENTED for the API |
| **D**enial of service by request floods | API | Token-bucket limits (§5), 429 + `Retry-After` | **TESTED** (9 unit tests) / IMPLEMENTED (HTTP) |
| DoS by slow queries | DB | Hot queries measured at 100k orders (§3), two indexes added | **VERIFIED** (EXPLAIN ANALYZE) |
| **E**levation of privilege via frontend-only checks | API | Every permission enforced in the backend; static check compares 52 endpoints with the permission map | **VERIFIED** (0 problems) |
| Elevation via vulnerable dependencies | Build | Boot 3.5.6 → 3.5.16; npm audit 0; Dependabot | see §6 |

### Review findings and what was done

1. **Open redirect after sign-in (fixed).** The old check accepted `//evil.example`
   and `/\evil.example`, which browsers treat as another origin. Reproduced in
   Chromium, then replaced by `frontend/src/lib/safeNext.ts`, which resolves the
   value against the current origin and refuses anything that leaves it. 12 tests.
2. **Vulnerable Spring Boot (fixed).** 3.5.6 carried published advisories in Boot
   and Spring Security; upgraded the parent to 3.5.16. This was a manual check
   against public advisories, because Maven Central and OSV are not reachable from
   the build sandbox; Dependabot now watches it.
3. **A JWT test secret was committed (fixed).** Test configuration no longer holds
   a key; `TestJwtSecret` generates 48 random bytes per run. The old value stays in
   history and is listed in `.gitleaksignore` by fingerprint — it was only ever a
   test value and is not used anywhere.
4. **Four high-severity npm advisories (fixed).** Upgraded router, Vite, Vitest,
   PostCSS and friends; `npm audit` reports 0.
5. **Postgres published on all interfaces in `docker-compose.yml` (fixed).** Now
   bound to `127.0.0.1`.
6. **CSRF.** Disabled deliberately: the API authenticates with a bearer token in
   the `Authorization` header, which a cross-site form cannot set, and sets no
   authentication cookie. Re-evaluate if cookies are ever introduced.
7. **CORS.** Not enabled: the SPA and the API share one origin (the Vite proxy in development; the same host in deployment).
8. **SQL injection.** All SQL uses named parameters; the only text concatenated
   into statements is fixed `ORDER BY` fragments chosen from an allow-list. The SQL
   harness runs every statement on PostgreSQL (170 checks).
9. **Registration reveals whether an email exists.** Accepted risk (§7), now
   limited to 5 attempts per hour per address.

---

## 2. Security tooling

| Tool | Scope | Result | Status |
|---|---|---|---|
| gitleaks 8.21.2 (checksum-verified) | Full git history + working tree | 0 findings; a planted secret was caught (control) | **VERIFIED**; in CI |
| `npm audit --audit-level=moderate` | Frontend | 0 vulnerabilities | **VERIFIED**; in CI |
| `scripts/check-authorization.py` | Every controller method vs permission map and `permitAll` list | 52 endpoints, 0 problems; table in `endpoint-permissions.md` | **VERIFIED**; in CI |
| CSP check (`npm run csp-check`) | Built SPA under a strict CSP in Chromium, both themes | No violations; injected inline script blocked (control) | **TESTED**; in CI |
| hadolint 2.12.0 | Dockerfiles | Clean | **TESTED** |
| Dependabot | Maven, npm, Actions, Docker | Weekly | IMPLEMENTED (takes effect on GitHub) |
| Backend dependency scan (Trivy on `pom.xml`) | Maven | In CI (Phase 9); **not yet run** — scanner databases are blocked here | IMPLEMENTED |
| Container image scan (Trivy) | Both images | In the CI `stack` job (Phase 9); **not yet run** | IMPLEMENTED |
| ZAP baseline | Running stack (web tier → API) | In the CI `stack` job (Phase 9); **not yet run** | IMPLEMENTED |
| Checkov | `infra/terraform` | 278 passed, 0 failed, 15 skipped (each with an inline reason) | **VERIFIED**; in CI |

The SPA's CSP, as tested:

```
default-src 'self'; script-src 'self' 'sha256-WN19nWavk+fg11TprWTdwCroLmEAAw1bNLFV6WLw8Vw=';
style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self';
object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'
```

The hash belongs to the small inline theme script in `index.html`; the check
recomputes it from the build, so a changed script fails the check rather than
silently loosening the policy.

The API's CSP: `default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'`.

---

## 3. Hot queries under EXPLAIN ANALYZE

`scripts/explain-hot-queries.py` runs the application's own SQL (extracted from the
Java source) under `EXPLAIN (ANALYZE, BUFFERS)` against the Phase 7 synthetic
database: 100,000 orders, 849,693 change events, 42,170 invoices. Median of 5
runs, PostgreSQL 16 in the sandbox VM. Parameters come from real rows (the
busiest product, a mid-history transaction).

**A correction before the numbers.** The first run showed sequential scans that
were an artifact of my loader: every `created_at` had the same value, so the
planner chose differently than it would on real data. The data was fixed
(timestamps spread, triggers disabled on `invoices` only for that update) and
everything below is from the corrected database.

| Query | Statement | Before ms | After ms | Seq scan before | Indexes after |
|---|---|---|---|---|---|
| Audit list, newest first | `LIST_EVENTS` | 0.12 | 0.10 | — | ix_change_events_org_time_chain |
| Audit list, page 500 | `LIST_EVENTS` | 24.63 | 28.60 | — | ix_change_events_org_time_chain |
| Audit total | `COUNT_EVENTS` | 94.07 | 86.77 | — | chain-index unique key |
| Audit list for one record | `LIST_EVENTS` | 0.07 | 0.07 | — | entity unique key |
| Audit list for one transaction | `LIST_EVENTS` | 0.07 | 0.05 | — | ix_change_events_txn |
| **Audit list, one-hour window** | `LIST_EVENTS` | **174.21** | **0.13** | — (wrong index) | ix_change_events_org_time_chain |
| Audit list by record type | `LIST_EVENTS` | 0.16 | 0.16 | — | ix_change_events_org_time_chain |
| Audit total by record type | `COUNT_EVENTS` | 14.98 | 14.88 | — | entity unique key |
| One audit event | `FIND_EVENT` | 0.07 | 0.04 | — | change_events_org_id_key |
| Provenance downstream of a busy product | `LIST_DOWNSTREAM_EDGES` | 21.41 | 20.89 | — | ix_prov_source |
| Provenance upstream of an order | `LIST_UPSTREAM_EDGES` | 0.05 | 0.06 | — | ix_prov_target |
| Orders list | `LIST_ORDERS` | 0.04 | 0.05 | — | ix_orders_org_created |
| Orders total | `COUNT_ORDERS` | 11.47 | 11.73 | — | ix_orders_org_customer |
| **Invoices list, ISSUED** | `LIST_INVOICES` | **7.50** | **0.04** | invoices | ix_invoices_org_created |
| Invoices total | `COUNT_INVOICES` | 6.88 | 6.70 | invoices | ix_invoices_org_created |
| Lines of an order | `LIST_ORDER_LINES` | 0.04 | 0.04 | — | order_lines unique key |
| Products search `%widget 1%` | `LIST_PRODUCTS` | 1.37 | 1.36 | — | — (1,000 products) |
| Full chain verification | `VERIFY_CHAIN` | 8,606 (1 run) | — | change_events (by design) | — |

What changed (`V8__list_indexes.sql`):

- `(organization_id, occurred_at DESC, chain_index DESC)` on `change_events`, and the
  audit list now orders by `occurred_at DESC, chain_index DESC` — it previously
  ordered by `chain_index`, so a time-window filter walked the chain index from the
  newest event backwards. This replaces the old `(organization_id, occurred_at)` index.
- `(organization_id, created_at DESC)` on `invoices`.

What is left as it is, and why:

- **Counts** (87 ms for all audit events) are proportional to the tenant's history.
  They feed the "N results" label. If a tenant grows well past ~1M events, switch the
  unfiltered total to an estimate or a "more than 10,000" cap.
- **Deep offsets** (page 500: 29 ms) grow linearly. Acceptable for an audit UI;
  keyset pagination is the fix if it ever matters.
- **Full chain verification** reads every event (8.6 s at 850k). It is an on-demand
  admin check, not a request path. Anchors (§4) let routine verification start from
  the last anchor: recomputing only the tail took **45 ms** in the same database.

### Load test (NFR-PERF-1)

`scripts/load/paginated-reads.js` (k6 0.54) drives 50 constant virtual users over
the paginated list endpoints with the threshold
`http_req_duration{kind:paginated} p(95) < 300`. The script was validated with
`k6 inspect` and run against a local stub to check its own logic.

**It has not been run against ATLAS**, because the backend cannot be built in this
sandbox (Maven Central is blocked). The stub run's latencies are meaningless and are
not reported. NFR-PERF-1 stays unverified. The database side of the same reads is
the table above: every paginated list query is under 1 ms except deep offsets.

Since Phase 9 the CI `stack` job runs it for 3 minutes through the web tier, against
the demo data the seed creates (an auditor account, so every list endpoint is readable),
with `ATLAS_RATELIMIT_APIPERMINUTE=100000` (otherwise the 600/min per-user limit, §5, is
what gets measured). A pass there is evidence on a CI runner, not on the reference
environment NFR-PERF-1 names.

By hand: `k6 run -e BASE_URL=... -e EMAIL=... -e PASSWORD=... -e ORG_ID=... scripts/load/paginated-reads.js`.

---

## 4. External anchoring of the audit chain

### The gap

`verify_audit_chain()` proves the chain is internally consistent. It cannot prove it
is the *original* chain: a database superuser can disable triggers
(`session_replication_role = replica`), edit an old event, and recompute every later
hash and the chain head. The chain then verifies. The end-to-end test performs
exactly this rewrite and confirms `verify_audit_chain` is fooled.

### The design

- Every `interval` (default 5 min), `AnchorJob` reads each organization's chain head
  `(index, hash)` and, if it moved, signs
  `atlas-audit-anchor-v1|org|index|hash|epochMillis|keyId` with **Ed25519** (JDK
  provider, no new dependency).
- The signed anchor is appended **first** to an external log (one JSON line per anchor,
  one file per organization, fsync on append), then recorded in `audit_anchors`
  (append-only, for listing). If the external write fails, nothing is recorded locally.
- **Verification trusts only the external log and the public keys.** For each pair of
  consecutive anchors it recomputes the chain *from event content*
  (`recompute_audit_chain`, V9) and compares the result with the signed hash. Stored
  `hash` columns are ignored except to report where they first disagree.
- `GET /api/v1/audit/anchors/verification` (permission `audit.read`) returns one of
  `INTACT`, `NO_ANCHORS`, `REWRITTEN` (with the index range), `MISSING_EVENTS`,
  `BAD_SIGNATURE`, `BAD_ANCHOR_LOG`.

### Trust boundaries and what this does and does not prove

- The private key is configured in the application's environment and **never stored in
  the database**. Someone with only database access cannot forge an anchor.
- The anchor directory must be storage the database administrators cannot rewrite —
  a write-once mount or an object-lock bucket synced from it. On a plain local disk
  shared with the DBA, anchoring proves nothing; the configuration comment says so.
- **Exposure window:** events after the latest anchor are not yet protected. With the
  default interval, a rewrite of at most the last ~5 minutes could go undetected until
  the next anchor — and then it would be the forged version that gets anchored.
- Someone who holds **both** the database and the signing key is out of scope.
- Key rotation: old public keys stay listed (`keyId=base64,...`) so older anchors
  still verify.

### Evidence

| Check | Result | Status |
|---|---|---|
| `AnchorTest` (12 tests): untouched chain intact; consistent rewrite before an anchor caught and located; rewrite after the latest anchor **not** detectable (the exposure window, asserted); edit without rehashing caught; deleted/truncated events; untrusted key; edited anchor; reordered anchors rejected, exact repeat accepted; no anchors reported as such; file log strict round trip; external copy written first; Ed25519-only key parsing | All pass | **TESTED** |
| `scripts/anchor-end-to-end.sh`: real signer, file log, service and verifier, the repository's own SQL through psql, on a fresh PostgreSQL 16 database. Anchors at #20 and #25, no anchor when unchanged, `INTACT` with 5 unanchored events, then the superuser rewrite of event #7 | `verify_audit_chain` fooled; anchors report `REWRITTEN` in #1–#20 | **VERIFIED** (SQL + domain code) |
| SQL harness anchoring section (same attack, relative indexes) | Pass | **TESTED** |
| Spring wiring (`Anchoring`, `AnchorJob`, endpoint) | Type-checks against stubs | IMPLEMENTED |

Disabled by default (`ATLAS_ANCHOR_ENABLED=false`). When enabled, every value is
validated at startup and the application refuses to start on a bad key or missing
directory.

---

## 5. Rate limits

Token bucket per key, in memory, integer arithmetic (an earlier floating-point
refill could hand out a fraction of a token early; found by a property test and
fixed). LRU eviction caps memory at `max-tracked-clients` (100,000).

| Limit | Default | Key |
|---|---|---|
| Sign-in | 10 / minute | client address |
| Registration | 5 / hour | client address |
| Token refresh | 30 / minute | client address |
| Any other API call | 600 / minute | user id, or address when anonymous |

All are configurable under `atlas.rate-limit.*`. Rejections return
`429 application/problem+json` with `Retry-After` in whole seconds and are counted
in `atlas_ratelimit_rejected_total{limit=...}`.

Limits for sign-in apply **before** the password is checked, so a correct password
does not bypass them. Behind a reverse proxy, set `ATLAS_FORWARD_HEADERS=framework`
so the client address is the real one; leave it `none` otherwise, or any client can
pick its own address with `X-Forwarded-For`.

**Found in Phase 9:** `framework` alone is not enough behind a load balancer. The ALB
*appends* the client to `X-Forwarded-For`, Spring reads the *leftmost* value, and the
leftmost value is whatever the client sent, so a client could pick its own address
and walk around every per-address limit. The web tier (`frontend/deploy/Caddyfile`)
now resolves the client right to left through trusted (private) proxies and sends
the backend exactly one address; `scripts/web-tier-check.sh` tests three spoofing
cases.

**Per instance.** With N instances the effective limit is N times higher. That is
acceptable for the planned single-service deployment; a shared store (Redis) becomes
worth adding only if the service is scaled out *and* the per-instance limit is shown
to be insufficient. No such need has been measured, so Redis was not added.

---

## 6. Observability

- **Metrics:** Micrometer with the Prometheus registry at `/actuator/prometheus`,
  served by its own filter chain with HTTP Basic (`ATLAS_METRICS_USERNAME`,
  `ATLAS_METRICS_PASSWORD`, at least 16 characters). Without credentials it refuses
  every request. Application counters:
  `atlas_auth_logins_total{outcome}`, `atlas_ratelimit_rejected_total{limit}`,
  `atlas_recovery_steps_total{status}`, `atlas_audit_anchors_total{result}`,
  `atlas_audit_anchor_verifications_total{status}`, plus the standard JVM, HTTP and
  HikariCP metrics.
- **Traces:** Micrometer Tracing with the OpenTelemetry bridge and OTLP exporter.
  Off by default (`ATLAS_TRACING_ENABLED`); sampling 10 %. The trace id is in every
  log line next to the correlation id.
- **Latency histograms** (Phase 9): `http.server.requests` publishes buckets
  (SLOs 50/100/300 ms/1 s), so p95 can be computed in Prometheus.
- **Prometheus + Grafana** (Phase 9, `docker compose --profile observability`):
  `ops/prometheus` holds the scrape config and six alert rules (p95 > 300 ms, 5xx > 1 %,
  anchor mismatch, anchoring failing, sign-in abuse, failed recovery steps) with
  **promtool unit tests** that check each fires on bad series and stays quiet on good
  ones. `ops/grafana` provisions a 12-panel dashboard; every panel query parses
  (promtool). None of it has scraped a running backend yet.
- **In AWS**, CloudWatch alarms cover the edge (p95 latency, 5xx, health) and the
  database; the application metrics are not scraped there yet (follow-up).

Status: IMPLEMENTED. `HardeningIT` checks the counter appears and that the endpoint
refuses missing and wrong credentials; it has not run yet.

---

## 7. Accepted risks and follow-ups

| Item | Why it is open | Next step |
|---|---|---|
| Spring Boot 3.5 open-source support has ended | 3.5.16 is patched today; a 4.x migration is a separate piece of work | Plan the Boot 4 migration before deployment |
| Base images pinned by tag, not digest | Digests cannot be resolved without Docker Hub | Pin digests in CI; Dependabot will then update them |
| ZAP baseline, Trivy, backend dependency scan | In CI since Phase 9, not yet run | Read the first CI results |
| k6 against ATLAS | In the CI stack job since Phase 9 (runner, not the reference environment) | Re-run on the deployed environment for the NFR-PERF-1 claim |
| Registration reveals existing emails | Changing it needs an email-verification flow | Rate-limited now; revisit with email verification |
| Bulk import of change events in one transaction is quadratic | Chain-head row (Phase 7) | Any bulk import must commit in batches |
| Rate limits per instance | See §5 | Redis only on a measured need |
| Audit counts grow with history | See §3 | Estimate or cap the unfiltered total past ~1M events |
