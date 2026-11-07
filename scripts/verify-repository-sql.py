#!/usr/bin/env python3
"""
Runs the SQL that the Java repositories actually contain against a freshly
migrated PostgreSQL database, following realistic scenarios, and checks
the results.

The statements are extracted from the Java source (every
`static final String NAME = \"\"\"...\"\"\";` text block), so this verifies
the code's SQL, not a copy. Named parameters (:name) are replaced with SQL
literals. What this does NOT check: JDBC parameter typing and the Java
mapping code; the Testcontainers integration tests cover those.

Usage: PGHOST=... PGUSER=... scripts/verify-repository-sql.py
"""
import os
import re
import subprocess
import sys
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAVA = ROOT / "backend/src/main/java"
MIGRATIONS = ROOT / "backend/src/main/resources/db/migration"
DB = f"atlas_repo_sql_{os.getpid()}"

failures = 0
passes = 0


# ---------------------------------------------------------------- extraction
def extract_statements():
    """Text blocks, also concatenated with other constants of the same file: A = \"\"\"..\"\"\" + B;"""
    found = {}
    block = r'"""\n(?:.*?)"""'
    pattern = re.compile(r'static final String (\w+) =\s*((?:' + block + r'|\w+)(?:\s*\+\s*(?:' + block + r'|\w+))*);', re.S)
    part = re.compile(r'"""\n(.*?)"""|(\w+)', re.S)
    for path in JAVA.rglob("*.java"):
        raw = dict(pattern.findall(path.read_text()))

        def resolve(expr, depth=0):
            out = []
            for text, ref in part.findall(expr):
                out.append(text if not ref else resolve(raw[ref], depth + 1))
            return "".join(out)
        fragments = {ref for expr in raw.values() for _, ref in part.findall(expr) if ref}
        for name, expr in raw.items():
            if name not in fragments:   # a shared fragment is checked through the statements using it
                # Java text-block escapes used in our SQL: only \\ -> \
                found[name] = resolve(expr).replace("\\\\", "\\")
    return found


SQL = extract_statements()


def literal(value):
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, (int, float)):
        return str(value)
    if isinstance(value, Raw):
        return value.sql
    return "'" + str(value).replace("'", "''") + "'"


class Raw:
    def __init__(self, sql):
        self.sql = sql


USED = set()


def bind(name, params, fmt=None):
    USED.add(name)
    sql = SQL[name]
    if fmt is not None:
        sql = sql % fmt
    used = set(re.findall(r"(?<![:\w]):([A-Za-z]\w*)", sql))
    missing = used - params.keys()
    if missing:
        raise SystemExit(f"{name}: no value for {sorted(missing)}")
    return re.sub(r"(?<![:\w]):([A-Za-z]\w*)", lambda m: literal(params[m.group(1)]), sql)


# ---------------------------------------------------------------- psql
def psql(sql, db=DB, expect_error=None):
    r = subprocess.run(["psql", "-X", "-At", "-F", "|", "-v", "ON_ERROR_STOP=1", "-d", db, "-c", sql],
                       capture_output=True, text=True)
    if expect_error:
        return r.returncode != 0 and expect_error in r.stderr, r.stderr.strip()
    if r.returncode != 0:
        raise RuntimeError(r.stderr.strip())
    rows = Rows(line for line in r.stdout.splitlines() if line != "" and not TAG.match(line))
    rows.tags = [line for line in r.stdout.splitlines() if TAG.match(line)]
    return rows


TAG = re.compile(r"^(INSERT 0 \d+|UPDATE \d+|DELETE \d+)$")


class Rows(list):
    tags = []


def run(_statement, fmt=None, **params):
    return psql(bind(_statement, params, fmt))


def check(label, condition, detail=""):
    global failures, passes
    if condition:
        passes += 1
        print(f"   PASS  {label}")
    else:
        failures += 1
        print(f"   FAIL  {label}  {detail}")


def rows_affected(out):
    """psql prints a command tag (UPDATE n / DELETE n / INSERT 0 n) after data-changing statements."""
    return int(out.tags[-1].split()[-1]) if out.tags else None


def sha_hex(text):
    import hashlib
    return Raw("decode('" + hashlib.sha256(text.encode()).hexdigest() + "', 'hex')")


