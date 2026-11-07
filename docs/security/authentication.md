# Authentication and authorization design (Phase 2a)

| Concern | Decision | Why |
|---|---|---|
| Password storage | BCrypt via Spring's delegating encoder (`{bcrypt}` prefix) | Slow hash; the prefix allows a future algorithm upgrade |
| Password policy | ≥ 12 characters, ≤ 72 bytes, not common, must not contain the email name | NIST SP 800-63B style; BCrypt ignores bytes after 72 |
| Access token | HS256 JWT, 15 min; claims sub, email, platform_admin, jti, iss=atlas | Short-lived; validated without a DB call |
| Tenant roles | **Not** in the token; loaded from the DB on every request | Revoking a role works on the next request, not 15 minutes later |
| Refresh token | 256-bit random, stored only as SHA-256, 14 days; HttpOnly + Secure + SameSite=Strict cookie on `/api/v1/auth` | Page scripts can't read it, other sites can't send it, and a DB leak doesn't leak sessions |
| Rotation | Every refresh issues a new token; at most one live token per family (unique index) | Limits how long a stolen token is useful |
| Reuse detection | A used or racing token revokes the whole family | A replayed token means someone else has a copy |
| Lockout | 5 failures → 15-minute lock; atomic counter in SQL; count restarts after a lock expires | Slows online guessing; parallel guesses can't race past the limit |
| Failed-login evidence | Committed **before** the 401 is thrown (TransactionTemplate) | A declarative transaction would roll back the counter and the log entry |
| Account enumeration | Same 401 message for unknown email, wrong password and locked account; unknown emails are checked against a dummy BCrypt hash | Neither the message nor the timing reveals which accounts exist |
| Known enumeration leak | Registration returns 409 for an existing email | Accepted until email verification exists; registration will then always return 202 |
| Cross-tenant access | Non-member → 404 "Organization not found"; every query filters by organization; composite FKs in the DB | No confirmation that another tenant's data exists |
| CSRF | Disabled: the API uses Authorization headers; the only cookie is SameSite=Strict and scoped to /api/v1/auth | Browsers never attach bearer headers on their own |
| Separation of duties | ORGANIZATION_ADMIN lacks `recovery.approve`; an organization must keep ≥ 1 admin (a row lock serialises demotions) | Phase 6 adds "approver ≠ plan author" |

## Not yet done

* Per-IP rate limiting of `/auth/login` (Phase 8). Lockout alone still
  allows slow password spraying across many accounts.
* Email verification and password reset (need email delivery).
* MFA (LATER tier).
* Behind a proxy, `server.forward-headers-strategy` must be set so login
  events record the real client IP (Phase 9).
* An admin can grant themselves RECOVERY_APPROVER; Phase 6 blocks
  approving one's own plan.
