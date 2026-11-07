# ATLAS — Software Requirements Specification (Phase 1 baseline)

Priority tiers decide what gets cut when time runs out:
**MUST** = the project fails without it · **SHOULD** = expected for a strong
portfolio project · **LATER** = only after every MUST is TESTED.

## 1. Actors

| Actor | Role code | Typical goals |
|---|---|---|
| Organization admin | ORGANIZATION_ADMIN | Manage members and roles; open and execute recoveries |
| Operations manager | OPERATIONS_MANAGER | Maintain catalogue and prices; manage orders and invoices; open incidents |
| Sales staff | SALES_STAFF | Create customers and orders |
| Auditor | AUDITOR | Read history and provenance; never modify |
| Recovery approver | RECOVERY_APPROVER | Approve or reject recovery plans (cannot execute them) |
| Employee | EMPLOYEE | Read business records |
| Platform admin | `users.platform_admin` | Operate the platform across tenants (no business-data edits) |

## 2. Functional requirements

### Identity and tenancy
| ID | Requirement | Tier |
|---|---|---|
| FR-ID-1 | Register, log in with email + password; passwords stored only as BCrypt/Argon2 hashes | MUST |
| FR-ID-2 | Issue short-lived JWT access tokens with refresh; log out revokes the refresh token | MUST |
| FR-ID-3 | Lock an account after N consecutive failed logins for a cooling period | MUST |
| FR-ID-4 | Record login history (success/failure, time, IP) | SHOULD |
| FR-ID-5 | Password reset and email verification via emailed single-use tokens | SHOULD |
| FR-ID-6 | TOTP multi-factor authentication | LATER |
| FR-TN-1 | Create an organization; creator becomes ORGANIZATION_ADMIN | MUST |
| FR-TN-2 | Invite members and grant/revoke roles | MUST |
| FR-TN-3 | Every request is scoped to one organization; a record from another organization is reported as *not found* (404), never 403, to avoid confirming it exists | MUST |

### Commerce domain
| ID | Requirement | Tier |
|---|---|---|
| FR-CM-1 | CRUD for products with SKU, price, cost; unique SKU per organization | MUST |
| FR-CM-2 | CRUD for customers | MUST |
| FR-CM-3 | Create orders with lines; a line's unit price is copied from the product's current price | MUST |
| FR-CM-4 | Order total is maintained as the sum of line totals | MUST |
| FR-CM-5 | Create an invoice from a confirmed order; issue it; void it | MUST |
| FR-CM-6 | Issued invoices are immutable; corrections use credit notes | MUST |
| FR-CM-7 | Inventory on hand per product, valued at cost | SHOULD |
| FR-CM-8 | Concurrent edits to the same record produce 409 Conflict, never a silent overwrite | MUST |
| FR-CM-9 | List endpoints support pagination, sorting and filtering | MUST |

### Audit and provenance
| ID | Requirement | Tier |
|---|---|---|
| FR-AU-1 | Every business write records actor, entity, version, operation, changed fields, before/after state, txn id, correlation id, reason | MUST |
| FR-AU-2 | History is append-only and tamper-evident; an admin can run chain verification | MUST |
| FR-AU-3 | Search history by entity, actor, time range, txn id | MUST |
| FR-PV-1 | Every derived value records a version-to-version provenance edge in the same transaction | MUST |
| FR-PV-2 | View the upstream and downstream provenance of any record version | MUST |

### Recovery (PASCAR)
| ID | Requirement | Tier |
|---|---|---|
| FR-RC-1 | Open an incident naming a faulty change event, with a reason | MUST |
| FR-RC-2 | Impact analysis: traverse provenance from the faulty version; classify each record DIRECTLY_AFFECTED / INDIRECTLY_AFFECTED / CONFLICTED / NON_REVERSIBLE / REQUIRES_MANUAL_REVIEW; cycle-safe | MUST |
| FR-RC-3 | Conflict detection: flag records changed legitimately after the faulty change | MUST |
| FR-RC-4 | Plan generation: ordered steps (restore / recompute / compensate / manual), each with expected before/after, risk and invariants to check | MUST |
| FR-RC-5 | Approval bound to a plan *version*; any data change that makes the plan stale invalidates the approval | MUST |
| FR-RC-6 | Execution: version-checked, idempotent, resumable after crash; issued invoices compensated with credit notes | MUST |
| FR-RC-7 | Verification: invariants and expected states checked; failure is reported as failure | MUST |
| FR-RC-8 | Recovery writes are themselves audited (actor_kind = RECOVERY) with provenance back to the incident | MUST |
| FR-RC-9 | Benchmark harness comparing PASCAR with baselines on synthetic datasets | MUST |

### UI
| ID | Requirement | Tier |
|---|---|---|
| FR-UI-1 | Login, organization switcher, catalogue, orders, invoices | MUST |
| FR-UI-2 | Audit explorer with field-level diff | MUST |
| FR-UI-3 | Recovery center: incident → impact → plan → approve → execute → verify | MUST |
| FR-UI-4 | Dependency explorer graph (React Flow) | SHOULD |
| FR-UI-5 | Dashboard with real metrics; dark theme | SHOULD |
| FR-UI-6 | Visual workflow builder; project/task management | LATER |

## 3. Non-functional requirements

These are **targets to test against**, not achieved results.

| ID | Requirement | How it will be measured |
|---|---|---|
| NFR-SEC-1 | Zero successful cross-tenant reads/writes | Automated test per endpoint, plus DB-level FK assertions |
| NFR-SEC-2 | No secrets in the repository | gitleaks in CI (Phase 8) — **met**: 0 findings over full history |
| NFR-SEC-3 | OWASP Top 10 basics: validated input, parameterised SQL, no stack traces in errors | Tests + ZAP baseline scan (Phase 8) — parameterised SQL checked; ZAP baseline in CI, **not yet run** |
| NFR-PERF-1 | p95 < 300 ms for paginated reads at 50 concurrent users on the reference environment | k6 — in the CI stack job on demo data, **not yet run**; DB side of every list query < 1 ms at 100k orders |
| NFR-PERF-2 | Impact analysis for an incident touching 10,000 records completes in < 5 s | Benchmark harness (Phase 5) |
| NFR-REL-1 | Recovery execution resumes correctly after a crash at any step | Failure-injection test (Phase 6) |
| NFR-REL-2 | Audit chain verifies after concurrent writers | `chain-concurrency-check.sh` (Phase 1, done) |
| NFR-QA-1 | ≥ 80 % line coverage on `recovery`, `audit`, `provenance` | JaCoCo gate — added in Phase 9 (was missing); **not yet run** |
| NFR-OPS-1 | One command brings up the local stack | `docker compose up` |
| NFR-OPS-2 | Every merge to main is built and tested automatically | GitHub Actions |
| NFR-A11Y-1 | WCAG 2.1 AA contrast; full keyboard navigation of core flows | axe in Playwright — **0 violations on 20 states** (fixture data) after Phase 9 fixes; keyboard-only sign-in tested; other core flows by keyboard not yet scripted |