# ---------------------------------------------------------------- setup
subprocess.run(["psql", "-X", "-q", "-d", "postgres", "-c", f"CREATE DATABASE {DB}"], check=True)
try:
    for f in sorted(MIGRATIONS.glob("V*__*.sql"), key=lambda p: int(p.name[1:].split("__")[0])):
        subprocess.run(["psql", "-X", "-q", "-v", "ON_ERROR_STOP=1", "-d", DB, "--single-transaction",
                        "-f", str(f)], check=True, capture_output=True)

    print(f"== {len(SQL)} SQL statements extracted from Java source")
    T0 = "2026-11-05T10:00:00Z"
    T_LOCK = "2026-11-05T10:15:00Z"
    T_LATER = "2026-11-05T10:20:00Z"

    print("== identity: users, lockout, login history")
    u1 = run("INSERT_USER", email="Kaavya@atlas.test", displayName="Kaavya (test)", passwordHash="{bcrypt}test")[0]
    u2 = run("INSERT_USER", email="sales@atlas.test", displayName="Sales (test)", passwordHash="{bcrypt}test")[0]
    ok, _ = psql(bind("INSERT_USER", dict(email="KAAVYA@ATLAS.TEST", displayName="dup", passwordHash="x")),
                 expect_error="ux_users_email_ci")
    check("email uniqueness is case-insensitive", ok)
    found = run("FIND_USER_BY_EMAIL", email="kaavya@ATLAS.test")
    check("lookup by email ignores case", len(found) == 1 and found[0].startswith(u1), found)
    check("lookup by id", run("FIND_USER_BY_ID", id=u2)[0].split("|")[1] == "sales@atlas.test")
    check("lookup of unknown email returns no row", run("FIND_USER_BY_EMAIL", email="nobody@atlas.test") == [])

    for i in range(1, 6):
        out = run("RECORD_LOGIN_FAILURE", id=u1, now=T0, maxFailures=5, lockUntil=T_LOCK)[0]
        count, locked = out.split("|")
        if i < 5:
            check(f"failure {i}: counted, not yet locked", count == str(i) and locked == "", out)
        else:
            check("failure 5: account locked until +15 min", count == "5" and locked.startswith("2026-11-05 10:15:00"), out)
    out = run("RECORD_LOGIN_FAILURE", id=u1, now=T_LATER, maxFailures=5, lockUntil="2026-11-05T10:35:00Z")[0]
    check("first failure after lock expiry restarts the count at 1 and unlocks", out == "1|", out)
    run("RECORD_LOGIN_FAILURE", id=u1, now=T_LATER, maxFailures=5, lockUntil="2026-11-05T10:35:00Z")
    check("reset clears failures", rows_affected(run("RESET_LOGIN_FAILURES", id=u1, now=T_LATER)) == 1)
    check("reset is a no-op when already clean", rows_affected(run("RESET_LOGIN_FAILURES", id=u1, now=T_LATER)) == 0)

    run("INSERT_LOGIN_EVENT", userId=None, email="nobody@atlas.test", outcome="BAD_CREDENTIALS",
        ip="127.0.0.1", userAgent="harness")
    run("INSERT_LOGIN_EVENT", userId=u1, email="kaavya@atlas.test", outcome="SUCCESS", ip="::1", userAgent=None)
    check("login events store IPv4 and IPv6 peers",
          psql("SELECT count(*) FROM login_events WHERE ip_address IN ('127.0.0.1', '::1')") == ["2"])

    print("== identity: refresh-token rotation")
    fam = str(uuid.uuid4())
    run("INSERT_REFRESH_TOKEN", userId=u1, familyId=fam, tokenHash=sha_hex("t1"), now=T0,
        expiresAt="2026-11-19T10:00:00Z", userAgent="harness")
    tok = run("FIND_REFRESH_TOKEN", tokenHash=sha_hex("t1"))
    check("token found by hash", len(tok) == 1 and tok[0].split("|")[2] == fam, tok)
    tok_id = tok[0].split("|")[0]
    check("first rotation succeeds", rows_affected(run("MARK_TOKEN_ROTATED", id=tok_id, now=T0)) == 1)
    check("second rotation of the same token is refused (reuse signal)",
          rows_affected(run("MARK_TOKEN_ROTATED", id=tok_id, now=T0)) == 0)
    run("INSERT_REFRESH_TOKEN", userId=u1, familyId=fam, tokenHash=sha_hex("t2"), now=T0,
        expiresAt="2026-11-19T10:00:00Z", userAgent="harness")
    check("reuse revokes the whole family",
          run("REVOKE_TOKEN_FAMILY", familyId=fam, reason="REUSE_DETECTED") == ["2"])
    check("revoked successor can no longer rotate",
          rows_affected(run("MARK_TOKEN_ROTATED", id=run("FIND_REFRESH_TOKEN", tokenHash=sha_hex("t2"))[0].split("|")[0], now=T0)) == 0)

    print("== organization: tenancy and roles")
    org = run("INSERT_ORGANIZATION", slug="acme", name="Acme (test)")[0].split("|")
    o1 = org[0]
    check("organization defaults: INR, ACTIVE, version 0", org[3:5] == ["INR", "ACTIVE"] and org[6] == "0", org)
    ok, _ = psql(bind("INSERT_ORGANIZATION", dict(slug="acme", name="Other")), expect_error="organizations_slug_key")
    check("slug is unique", ok)
    o2 = run("INSERT_ORGANIZATION", slug="globex", name="Globex (test)")[0].split("|")[0]

    run("INSERT_MEMBER", orgId=o1, userId=u1)
    run("INSERT_MEMBER_ROLE", orgId=o1, userId=u1, role="ORGANIZATION_ADMIN")
    run("INSERT_MEMBER_ROLE", orgId=o1, userId=u1, role="ORGANIZATION_ADMIN")  # idempotent
    access = run("LOAD_ACCESS", orgId=o1, userId=u1)
    roles, perms = access[0].split("|")
    check("admin access loads role and permissions", roles == '["ORGANIZATION_ADMIN"]'
          and '"member.manage"' in perms and '"catalogue.write"' in perms, access)
    check("admin does NOT get recovery.approve (separation of duties)", "recovery.approve" not in perms)
    check("non-member gets no access row (-> 404)", run("LOAD_ACCESS", orgId=o1, userId=u2) == [])
    check("member of org A has no access to org B", run("LOAD_ACCESS", orgId=o2, userId=u1) == [])

    run("INSERT_MEMBER", orgId=o1, userId=u2)
    run("INSERT_MEMBER_ROLE", orgId=o1, userId=u2, role="EMPLOYEE")
    emp = run("LOAD_ACCESS", orgId=o1, userId=u2)[0].split("|")[1]
    check("employee is read-only", '"catalogue.read"' in emp and "catalogue.write" not in emp, emp)
    check("lock organization row", run("LOCK_ORGANIZATION", orgId=o1) == [o1])
    check("u1 is the only admin -> 0 other admins", run("COUNT_OTHER_ACTIVE_ADMINS", orgId=o1, userId=u1) == ["0"])
    check("from u2's view there is 1 other admin", run("COUNT_OTHER_ACTIVE_ADMINS", orgId=o1, userId=u2) == ["1"])

    run("DELETE_ROLES_NOT_IN", orgId=o1, userId=u2, roles='["SALES_STAFF"]')
    run("INSERT_MEMBER_ROLE", orgId=o1, userId=u2, role="SALES_STAFF")
    member = run("FIND_MEMBER", orgId=o1, userId=u2)[0].split("|")
    check("role replacement leaves exactly the new set", member[-1] == '["SALES_STAFF"]', member)
    check("membership version bumps", run("BUMP_MEMBER_VERSION", orgId=o1, userId=u2) == ["1"])
    check("member list is paginated and tenant-scoped",
          len(run("LIST_MEMBERS", orgId=o1, limit=10, offset=0)) == 2
          and len(run("LIST_MEMBERS", orgId=o1, limit=1, offset=1)) == 1
          and run("COUNT_MEMBERS", orgId=o1) == ["2"] and run("COUNT_MEMBERS", orgId=o2) == ["0"])
    mine = run("LIST_ORGANIZATIONS_FOR_USER", userId=u1)
    check("user's organization list shows their roles", len(mine) == 1 and mine[0].endswith('["ORGANIZATION_ADMIN"]'), mine)
    check("all 6 roles listed", len(run("LIST_ROLE_CODES")) == 6)

    print("== catalogue: products, optimistic locking, search")
    p = run("INSERT_PRODUCT", orgId=o1, sku="P100", name="Widget", unitPrice=5000, unitCost=3000, active=True)[0].split("|")
    p1 = p[0]
    check("product created at version 0 with 4-decimal money", p[4] == "5000.0000" and p[-1] == "0", p)
    run("INSERT_PRODUCT", orgId=o2, sku="P100", name="Globex widget", unitPrice=7000, unitCost=4000, active=True)
    check("same SKU allowed in another tenant", True)
    ok, _ = psql(bind("INSERT_PRODUCT", dict(orgId=o1, sku="P100", name="dup", unitPrice=1, unitCost=1, active=True)),
                 expect_error="products_organization_id_sku_key")
    check("duplicate SKU in the same tenant rejected", ok)
    check("other tenant cannot read the product", run("FIND_PRODUCT", orgId=o2, id=p1) == [])
    check("other tenant cannot lock it for update", run("FIND_PRODUCT_FOR_UPDATE", orgId=o2, id=p1) == [])

    txn = str(uuid.uuid4())
    ev0 = run("INSERT_CHANGE_EVENT", orgId=o1, txnId=txn, actorKind="USER", actorUserId=u1, entityType="product",
              entityId=p1, version=0, operation="INSERT",
              changedFields='["active","name","sku","unit_cost","unit_price"]', before=None,
              after='{"sku":"P100","name":"Widget","unit_price":5000.0000,"unit_cost":3000.0000,"active":true}',
              reason="product created", correlationId="harness-1")
    check("change event v0 recorded", len(ev0) == 1)

    upd = run("UPDATE_PRODUCT", orgId=o1, id=p1, name="Widget", unitPrice=500, unitCost=3000, active=True,
              expectedVersion=0)
    check("update with current version succeeds and bumps to v1", len(upd) == 1 and upd[0].split("|")[-1] == "1"
          and upd[0].split("|")[4] == "500.0000", upd)
    check("update with stale version changes nothing (-> 409)",
          run("UPDATE_PRODUCT", orgId=o1, id=p1, name="X", unitPrice=1, unitCost=1, active=False, expectedVersion=0) == [])
    check("update in the wrong tenant changes nothing",
          run("UPDATE_PRODUCT", orgId=o2, id=p1, name="X", unitPrice=1, unitCost=1, active=False, expectedVersion=1) == [])
    run("INSERT_CHANGE_EVENT", orgId=o1, txnId=str(uuid.uuid4()), actorKind="USER", actorUserId=u1,
        entityType="product", entityId=p1, version=1, operation="UPDATE", changedFields='["unit_price"]',
        before='{"sku":"P100","name":"Widget","unit_price":5000.0000,"unit_cost":3000.0000,"active":true}',
        after='{"sku":"P100","name":"Widget","unit_price":500.0000,"unit_cost":3000.0000,"active":true}',
        reason="the faulty edit (test)", correlationId=None)
    check("SYSTEM actor without user id is accepted",
          len(run("INSERT_CHANGE_EVENT", orgId=o2, txnId=str(uuid.uuid4()), actorKind="SYSTEM", actorUserId=None,
                  entityType="product", entityId=str(uuid.uuid4()), version=0, operation="INSERT",
                  changedFields="[]", before=None, after="{}", reason=None, correlationId=None)) == 1)

    run("INSERT_PRODUCT", orgId=o1, sku="T-100", name="100% Cotton tee", unitPrice=900, unitCost=300, active=True)
    run("INSERT_PRODUCT", orgId=o1, sku="U1000", name="1000 units bundle", unitPrice=10, unitCost=5, active=False)
    order = "created_at DESC"

    def search(q, active=None):
        pat = None if q is None else "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        rows = run("LIST_PRODUCTS", fmt=order, orgId=o1, pattern=pat, active=active, limit=50, offset=0)
        count = run("COUNT_PRODUCTS", orgId=o1, pattern=pat, active=active)[0]
        return [r.split("|")[2] for r in rows], int(count)

    check("no filter lists all of this tenant's products", search(None) == (["U1000", "T-100", "P100"], 3), search(None))
    check("search is case-insensitive on name and SKU", search("WIDG")[0] == ["P100"] and search("t-1")[0] == ["T-100"])
    check("a literal % in the search is not a wildcard", search("100%") == (["T-100"], 1), search("100%"))
    check("active filter", search(None, active=False) == (["U1000"], 1) and search(None, active=True)[1] == 2)
    check("pagination", run("LIST_PRODUCTS", fmt="sku ASC", orgId=o1, pattern=None, active=None, limit=1, offset=1)[0].split("|")[2] == "T-100")

    print("== audit: query, filters, chain verification")
    allev = run("LIST_EVENTS", orgId=o1, entityType=None, entityId=None, actorUserId=None, txnId=None,
                fromTs=None, toTs=None, limit=10, offset=0)
    check("org A sees its 2 events, newest first", len(allev) == 2 and allev[0].split("|")[1] == "2", allev)
    check("changed_fields comes back as a JSON array", allev[0].split("|")[9] == '["unit_price"]', allev[0])
    check("events carry the actor's display name", allev[0].split("|")[16] == "Kaavya (test)", allev[0])
    check("filter by entity", len(run("LIST_EVENTS", orgId=o1, entityType="product", entityId=p1, actorUserId=u1,
                                      txnId=None, fromTs=None, toTs=None, limit=10, offset=0)) == 2)
    check("filter by txn id", len(run("LIST_EVENTS", orgId=o1, entityType=None, entityId=None, actorUserId=None,
                                      txnId=txn, fromTs=None, toTs=None, limit=10, offset=0)) == 1)
    check("time window excludes everything in the future",
          run("COUNT_EVENTS", orgId=o1, entityType=None, entityId=None, actorUserId=None, txnId=None,
              fromTs="2100-01-01T00:00:00Z", toTs=None) == ["0"])
    check("org B cannot see org A's events",
          run("COUNT_EVENTS", orgId=o2, entityType="product", entityId=p1, actorUserId=None, txnId=None,
              fromTs=None, toTs=None) == ["0"])
    ev_id = ev0[0]
    check("SYSTEM events have no actor name", run("LIST_EVENTS", orgId=o2, entityType=None, entityId=None,
          actorUserId=None, txnId=None, fromTs=None, toTs=None, limit=10, offset=0)[0].split("|")[16] == "")
    check("event by id is tenant-scoped", len(run("FIND_EVENT", orgId=o1, id=ev_id)) == 1
          and run("FIND_EVENT", orgId=o2, id=ev_id) == [])
    check("chain verifies: intact, 2 events", run("VERIFY_CHAIN", orgId=o1) == ["|2"])

    print("== idempotency keys")
    claimed = run("CLAIM_IDEMPOTENCY_KEY", orgId=o1, key="order-create-0001", userId=u1, method="POST",
                  path="/api/v1/orders", requestHash=sha_hex("body-1"))
    check("first claim of a key succeeds", claimed == ["order-create-0001"], claimed)
    check("second claim of the same key returns nothing",
          run("CLAIM_IDEMPOTENCY_KEY", orgId=o1, key="order-create-0001", userId=u1, method="POST",
              path="/api/v1/orders", requestHash=sha_hex("body-1")) == [])
    check("same key is free in another tenant",
          run("CLAIM_IDEMPOTENCY_KEY", orgId=o2, key="order-create-0001", userId=u1, method="POST",
              path="/api/v1/orders", requestHash=sha_hex("body-1")) == ["order-create-0001"])
    stored = run("FIND_IDEMPOTENCY_KEY", orgId=o1, key="order-create-0001")[0].split("|")
    check("claimed key is IN_PROGRESS with no response", stored[4] == "IN_PROGRESS" and stored[5] == "", stored)
    check("completion stores the response",
          rows_affected(run("COMPLETE_IDEMPOTENCY_KEY", orgId=o1, key="order-create-0001", responseStatus=201,
                            responseBody='{"orderNumber":"SO-000001"}')) == 1)
    check("a key completes only once",
          rows_affected(run("COMPLETE_IDEMPOTENCY_KEY", orgId=o1, key="order-create-0001", responseStatus=500,
                            responseBody='{}')) == 0)
    stored = run("FIND_IDEMPOTENCY_KEY", orgId=o1, key="order-create-0001")[0].split("|")
    check("replay data: COMPLETED, 201, original body",
          stored[4:] == ["COMPLETED", "201", '{"orderNumber": "SO-000001"}'], stored)

    print("== sales: customers")
    def ce(entity_type, entity_id, version, operation, after, before=None, changed="[]", org=None):
        return run("INSERT_CHANGE_EVENT", orgId=org or o1, txnId=str(uuid.uuid4()), actorKind="USER",
                   actorUserId=u1, entityType=entity_type, entityId=entity_id, version=version,
                   operation=operation, changedFields=changed, before=before, after=after, reason=None,
                   correlationId=None)

    def edge(src, src_field, dst, dst_field, relation):
        return run("INSERT_PROVENANCE_EDGE", orgId=o1, txnId=str(uuid.uuid4()),
                   sourceType=src[0], sourceId=src[1], sourceVersion=src[2], sourceField=src_field,
                   targetType=dst[0], targetId=dst[1], targetVersion=dst[2], targetField=dst_field,
                   relation=relation, operation="order.add_line")

    c = run("INSERT_CUSTOMER", orgId=o1, name="Acme Traders (test)", email=None)[0].split("|")
    c1 = c[0]
    check("customer created at v0 without email", c[3] == "" and c[-1] == "0", c)
    check("customer is invisible to another tenant", run("FIND_CUSTOMER", orgId=o2, id=c1) == []
          and run("FIND_CUSTOMER_FOR_UPDATE", orgId=o2, id=c1) == [])
    check("customer update with current version", run("UPDATE_CUSTOMER", orgId=o1, id=c1, name="Acme Traders (test)",
                                                      email="ops@acme.test", expectedVersion=0)[0].endswith("|1"))
    check("stale customer update changes nothing",
          run("UPDATE_CUSTOMER", orgId=o1, id=c1, name="X", email=None, expectedVersion=0) == [])
    check("customer search by email", len(run("LIST_CUSTOMERS", fmt="name ASC", orgId=o1, pattern="%acme.test%",
                                              limit=10, offset=0)) == 1
          and run("COUNT_CUSTOMERS", orgId=o1, pattern=None) == ["1"]
          and run("COUNT_CUSTOMERS", orgId=o2, pattern=None) == ["0"])
    check("customer lookup", len(run("FIND_CUSTOMER", orgId=o1, id=c1)) == 1)

    print("== sales: order pricing records provenance")
    check("first order number in this tenant is 1", run("NEXT_DOCUMENT_NUMBER", orgId=o1, kind="ORDER") == ["1"])
    order = run("INSERT_ORDER", orgId=o1, orderNumber="SO-000001", customerId=c1, createdBy=u1)[0].split("|")
    oid = order[0]
    check("order starts DRAFT, total 0, v0", order[4] == "DRAFT" and order[5] == "0.0000" and order[10] == "0", order)
    ce("order", oid, 0, "INSERT", '{"status":"DRAFT"}')
    ok, _ = psql(bind("INSERT_ORDER", dict(orgId=o1, orderNumber="SO-000002", customerId=str(uuid.uuid4()),
                                           createdBy=u1)), expect_error="orders_organization_id_customer_id_fkey")
    check("order for a customer of no tenant is rejected", ok)
    check("order lock is tenant-scoped", len(run("FIND_ORDER_FOR_UPDATE", orgId=o1, id=oid)) == 1
          and run("FIND_ORDER", orgId=o2, id=oid) == [])

    # Line 1: priced from the FAULTY product version p1@v1 (500 instead of 5000).
    l1 = run("INSERT_ORDER_LINE", orgId=o1, orderId=oid, productId=p1, description="P100 - Widget",
             quantity=3, unitPrice=500)[0].split("|")
    check("line 1 gets line_no 1 and a DB-computed total 1500", l1[2] == "1" and l1[7] == "1500.0000", l1)
    ce("order_line", l1[0], 0, "INSERT", '{"unit_price":500}')
    edge(("product", p1, 1), "unit_price", ("order_line", l1[0], 0), "unit_price", "DERIVED_VALUE")
    o_v1 = run("RECOMPUTE_ORDER_TOTAL", orgId=o1, id=oid)[0].split("|")
    check("order total recomputed from lines: 1500 at v1", o_v1[5] == "1500.0000" and o_v1[10] == "1", o_v1)
    ce("order", oid, 1, "UPDATE", '{"total_amount":1500}', before='{"total_amount":0}', changed='["total_amount"]')
    edge(("order_line", l1[0], 0), "line_total", ("order", oid, 1), "total_amount", "AGGREGATED_INTO")

    # Line 2: an unrelated, correctly priced product.
    t100 = psql(f"SELECT id FROM products WHERE organization_id = '{o1}' AND sku = 'T-100'")[0]
    l2 = run("INSERT_ORDER_LINE", orgId=o1, orderId=oid, productId=t100, description="T-100 - tee",
             quantity=2, unitPrice=900)[0].split("|")
    check("line 2 gets line_no 2", l2[2] == "2" and l2[7] == "1800.0000", l2)
    ce("order_line", l2[0], 0, "INSERT", '{"unit_price":900}')
    ok, err = psql(bind("INSERT_PROVENANCE_EDGE", dict(orgId=o1, txnId=str(uuid.uuid4()), sourceType="product",
                   sourceId=t100, sourceVersion=0, sourceField="unit_price", targetType="order_line",
                   targetId=l2[0], targetVersion=0, targetField="unit_price", relation="DERIVED_VALUE",
                   operation="order.add_line")),
                   expect_error="is not present in table \"change_events\"")
    check("edge from a product version with no change event is rejected", ok, err)
    ce("product", t100, 0, "INSERT", '{"unit_price":900}')
    edge(("product", t100, 0), "unit_price", ("order_line", l2[0], 0), "unit_price", "DERIVED_VALUE")
    o_v2 = run("RECOMPUTE_ORDER_TOTAL", orgId=o1, id=oid)[0].split("|")
    check("order total 3300 at v2", o_v2[5] == "3300.0000" and o_v2[10] == "2", o_v2)
    ce("order", oid, 2, "UPDATE", '{"total_amount":3300}', before='{"total_amount":1500}', changed='["total_amount"]')
    for line in (l1, l2):
        edge(("order_line", line[0], 0), "line_total", ("order", oid, 2), "total_amount", "AGGREGATED_INTO")
    check("lines listed in order", [r.split("|")[2] for r in run("LIST_ORDER_LINES", orgId=o1, orderId=oid)] == ["1", "2"])

    conf = run("SET_ORDER_STATUS", orgId=o1, id=oid, status="CONFIRMED", expectedVersion=2)[0].split("|")
    check("confirm sets placed_at and v3", conf[4] == "CONFIRMED" and conf[7] != "" and conf[10] == "3", conf)
    ce("order", oid, 3, "UPDATE", '{"status":"CONFIRMED"}', before='{"status":"DRAFT"}', changed='["status"]')
    check("stale status change refused", run("SET_ORDER_STATUS", orgId=o1, id=oid, status="CANCELLED",
                                             expectedVersion=2) == [])
    check("order list filters by status and customer",
          len(run("LIST_ORDERS", fmt="created_at DESC", orgId=o1, status="CONFIRMED", customerId=c1, limit=10, offset=0)) == 1
          and run("COUNT_ORDERS", orgId=o1, status="DRAFT", customerId=None) == ["0"]
          and run("COUNT_ORDERS", orgId=o2, status=None, customerId=None) == ["0"])

    print("== billing: invoice, issue, credit, void")
    check("no active invoice yet", run("ACTIVE_INVOICE_EXISTS", orgId=o1, orderId=oid) == ["f"])
    check("invoice numbering starts at 1", run("NEXT_DOCUMENT_NUMBER", orgId=o1, kind="INVOICE") == ["1"])
    inv = run("INSERT_INVOICE", orgId=o1, invoiceNumber="INV-000001", orderId=oid, totalAmount=3300)[0].split("|")
    iid = inv[0]
    check("invoice starts DRAFT at v0", inv[4] == "DRAFT" and inv[-1] == "0", inv)
    ils = []
    for n, line in enumerate((l1, l2), start=1):
        il = run("INSERT_INVOICE_LINE", orgId=o1, invoiceId=iid, lineNo=n, orderLineId=line[0],
                 description=line[4], quantity=int(line[5]), unitPrice=line[6])[0].split("|")
        ce("invoice_line", il[0], 0, "INSERT", '{}')
        edge(("order_line", line[0], 0), "unit_price", ("invoice_line", il[0], 0), "unit_price", "COPIED_INTO")
        ils.append(il)
    ce("invoice", iid, 0, "INSERT", '{"total_amount":3300}')
    for il in ils:
        edge(("invoice_line", il[0], 0), "line_total", ("invoice", iid, 0), "total_amount", "AGGREGATED_INTO")
    check("invoice lines copy totals", [r.split("|")[7] for r in run("LIST_INVOICE_LINES", orgId=o1, invoiceId=iid)]
          == ["1500.0000", "1800.0000"])
    check("order now has an active invoice", run("ACTIVE_INVOICE_EXISTS", orgId=o1, orderId=oid) == ["t"])
    ok, _ = psql(bind("INSERT_INVOICE", dict(orgId=o1, invoiceNumber="INV-000002", orderId=oid, totalAmount=1)),
                 expect_error="ux_invoices_active_per_order")
    check("second active invoice for the order rejected", ok)

    issued = run("SET_INVOICE_STATUS", orgId=o1, id=iid, status="ISSUED", expectedVersion=0)[0].split("|")
    check("issue stamps issued_at, v1", issued[4] == "ISSUED" and issued[6] != "" and issued[-1] == "1", issued)
    check("invoice lock and lookup are tenant-scoped", len(run("FIND_INVOICE_FOR_UPDATE", orgId=o1, id=iid)) == 1
          and run("FIND_INVOICE", orgId=o2, id=iid) == [])
    check("nothing credited yet", run("SUM_CREDITED", orgId=o1, invoiceId=iid) == ["0|0"])
    check("credit note numbering", run("NEXT_DOCUMENT_NUMBER", orgId=o1, kind="CREDIT_NOTE") == ["1"])
    cn = run("INSERT_CREDIT_NOTE", orgId=o1, creditNoteNumber="CN-000001", invoiceId=iid, amount=100,
             reason="goodwill (test)")[0].split("|")
    check("credit note issued immediately", cn[5] == "ISSUED" and cn[6] != "", cn)
    check("credited sum and count", run("SUM_CREDITED", orgId=o1, invoiceId=iid) == ["100.0000|1"]
          and len(run("LIST_CREDIT_NOTES", orgId=o1, invoiceId=iid)) == 1)

    o2id = run("INSERT_ORDER", orgId=o1, orderNumber="SO-000002", customerId=c1, createdBy=u1)[0].split("|")[0]
    run("SET_ORDER_STATUS", orgId=o1, id=o2id, status="CONFIRMED", expectedVersion=0)
    note = run("SET_DELIVERY_NOTE", orgId=o1, id=o2id, deliveryNote="Leave at gate 2", expectedVersion=1)[0].split("|")
    check("delivery note is editable on a confirmed order: new version, status and total untouched",
          note[11] == "Leave at gate 2" and note[10] == "2" and note[4] == "CONFIRMED" and note[5] == "0.0000", note)
    check("a stale delivery-note edit is refused",
          run("SET_DELIVERY_NOTE", orgId=o1, id=o2id, deliveryNote="x", expectedVersion=1) == [])
    draft = run("INSERT_INVOICE", orgId=o1, invoiceNumber="INV-000002", orderId=o2id, totalAmount=0)[0].split("|")[0]
    voided = run("SET_INVOICE_STATUS", orgId=o1, id=draft, status="VOID", expectedVersion=0)[0].split("|")
    check("a never-issued DRAFT can be voided without an issued_at (V5 fix)", voided[4] == "VOID" and voided[6] == "", voided)
    check("after voiding, the order may be invoiced again", run("ACTIVE_INVOICE_EXISTS", orgId=o1, orderId=o2id) == ["f"])
    check("invoice list filters", len(run("LIST_INVOICES", fmt="invoice_number ASC", orgId=o1, status=None, orderId=None,
                                          limit=10, offset=0)) == 2
          and run("COUNT_INVOICES", orgId=o1, status="ISSUED", orderId=oid) == ["1"])

    print("== provenance: read API and the precision of the recorded graph")
    down = run("LIST_DOWNSTREAM_EDGES", orgId=o1, entityType="product", entityId=p1, version=1)
    check("faulty product v1 has exactly one direct consumer: line 1",
          len(down) == 1 and down[0].split("|")[5] == l1[0], down)
    check("product v0 (the correct price) fed nothing",
          run("LIST_DOWNSTREAM_EDGES", orgId=o1, entityType="product", entityId=p1, version=0) == [])
    up = run("LIST_UPSTREAM_EDGES", orgId=o1, entityType="invoice", entityId=iid, version=None)
    check("invoice v0 total aggregates both invoice lines", len(up) == 2, up)
    check("provenance is tenant-scoped",
          run("LIST_DOWNSTREAM_EDGES", orgId=o2, entityType="product", entityId=p1, version=None) == [])

    reach = psql(f"""
        WITH RECURSIVE reach(t, id, v) AS (
            SELECT CAST('product' AS varchar), CAST('{p1}' AS uuid), CAST(1 AS bigint)
          UNION
            SELECT e.target_entity_type, e.target_entity_id, e.target_entity_version
              FROM provenance_edges e JOIN reach r
                ON e.source_entity_type = r.t AND e.source_entity_id = r.id AND e.source_entity_version = r.v
             WHERE e.organization_id = '{o1}')
        SELECT t || '@' || v || ':' || id FROM reach ORDER BY 1""")
    expected = sorted([f"product@1:{p1}", f"order_line@0:{l1[0]}", f"order@1:{oid}", f"order@2:{oid}",
                       f"invoice_line@0:{ils[0][0]}", f"invoice@0:{iid}"])
    check("walking the recorded edges from the faulty price reaches exactly: its line, the order totals, "
          "the invoice line and the invoice - and NOT the correctly priced line 2", reach == expected,
          f"\n      got {reach}\n      expected {expected}")
    check("chain still verifies after the whole scenario", run("VERIFY_CHAIN", orgId=o1)[0].startswith("|"))

    print("== recovery: incident graph loading, incidents, immutable plans")
    faulty_event = psql(f"SELECT id FROM change_events WHERE organization_id = '{o1}' AND entity_id = '{p1}' AND entity_version = 1")[0]
    ev = run("FIND_CHANGE_EVENT", orgId=o1, id=faulty_event)[0].split("|")
    check("faulty change event loads with its changed field and before-value",
          ev[4] == "UPDATE" and ev[5] == '["unit_price"]' and '"unit_price": 5000' in ev[6], ev)
    check("change event lookup is tenant-scoped", run("FIND_CHANGE_EVENT", orgId=o2, id=faulty_event) == [])

    def edge_set(rows):
        return sorted((r.split("|")[0], r.split("|")[1], r.split("|")[2], r.split("|")[4], r.split("|")[5], r.split("|")[6]) for r in rows)
    loaded = run("LOAD_INCIDENT_EDGES", orgId=o1, entityType="product", entityId=p1, version=1)
    expected_edges = sorted([
        ("product", p1, "1", "order_line", l1[0], "0"),
        ("order_line", l1[0], "0", "order", oid, "1"),
        ("order_line", l1[0], "0", "order", oid, "2"),
        ("order_line", l2[0], "0", "order", oid, "2"),          # sibling summed into a reached order
        ("order_line", l1[0], "0", "invoice_line", ils[0][0], "0"),
        ("invoice_line", ils[0][0], "0", "invoice", iid, "0"),
        ("invoice_line", ils[1][0], "0", "invoice", iid, "0"),   # sibling summed into a reached invoice
    ])
    check("graph loader returns exactly the edges touching reached records (siblings included, T-100's pricing not)",
          edge_set(loaded) == expected_edges, f"\n      got {edge_set(loaded)}\n      exp {expected_edges}")
    versions = run("LOAD_INCIDENT_VERSIONS", orgId=o1, entityType="product", entityId=p1, version=1)
    entities = sorted({(r.split("|")[0], r.split("|")[1]) for r in versions})
    check("version loader returns full histories of reached records and siblings, not unrelated products",
          entities == sorted({("product", p1), ("order_line", l1[0]), ("order_line", l2[0]), ("order", oid),
                              ("invoice_line", ils[0][0]), ("invoice_line", ils[1][0]), ("invoice", iid)}), entities)
    check("product history comes back in version order with snapshots",
          [r.split("|")[2] for r in versions if r.split("|")[1] == p1] == ["0", "1"]
          and '"unit_price": 500' in [r for r in versions if r.split("|")[1] == p1][1])
    check("loading another tenant's graph returns nothing",
          run("LOAD_INCIDENT_EDGES", orgId=o2, entityType="product", entityId=p1, version=1) == [])

    # Graph Intelligence: required provenance that was never recorded, within the incident's scope.
    # This harness writes INSERT events with no changed fields; the application lists every field of an
    # INSERT (ChangeRecorder.recordInsert). Each scenario runs in a rolled-back transaction that first
    # makes the events look like the application's; psql reads the script from stdin so all output is kept.
    import subprocess as _sp

    def gaps_with(extra_sql=""):
        script = ("BEGIN;\nALTER TABLE provenance_edges DISABLE TRIGGER USER;\nALTER TABLE change_events DISABLE TRIGGER USER;\n"
                  f"UPDATE change_events SET changed_fields = ARRAY(SELECT jsonb_object_keys(after_state)) "
                  f"WHERE organization_id = '{o1}' AND operation = 'INSERT';\n" + extra_sql
                  + bind("LOAD_INCIDENT_GAPS", dict(orgId=o1, entityType="product", entityId=p1, version=1)) + ";\nROLLBACK;\n")
        out = _sp.run(["psql", "-X", "-At", "-F", "|", "-v", "ON_ERROR_STOP=1", "-d", DB, "-f", "-"], input=script,
                      capture_output=True, text=True)
        if out.returncode != 0:
            raise RuntimeError(out.stderr)
        return [g for g in out.stdout.splitlines() if g.count("|") >= 4]

    clean = gaps_with()
    check("no gaps when every required input in scope was recorded (an empty order's zero total needs none)",
          clean == [], clean)
    gap = gaps_with(f"DELETE FROM provenance_edges WHERE organization_id = '{o1}' AND target_entity_id = '{l2[0]}' "
                    "AND target_field = 'unit_price';\n")
    check("a sibling whose price has no recorded input is reported as a gap (field, version, rule)",
          len(gap) == 1 and gap[0].split("|")[1] == l2[0] and gap[0].split("|")[2:4] == ["0", "unit_price"], gap)
    check("gaps are tenant-scoped",
          run("LOAD_INCIDENT_GAPS", orgId=o2, entityType="product", entityId=p1, version=1) == [])

    check("incident numbering", run("NEXT_DOCUMENT_NUMBER", orgId=o1, kind="INCIDENT") == ["1"])
    inc = run("INSERT_INCIDENT", orgId=o1, incidentNumber="REC-000001", changeEventId=faulty_event,
              title="Mistyped price (test)", reason="P100 entered as 500 instead of 5000", openedBy=u1)[0].split("|")
    inc_id = inc[0]
    check("incident opens as OPEN v0", inc[5] == "OPEN" and inc[-1] == "0", inc)
    ok, _ = psql(bind("INSERT_INCIDENT", dict(orgId=o1, incidentNumber="REC-000002", changeEventId=faulty_event,
                                              title="dup", reason="dup", openedBy=u1)), expect_error="ux_incidents_active_per_change")
    check("a second open incident for the same change is refused", ok)
    check("incident lookups are tenant-scoped", len(run("FIND_INCIDENT", orgId=o1, id=inc_id)) == 1
          and run("FIND_INCIDENT_FOR_UPDATE", orgId=o2, id=inc_id) == []
          and len(run("FIND_INCIDENT_FOR_UPDATE", orgId=o1, id=inc_id)) == 1)
    check("incident list and count", len(run("LIST_INCIDENTS", orgId=o1, limit=10, offset=0)) == 1
          and run("COUNT_INCIDENTS", orgId=o1) == ["1"] and run("COUNT_INCIDENTS", orgId=o2) == ["0"])
    check("first plan number is 1", run("NEXT_PLAN_NUMBER", orgId=o1, incidentId=inc_id) == ["1"])
    fp = "sha256:" + "c" * 64
    plan1 = run("INSERT_PLAN", orgId=o1, incidentId=inc_id, planNumber=1, fingerprint=fp, risk="HIGH",
                requiresApproval=True, stepCount=5, manualStepCount=1, impact='{"counts":{}}',
                plan='{"steps":[]}', createdBy=u1)[0].split("|")
    check("plan stored as PROPOSED with its fingerprint", plan1[8] == "PROPOSED" and plan1[3] == fp, plan1)
    check("incident status moves to PLANNED (v1)", run("SET_INCIDENT_STATUS", orgId=o1, id=inc_id, status="PLANNED") == ["1"])
    check("regenerating supersedes the proposed plan",
          rows_affected(run("SUPERSEDE_PROPOSED_PLANS", orgId=o1, incidentId=inc_id)) == 1
          and run("NEXT_PLAN_NUMBER", orgId=o1, incidentId=inc_id) == ["2"])
    run("INSERT_PLAN", orgId=o1, incidentId=inc_id, planNumber=2, fingerprint=fp, risk="LOW", requiresApproval=False,
        stepCount=3, manualStepCount=0, impact="{}", plan="{}", createdBy=u1)
    listed = [r.split("|")[2] + ":" + r.split("|")[8] for r in run("LIST_PLANS_FOR_INCIDENT", orgId=o1, incidentId=inc_id)]
    check("plan history newest first: 2 PROPOSED, 1 SUPERSEDED", listed == ["2:PROPOSED", "1:SUPERSEDED"], listed)
    check("plan lookup is tenant-scoped", len(run("FIND_PLAN", orgId=o1, id=plan1[0])) == 1
          and run("FIND_PLAN", orgId=o2, id=plan1[0]) == [])

    print("== recovery execution: approval, step writes, actions, verification (V7)")
    plan2 = psql(f"SELECT id FROM recovery_plans WHERE incident_id = '{inc_id}' AND plan_number = 2")[0]
    lp = run("LOCK_PLAN", orgId=o1, id=plan2)[0].split("|")
    check("locking a plan returns its incident's origin record and faulty change",
          lp[3] == "PROPOSED" and lp[5] == "product" and lp[6] == p1 and lp[7] == faulty_event, lp)
    check("plan lock is tenant-scoped", run("LOCK_PLAN", orgId=o2, id=plan2) == [])
    check("the per-step lock reads only the status", run("LOCK_PLAN_STATUS", orgId=o1, id=plan2) == ["PROPOSED"]
          and run("LOCK_PLAN_STATUS", orgId=o2, id=plan2) == [])
    ok, err = psql(bind("INSERT_DECISION", dict(orgId=o1, planId=plan2, decision="APPROVED", fingerprint=fp,
                                                comment=None, decidedBy=u1)), expect_error="cannot approve it")
    check("the plan's author cannot approve it (database rule)", ok, err)
    ok, err = psql(bind("INSERT_DECISION", dict(orgId=o1, planId=plan2, decision="APPROVED",
                                                fingerprint="sha256:" + "d" * 64, comment=None, decidedBy=u2)),
                   expect_error="decision names fingerprint")
    check("an approval for a different fingerprint is refused", ok, err)
    run("INSERT_DECISION", orgId=o1, planId=plan2, decision="APPROVED", fingerprint=fp, comment="checked", decidedBy=u2)
    dec = run("LIST_DECISIONS", orgId=o1, planId=plan2)
    check("decision listed with the approver's name", len(dec) == 1 and dec[0].split("|")[5] == "Sales (test)", dec)
    check("status compare-and-set: PROPOSED -> APPROVED once",
          rows_affected(run("SET_PLAN_STATUS", orgId=o1, id=plan2, **{"from": "PROPOSED", "to": "APPROVED"})) == 1
          and rows_affected(run("SET_PLAN_STATUS", orgId=o1, id=plan2, **{"from": "PROPOSED", "to": "APPROVED"})) == 0)
    check("no execution in progress yet", run("ANY_PLAN_EXECUTING", orgId=o1, incidentId=inc_id) == ["f"])
    run("SET_PLAN_STATUS", orgId=o1, id=plan2, **{"from": "APPROVED", "to": "EXECUTING"})
    check("execution in progress is visible", run("ANY_PLAN_EXECUTING", orgId=o1, incidentId=inc_id) == ["t"])

    txn = str(uuid.uuid4())
    check("record lock finds the product in its tenant only",
          run("LOCK_RECORD", fmt="products", orgId=o1, id=p1) == [p1]
          and run("LOCK_RECORD", fmt="products", orgId=o2, id=p1) == [])
    st = run("LATEST_STATE", orgId=o1, entityType="product", entityId=p1)[0].split("|")
    check("latest recorded state of the product is v1 with the faulty price", st[0] == "1" and '"unit_price": 500' in st[1], st)
    check("product restore writes v2", run("RECOVER_PRODUCT", orgId=o1, id=p1, name="Widget", unitPrice=5000,
                                            unitCost=3000, active=True) == ["2"])
    run("INSERT_CHANGE_EVENT", orgId=o1, txnId=txn, actorKind="RECOVERY", actorUserId=u2, entityType="product",
        entityId=p1, version=2, operation="UPDATE", changedFields='["unit_price"]', before='{"unit_price":500}',
        after='{"unit_price":5000}', reason="Recovery plan step 1", correlationId=None)
    row = run("CURRENT_ROW", fmt="products", orgId=o1, id=p1)[0]
    check("the stored row reads back keyed by column name", '"unit_price": 5000' in row and '"version": 2' in row, row)
    line = run("RECOVER_ORDER_LINE", orgId=o1, id=l1[0], unitPrice=5000)[0].split("|")
    check("order line repriced: v1, generated total 15000", line == ["1", "15000.0000"], line)
    check("an order total that does not equal its lines is not written",
          run("RECOVER_ORDER_TOTAL", orgId=o1, id=oid, total=999) == [])
    check("the order total equal to its lines is written (v4)",
          run("RECOVER_ORDER_TOTAL", orgId=o1, id=oid, total=16800) == ["4"]
          and run("SUM_ORDER_LINES", orgId=o1, id=oid) == ["16800.0000"])
    ok, err = psql(bind("RECOVER_INVOICE_LINE", dict(orgId=o1, id=ils[0][0], unitPrice=5000)),
                   expect_error="cannot be changed")
    check("a line of an ISSUED invoice cannot be rewritten by recovery", ok, err)

    o3 = run("INSERT_ORDER", orgId=o1, orderNumber="SO-000003", customerId=c1, createdBy=u1)[0].split("|")[0]
    l3 = run("INSERT_ORDER_LINE", orgId=o1, orderId=o3, productId=p1, description="P100 - Widget",
             quantity=2, unitPrice=500)[0].split("|")
    d3 = run("INSERT_INVOICE", orgId=o1, invoiceNumber="INV-000003", orderId=o3, totalAmount=1000)[0].split("|")[0]
    il3 = run("INSERT_INVOICE_LINE", orgId=o1, invoiceId=d3, lineNo=1, orderLineId=l3[0], description="P100 - Widget",
              quantity=2, unitPrice=500)[0].split("|")
    check("a DRAFT invoice line is repriced; its total is generated",
          run("RECOVER_INVOICE_LINE", orgId=o1, id=il3[0], unitPrice=5000) == ["10000.0000"])
    check("a DRAFT invoice total must equal its lines",
          run("RECOVER_INVOICE_TOTAL", orgId=o1, id=d3, total=1000) == []
          and run("RECOVER_INVOICE_TOTAL", orgId=o1, id=d3, total=10000) == ["1"]
          and run("SUM_INVOICE_LINES", orgId=o1, id=d3) == ["10000.0000"])

    cn2 = run("RECOVERY_CREDIT_NOTE", orgId=o1, creditNoteNumber="CN-000002", invoiceId=iid, amount=200,
              reason="Recovery plan step 4")
    check("recovery credits an ISSUED invoice", len(cn2) == 1 and cn2[0].split("|")[5] == "ISSUED", cn2)
    cn2id = cn2[0].split("|")[0]
    check("a credit beyond what remains on the invoice is refused (3300 - 300 credited)",
          run("RECOVERY_CREDIT_NOTE", orgId=o1, creditNoteNumber="CN-000003", invoiceId=iid, amount=3001,
              reason="too much") == [])
    check("a zero credit is refused",
          run("RECOVERY_CREDIT_NOTE", orgId=o1, creditNoteNumber="CN-000003", invoiceId=iid, amount=0, reason="x") == [])
    check("a DRAFT invoice is not credited",
          run("RECOVERY_CREDIT_NOTE", orgId=o1, creditNoteNumber="CN-000003", invoiceId=d3, amount=1, reason="x") == [])
    check("credit note amount reads back", run("CREDIT_NOTE_AMOUNT", orgId=o1, id=cn2id) == ["200.0000"]
          and run("CREDIT_NOTE_AMOUNT", orgId=o2, id=cn2id) == [])

    def action(step, act, etype, eid, status, rv=None, rid=None, plan=plan2, t=txn):
        return run("INSERT_ACTION", orgId=o1, planId=plan, stepNo=step, action=act, entityType=etype, entityId=eid,
                   expectedVersion=1, status=status, detail="test", resultVersion=rv, resultEntityId=rid,
                   txnId=t, executedBy=u2)
    action(1, "RESTORE_FIELDS", "product", p1, "DONE", rv=2)
    ok, err = psql(bind("INSERT_ACTION", dict(orgId=o1, planId=plan2, stepNo=1, action="RESTORE_FIELDS",
                                              entityType="product", entityId=p1, expectedVersion=1, status="DONE",
                                              detail="again", resultVersion=3, resultEntityId=None, txnId=txn,
                                              executedBy=u2)), expect_error="duplicate key")
    check("a step's outcome is recorded once", ok, err)
    ok, err = psql(bind("INSERT_ACTION", dict(orgId=o1, planId=plan1[0], stepNo=1, action="RESTORE_FIELDS",
                                              entityType="product", entityId=p1, expectedVersion=1, status="DONE",
                                              detail="x", resultVersion=2, resultEntityId=None, txnId=txn,
                                              executedBy=u2)), expect_error="only while plan")
    check("steps of a plan that is not executing are refused", ok, err)
    action(2, "MANUAL_REVIEW", "order_line", l3[0], "AWAITING_ACKNOWLEDGEMENT", t=str(uuid.uuid4()))
    action(3, "ISSUE_CREDIT_NOTE", "invoice", iid, "DONE", rid=cn2id, t=str(uuid.uuid4()))
    acts = run("LIST_ACTIONS", orgId=o1, planId=plan2)
    check("actions listed in step order", [a.split("|")[0] for a in acts] == ["1", "2", "3"], acts)
    check("a manual step is acknowledged once",
          rows_affected(run("ACKNOWLEDGE_ACTION", orgId=o1, planId=plan2, stepNo=2, userId=u1, note="kept as is")) == 1
          and rows_affected(run("ACKNOWLEDGE_ACTION", orgId=o1, planId=plan2, stepNo=2, userId=u1, note="again")) == 0
          and rows_affected(run("ACKNOWLEDGE_ACTION", orgId=o1, planId=plan2, stepNo=1, userId=u1, note="done step")) == 0)
    check("credits issued by this incident's executions, per invoice (the earlier manual credit is not counted)",
          run("CREDITED_BY_INCIDENT", orgId=o1, incidentId=inc_id) == [f"{iid}|200.0000"]
          and run("CREDITED_BY_INCIDENT", orgId=o2, incidentId=inc_id) == [])
    written = run("WRITTEN_BY_PLAN", orgId=o1, planId=plan2)
    check("writes attributed to the plan: RECOVERY events in its step transactions only",
          written == [f"product|{p1}"], written)
    run("SET_PLAN_STATUS", orgId=o1, id=plan2, **{"from": "EXECUTING", "to": "EXECUTED"})
    run("INSERT_VERIFICATION", orgId=o1, planId=plan2, outcome="FAILED", checkCount=4, failedCount=1, pendingManual=0,
        checks='[{"kind":"SCOPE","passed":false}]', verifiedBy=u1)
    run("INSERT_VERIFICATION", orgId=o1, planId=plan2, outcome="PASSED", checkCount=4, failedCount=0, pendingManual=0,
        checks='[]', verifiedBy=u1)
    ver = run("LIST_VERIFICATIONS", orgId=o1, planId=plan2)
    check("verifications are kept, newest first", [v.split("|")[1] for v in ver] == ["PASSED", "FAILED"], ver)
    ok, err = psql(bind("INSERT_VERIFICATION", dict(orgId=o1, planId=plan2, outcome="PASSED", checkCount=4,
                                                    failedCount=1, pendingManual=0, checks="[]", verifiedBy=u1)),
                   expect_error="recovery_verifications")
    check("a PASSED verification with a failed check cannot be stored", ok, err)
    check("chain verifies after recovery writes", run("VERIFY_CHAIN", orgId=o1)[0].startswith("|"))

    print("== audit anchoring: a superuser rewrite passes verify_audit_chain but not the anchors (V9)")
    ZERO = "0" * 64
    n0 = int(run("CHAIN_HEAD", orgId=o2)[0].split("|")[0])   # events this tenant already has
    A, F, C, D, H = n0 + 4, n0 + 2, n0 + 3, n0 + 5, n0 + 6   # anchor, forged, careless edit, deleted, head
    for i in range(6):
        ce("product", str(uuid.uuid4()), 0, "INSERT", f'{{"name": "item {i}"}}', org=o2)
    head = run("CHAIN_HEAD", orgId=o2)[0].split("|")
    check("chain head of the second tenant", head[0] == str(H), head)
    # Anchor at index A: what the application signs and stores outside the database.
    h4 = psql(f"SELECT encode(hash, 'hex') FROM change_events WHERE organization_id = '{o2}' AND chain_index = {A}")[0]
    run("INSERT_ANCHOR", orgId=o2, chainIndex=A, hash=h4, keyId="test-key", signature="c2lnbmF0dXJl",
        sink="file:/anchors", anchoredAt="2026-11-06T10:00:00Z")
    run("INSERT_ANCHOR", orgId=o2, chainIndex=A, hash=h4, keyId="test-key", signature="c2lnbmF0dXJl",
        sink="file:/anchors", anchoredAt="2026-11-06T10:00:00Z")
    check("an anchor is recorded once per index", run("LATEST_ANCHOR_INDEX", orgId=o2) == [str(A)])
    ok, err = psql("UPDATE audit_anchors SET key_id = 'other'", expect_error="append-only")
    check("the local anchor log is append-only", ok, err)
    check("organizations with a chain are listed for anchoring", o2 in run("ORGANIZATIONS_WITH_CHAINS"))
    r = run("RECOMPUTE_CHAIN", orgId=o2, after=0, prevHash=ZERO, upto=A)[0].split("|")
    check("untouched: recomputing from content reproduces the anchored hash", r == [str(A), h4, ""], r)
    r = run("RECOMPUTE_CHAIN", orgId=o2, after=A, prevHash=h4, upto=H)[0].split("|")
    check("untouched: the chain after the anchor continues from it", r[0] == str(H) and r[2] == "" and r[1] == head[1], r)

    # The attack: a superuser forges event F and recomputes every later hash and the head.
    psql(f"""BEGIN;
        SET LOCAL session_replication_role = replica;  -- superuser: skips triggers and FK checks
        UPDATE change_events SET after_state = '{{"name": "forged"}}' WHERE organization_id = '{o2}' AND chain_index = {F};
        DO $$ DECLARE e change_events; v_prev bytea; BEGIN
          SELECT hash INTO v_prev FROM change_events WHERE organization_id = '{o2}' AND chain_index = {F - 1};
          FOR e IN SELECT * FROM change_events WHERE organization_id = '{o2}' AND chain_index >= {F} ORDER BY chain_index LOOP
            UPDATE change_events SET prev_hash = v_prev, hash = sha256(v_prev || change_event_payload(e)) WHERE id = e.id;
            v_prev := sha256(v_prev || change_event_payload(e));
          END LOOP;
          UPDATE audit_chain_heads SET last_hash = v_prev WHERE organization_id = '{o2}';
        END $$;
        COMMIT;""")
    check("the forged event is in place", psql(
        f"SELECT after_state->>'name' FROM change_events WHERE organization_id = '{o2}' AND chain_index = {F}") == ["forged"])
    check("verify_audit_chain is fooled by a consistent rewrite (the gap anchoring closes)",
          run("VERIFY_CHAIN", orgId=o2)[0].startswith("|"))
    r = run("RECOMPUTE_CHAIN", orgId=o2, after=0, prevHash=ZERO, upto=A)[0].split("|")
    check("the anchor exposes it: content before the anchor no longer yields the signed hash",
          r[0] == str(A) and r[1] != h4 and r[2] == "", r)
    r = run("RECOMPUTE_CHAIN", orgId=o2, after=A, prevHash=h4, upto=H)[0].split("|")
    check("and the stored chain no longer continues from the signed hash", r[2] == str(A + 1), r)

    # A careless edit without rehashing is caught by the existing check and located by recomputation.
    psql(f"""BEGIN; SET LOCAL session_replication_role = replica;  -- superuser: skips triggers and FK checks
        UPDATE change_events SET reason = 'edited' WHERE organization_id = '{o2}' AND chain_index = {C}; COMMIT;""")
    check("an edit without rehashing is caught by verify_audit_chain", run("VERIFY_CHAIN", orgId=o2)[0].startswith(f"{C}|"))
    r = run("RECOMPUTE_CHAIN", orgId=o2, after=0, prevHash=ZERO, upto=H)[0].split("|")
    check("recomputation locates the first stored hash that disagrees", r[2] == str(C), r)
    # A deleted event shows up as a short segment.
    psql(f"""BEGIN; SET LOCAL session_replication_role = replica;  -- superuser: skips triggers and FK checks
        DELETE FROM change_events WHERE organization_id = '{o2}' AND chain_index = {D}; COMMIT;""")
    r = run("RECOMPUTE_CHAIN", orgId=o2, after=A, prevHash=h4, upto=H)[0].split("|")
    check("a deleted event stops the segment early", r[0] == str(D - 1), r)

    # Last, because provenance is append-only: a cyclic edge must not make the loader run forever.
    edge(("order", oid, 2), "total_amount", ("product", p1, 1), "unit_price", "DERIVED_VALUE")
    timed = psql("SET statement_timeout = '5s'; " + bind("LOAD_INCIDENT_EDGES",
                 dict(orgId=o1, entityType="product", entityId=p1, version=1)))
    timed = [r for r in timed if r != "SET"]   # psql echoes the SET command tag
    check("recursive graph loading terminates on a cyclic graph", len(timed) == len(expected_edges) + 1, len(timed))

    # Statements of the ATLAS X modules are exercised by scripts/verify-atlasx-sql.py.
    sys.path.insert(0, str(ROOT / "scripts"))
    from sqlharness import atlasx_statement_names, graph_statement_names  # noqa: E402
    unexercised = sorted(set(SQL) - USED - atlasx_statement_names() - graph_statement_names())
    check("every SQL statement in the Java source was exercised", not unexercised, unexercised)
finally:
    subprocess.run(["psql", "-X", "-q", "-d", "postgres", "-c", f"DROP DATABASE IF EXISTS {DB}"])

print(f"== {passes} passed, {failures} failed")
sys.exit(1 if failures else 0)
