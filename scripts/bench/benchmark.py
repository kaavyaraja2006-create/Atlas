#!/usr/bin/env python3
"""
Phase 7 benchmark: PASCAR against baselines on PostgreSQL 16, on SYNTHETIC
workloads (the Phase 5 generator, fixed seeds).

Per run (orders, seed, scenario):
  1. Export the generated history (BenchmarkExport) and load it into a freshly
     migrated database: business rows, change events in write order (the
     database computes the hash chain), provenance edges.
  2. Baselines, measured in the database:
       NAIVE_FK          every line of the faulty product, their orders, invoices, invoice lines
       RECORD_LEVEL      dependency closure at record granularity from the faulty version
                         (what record-level / transaction-level undo tracks; ignores fields)
       FULL_RESTORE      records whose latest write is at or after the fault (lost on a
                         point-in-time restore) + wall time of pg_dump/pg_restore of the database
  3. PASCAR: the real loader SQL (from RecoveryRepository) run in one REPEATABLE READ
     transaction, its output fed to the real engine (DbGraphRun); scored against the
     oracle's ground truth, which never reads provenance.
  4. Execution: the plan is stored, approved by a second user and executed one
     transaction per step with the SQL statements of ExecutionRepository /
     ChangeRecorder / ProvenanceRecorder. THE ORCHESTRATION IS A PYTHON REPLAY of
     JdbcRecoveryPorts + RecoveryWorkflow (which cannot run here: no Spring build);
     the SQL is the application's own.
  5. Verification in the database: changed fields, every total in the database equals
     its lines, credits, scope of writes, the audit chain, and a fresh load + analysis
     + plan of the repaired data (must have no automatic step).
  6. Contention, on a pg_restore'd copy of the pre-execution database: after the
     first step commits, a person edits K lines the plan has not reached yet. Measures:
     conflicts detected, updates lost, steps blocked, whether the fingerprint went stale.

Output: one JSON line per run (raw), printed and appended to --out.

Usage: PGHOST=... PGPORT=... PGUSER=... scripts/bench/benchmark.py --orders 1000 --seed 1 \
           --scenario LIVE --out docs/experiments/raw/phase7-benchmark.jsonl
"""
import argparse
import csv
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import uuid
from decimal import Decimal
from pathlib import Path

import psycopg

ROOT = Path(__file__).resolve().parents[2]
JAVA_SRC = ROOT / "backend/src/main/java"
MIGRATIONS = ROOT / "backend/src/main/resources/db/migration"
ORG = "0b000000-0000-4000-8000-000000000001"
AUTHOR = "0c000000-0000-4000-8000-000000000001"
APPROVER = "0c000000-0000-4000-8000-000000000002"
EXECUTOR = "0c000000-0000-4000-8000-000000000003"
PERSON = "0c000000-0000-4000-8000-000000000004"
CUSTOMER = "00000000-0000-0000-0000-000000000001"


# ------------------------------------------------------------------ SQL from the Java source
def extract():
    found = {}
    pat = re.compile(r'static final String (\w+) = """\n(.*?)""";', re.S)
    for p in JAVA_SRC.rglob("*.java"):
        for name, body in pat.findall(p.read_text()):
            found[name] = body.replace("\\\\", "\\")
    return found


SQL = extract()
PARAM = re.compile(r"(?<![:\w]):([A-Za-z]\w*)")


def q(name, fmt=None):
    """A Java statement with :name placeholders turned into psycopg %(name)s."""
    s = SQL[name]
    if fmt is not None:
        s = s % fmt
    return PARAM.sub(lambda m: "%(" + m.group(1) + ")s", s.replace("%", "%%"))


def literal(v):
    if v is None:
        return "NULL"
    if isinstance(v, (int, Decimal)):
        return str(v)
    return "'" + str(v).replace("'", "''") + "'"


def inline(name, **params):
    """For COPY (...) TO STDOUT, which takes no bind parameters."""
    return PARAM.sub(lambda m: literal(params[m.group(1)]), SQL[name])


def dec_json(text):
    return json.loads(text, parse_float=Decimal) if text else None


def _has_dec(v):
    if isinstance(v, Decimal):
        return True
    if isinstance(v, dict):
        return any(_has_dec(x) for x in v.values())
    if isinstance(v, list):
        return any(_has_dec(x) for x in v)
    return False


def _exact(v):
    if isinstance(v, Decimal):
        return format(v, "f")
    if isinstance(v, dict):
        return "{" + ",".join(json.dumps(k) + ":" + _exact(x) for k, x in v.items()) + "}"
    if isinstance(v, list):
        return "[" + ",".join(_exact(x) for x in v) + "]"
    return json.dumps(v)


def same(a, b):
    if isinstance(a, (int, float, Decimal)) and isinstance(b, (int, float, Decimal)) and not isinstance(a, bool):
        return Decimal(str(a)) == Decimal(str(b))
    return a == b


# ------------------------------------------------------------------ helpers
def sh(*args, **kw):
    return subprocess.run(args, check=True, capture_output=True, text=True, **kw)


def connect(db, **kw):
    return psycopg.connect(dbname=db, **kw)


def ms(t):
    return round((time.perf_counter() - t) * 1000, 1)


def create_db(name):
    with connect("postgres", autocommit=True) as c:
        c.execute(f"DROP DATABASE IF EXISTS {name}")
        c.execute(f"CREATE DATABASE {name}")
    for f in sorted(MIGRATIONS.glob("V*__*.sql"), key=lambda p: int(p.name[1:].split("__")[0])):
        sh("psql", "-X", "-q", "-v", "ON_ERROR_STOP=1", "-d", name, "--single-transaction", "-f", str(f))


def drop_db(name):
    with connect("postgres", autocommit=True) as c:
        c.execute(f"DROP DATABASE IF EXISTS {name}")


STAGE = {
    "products": "id uuid, sku text, name text, unit_price numeric, unit_cost numeric, active boolean, version bigint",
    "orders": "id uuid, order_number text, customer_id uuid, status text, total_amount numeric, placed_at timestamptz, version bigint",
    "order_lines": "id uuid, order_id uuid, line_no int, product_id uuid, description text, quantity int, unit_price numeric, version bigint",
    "invoices": "id uuid, order_id uuid, invoice_number text, total_amount numeric, status text, issued_at timestamptz, version bigint",
    "invoice_lines": "id uuid, invoice_id uuid, line_no int, order_line_id uuid, description text, quantity int, unit_price numeric",
    "change_events": "txn_id uuid, actor_kind text, entity_type text, entity_id uuid, entity_version bigint, operation text, "
                     "changed_fields text[], before_state jsonb, after_state jsonb, occurred_at timestamptz",
    "provenance_edges": "st text, sid uuid, sv bigint, sf text, tt text, tid uuid, tv bigint, tf text, relation text, evidence text",
}


def load(db, data):
    with connect(db) as c:
        c.execute("INSERT INTO organizations (id, slug, name) VALUES (%s, 'bench', 'Benchmark (synthetic)')", (ORG,))
        for uid, who in ((AUTHOR, "author"), (APPROVER, "approver"), (EXECUTOR, "executor"), (PERSON, "person")):
            c.execute("INSERT INTO users (id, email, display_name, password_hash) VALUES (%s, %s, %s, '{bench}none')",
                      (uid, f"{who}@bench.test", who.title()))
        c.execute("INSERT INTO customers (id, organization_id, name) VALUES (%s, %s, 'Benchmark customer')", (CUSTOMER, ORG))
        for t, cols in STAGE.items():
            c.execute(f"CREATE TEMP TABLE s_{t} ({cols})")
            with c.cursor().copy(f"COPY s_{t} FROM STDIN WITH (FORMAT csv)") as cp:
                cp.write((data / f"{t}.csv").read_bytes())
        c.execute(f"""INSERT INTO products (id, organization_id, sku, name, unit_price, unit_cost, active, version)
                      SELECT id, '{ORG}', sku, name, unit_price, unit_cost, active, version FROM s_products""")
        c.execute(f"""INSERT INTO orders (id, organization_id, order_number, customer_id, status, total_amount, created_by,
                                          placed_at, version)
                      SELECT id, '{ORG}', order_number, customer_id, status, total_amount, '{AUTHOR}', placed_at, version
                        FROM s_orders""")
        c.execute(f"""INSERT INTO order_lines (id, organization_id, order_id, line_no, product_id, description, quantity,
                                               unit_price, version)
                      SELECT id, '{ORG}', order_id, line_no, product_id, description, quantity, unit_price, version
                        FROM s_order_lines""")
        # Lines can only be added to a DRAFT invoice (V2 trigger): insert drafts, lines, then the final status.
        c.execute(f"""INSERT INTO invoices (id, organization_id, invoice_number, order_id, total_amount, version)
                      SELECT id, '{ORG}', invoice_number, order_id, total_amount, version FROM s_invoices""")
        c.execute(f"""INSERT INTO invoice_lines (id, organization_id, invoice_id, line_no, order_line_id, description,
                                                 quantity, unit_price)
                      SELECT id, '{ORG}', invoice_id, line_no, order_line_id, description, quantity, unit_price
                        FROM s_invoice_lines""")
        c.execute("""UPDATE invoices i SET status = s.status, issued_at = s.issued_at
                       FROM s_invoices s WHERE s.id = i.id AND s.status <> 'DRAFT'""")
        # Committed in batches: appending to one chain head many thousand times inside a single transaction is
        # quadratic (each update leaves a dead version of the head row that later lookups walk). The application
        # never does that (one small transaction per command); a bulk import must batch, as here.
        c.execute("CREATE TEMP TABLE s_events_ordered AS SELECT row_number() OVER (ORDER BY occurred_at) AS rn, * "
                  "FROM s_change_events")
        total = c.execute("SELECT count(*) FROM s_events_ordered").fetchone()[0]
        c.commit()
        for lo in range(0, total, 2000):
            c.execute(f"""INSERT INTO change_events (organization_id, txn_id, actor_kind, actor_user_id, entity_type,
                                                     entity_id, entity_version, operation, changed_fields, before_state,
                                                     after_state, occurred_at)
                          SELECT '{ORG}', txn_id, actor_kind, CASE WHEN actor_kind = 'USER' THEN '{AUTHOR}'::uuid END,
                                 entity_type, entity_id, entity_version, operation, changed_fields, before_state,
                                 after_state, occurred_at
                            FROM s_events_ordered WHERE rn > {lo} AND rn <= {lo + 2000} ORDER BY rn""")
            c.commit()
        c.execute(f"""INSERT INTO provenance_edges (organization_id, txn_id, source_entity_type, source_entity_id,
                          source_entity_version, source_field, target_entity_type, target_entity_id,
                          target_entity_version, target_field, relation, evidence)
                      SELECT '{ORG}', gen_random_uuid(), st, sid, sv, sf, tt, tid, tv, tf, relation, evidence
                        FROM s_provenance_edges""")
    with connect(db, autocommit=True) as c:
        c.execute("VACUUM ANALYZE")


def read_set(path, cols=2):
    with open(path, newline="") as f:
        return {tuple(r[:cols]) for r in csv.reader(f)}


def score(selected, truth):
    tp = len(selected & truth)
    return {"selected": len(selected), "truePositives": tp,
            "precision": round(tp / len(selected), 4) if selected else 1.0,
            "recall": round(tp / len(truth), 4) if truth else 1.0,
            "unaffectedTouched": len(selected) - tp}


# ------------------------------------------------------------------ baselines
NAIVE_FK = """
WITH l AS (SELECT id, order_id FROM order_lines WHERE organization_id = %(org)s AND product_id = %(p)s)
SELECT 'product', %(p)s::text
UNION SELECT 'order_line', id::text FROM l
UNION SELECT 'order', order_id::text FROM l
UNION SELECT 'invoice', i.id::text FROM invoices i JOIN l ON i.order_id = l.order_id WHERE i.organization_id = %(org)s
UNION SELECT 'invoice_line', il.id::text FROM invoice_lines il JOIN l ON il.order_line_id = l.id
       WHERE il.organization_id = %(org)s
"""

RECORD_LEVEL = """
WITH RECURSIVE reach(t, id, v) AS (
    SELECT 'product'::varchar(64), %(p)s::uuid, %(v)s::bigint
  UNION
    SELECT e.target_entity_type, e.target_entity_id, e.target_entity_version
      FROM provenance_edges e JOIN reach r
        ON e.source_entity_type = r.t AND e.source_entity_id = r.id AND e.source_entity_version >= r.v
     WHERE e.organization_id = %(org)s)
SELECT DISTINCT t, id::text FROM reach
"""

FULL_RESTORE = """
SELECT DISTINCT entity_type, entity_id::text FROM change_events
 WHERE organization_id = %(org)s AND chain_index >= %(idx)s
   AND entity_type IN ('product', 'order', 'order_line', 'invoice', 'invoice_line', 'credit_note')
"""

TOTAL_VIOLATIONS = """
SELECT
 (SELECT count(*) FROM orders o WHERE o.organization_id = %(org)s AND o.total_amount <>
     (SELECT COALESCE(sum(line_total), 0) FROM order_lines l WHERE l.order_id = o.id)),
 (SELECT count(*) FROM invoices i WHERE i.organization_id = %(org)s AND i.status = 'DRAFT' AND i.total_amount <>
     (SELECT COALESCE(sum(line_total), 0) FROM invoice_lines l WHERE l.invoice_id = i.id)),
 (SELECT count(*) FROM invoices i WHERE i.organization_id = %(org)s AND i.total_amount <
     (SELECT COALESCE(sum(amount), 0) FROM credit_notes n WHERE n.invoice_id = i.id))
"""


# ------------------------------------------------------------------ PASCAR via the database
def pascar_load(db, fault_event, out_dir, incident=None, truth_dir=None):
    """Runs the real loader SQL in one REPEATABLE READ snapshot, dumps it as CSV, runs the real engine."""
    out_dir.mkdir(parents=True, exist_ok=True)
    timings = {}
    with connect(db) as c:
        c.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ")
        ev = c.execute(q("FIND_CHANGE_EVENT"), {"orgId": ORG, "id": fault_event}).fetchone()
        _, etype, eid, ver, _, changed, before, after = ev
        with open(out_dir / "fault.csv", "w", newline="") as f:
            csv.writer(f).writerow([etype, eid, ver, changed, before, after])
        p = dict(orgId=ORG, entityType=etype, entityId=str(eid), version=ver)
        for name, file in (("LOAD_INCIDENT_EDGES", "edges.csv"), ("LOAD_INCIDENT_VERSIONS", "versions.csv")):
            t = time.perf_counter()
            with open(out_dir / file, "wb") as f, c.cursor().copy(f"COPY ({inline(name, **p)}) TO STDOUT WITH (FORMAT csv)") as cp:
                for chunk in cp:
                    f.write(chunk)
            timings[name] = ms(t)
        if incident:
            with open(out_dir / "credited.csv", "w", newline="") as f:
                w = csv.writer(f)
                for inv, amount in c.execute(q("CREDITED_BY_INCIDENT"), {"orgId": ORG, "incidentId": incident}):
                    w.writerow([inv, amount])
    if truth_dir:
        for name in ("truth.csv", "truth_totals.csv"):
            (out_dir / name).write_bytes((truth_dir / name).read_bytes())
    t = time.perf_counter()
    r = subprocess.run(["java", "-Xss512k", "-Xmx3g", "-cp", JAVA_CP, "com.atlas.recovery.engine.DbGraphRun", str(out_dir)],
                       check=True, capture_output=True, text=True)
    timings["engineProcess"] = ms(t)
    result = json.loads((out_dir / "result.json").read_text())
    result["dbLoadMs"] = timings
    return result


# ------------------------------------------------------------------ execution (Python replay of the workflow)
WRITABLE = {"product": {"name", "unit_price", "unit_cost", "active"}, "order_line": {"unit_price", "line_total"},
            "order": {"total_amount"}, "invoice_line": {"unit_price", "line_total"}, "invoice": {"total_amount"}}


class Rejected(Exception):
    pass


def store_plan(c, fault_event, plan_json, plan):
    n = c.execute(q("NEXT_DOCUMENT_NUMBER"), {"orgId": ORG, "kind": "INCIDENT"}).fetchone()[0]
    inc = c.execute(q("INSERT_INCIDENT"), {"orgId": ORG, "incidentNumber": f"REC-{n:06d}", "changeEventId": fault_event,
                                           "title": "Benchmark fault", "reason": "price typed at a tenth (synthetic)",
                                           "openedBy": AUTHOR}).fetchone()[0]
    manual = sum(1 for s in plan["steps"] if s["action"] in ("MANUAL_REVIEW", "SUPPLEMENTARY_CHARGE"))
    pid = c.execute(q("INSERT_PLAN"), {"orgId": ORG, "incidentId": inc, "planNumber": 1, "fingerprint": plan["fingerprint"],
                                       "risk": plan["risk"], "requiresApproval": plan["requiresApproval"],
                                       "stepCount": len(plan["steps"]), "manualStepCount": manual, "impact": "{}",
                                       "plan": plan_json, "createdBy": AUTHOR}).fetchone()[0]
    c.execute(q("INSERT_DECISION"), {"orgId": ORG, "planId": pid, "decision": "APPROVED", "fingerprint": plan["fingerprint"],
                                     "comment": "benchmark", "decidedBy": APPROVER})
    c.execute(q("SET_PLAN_STATUS"), {"orgId": ORG, "id": pid, "from": "PROPOSED", "to": "APPROVED"})
    return inc, pid


def latest(c, etype, eid):
    r = c.execute(q("LATEST_STATE"), {"orgId": ORG, "entityType": etype, "entityId": eid}).fetchone()
    return (r[0], dec_json(r[1])) if r else (None, None)


def change_event(c, txn, etype, eid, version, op, before, after, reason):
    changed = sorted(k for k in after if before is None or k not in before or not same(before[k], after[k]))
    c.execute(q("INSERT_CHANGE_EVENT"), {"orgId": ORG, "txnId": txn, "actorKind": "RECOVERY", "actorUserId": EXECUTOR,
                                         "entityType": etype, "entityId": eid, "version": version, "operation": op,
                                         "changedFields": json.dumps(changed), "before": None if before is None else _exact(before),
                                         "after": _exact(after), "reason": reason, "correlationId": None})


def apply_fields(c, txn, step, reason):
    k = step["key"]
    etype, eid = k["entityType"], k["entityId"]
    fields = {ch["field"]: ch["proposed"] for ch in step["changes"]}
    if not set(fields) <= WRITABLE.get(etype, set()):
        raise Rejected(f"recovery may not change {sorted(fields)} of a {etype}")
    v, before = latest(c, etype, eid)
    after = dict(before)
    after.update(fields)
    nxt = v + 1
    if etype == "product":
        r = c.execute(q("RECOVER_PRODUCT"), {"orgId": ORG, "id": eid, "name": after["name"], "unitPrice": after["unit_price"],
                                             "unitCost": after["unit_cost"], "active": after["active"]}).fetchone()
        rv = r[0]
    elif etype == "order_line":
        rv, lt = c.execute(q("RECOVER_ORDER_LINE"), {"orgId": ORG, "id": eid, "unitPrice": after["unit_price"]}).fetchone()
        if "line_total" in fields and not same(lt, fields["line_total"]):
            raise Rejected("line total mismatch")
        after["line_total"] = lt
    elif etype == "order":
        r = c.execute(q("RECOVER_ORDER_TOTAL"), {"orgId": ORG, "id": eid, "total": after["total_amount"]}).fetchone()
        if r is None:
            raise Rejected("total does not equal the sum of the order's lines")
        rv = r[0]
    elif etype == "invoice_line":
        lt = c.execute(q("RECOVER_INVOICE_LINE"), {"orgId": ORG, "id": eid, "unitPrice": after["unit_price"]}).fetchone()[0]
        if "line_total" in fields and not same(lt, fields["line_total"]):
            raise Rejected("line total mismatch")
        after["line_total"] = lt
        rv = nxt
    elif etype == "invoice":
        r = c.execute(q("RECOVER_INVOICE_TOTAL"), {"orgId": ORG, "id": eid, "total": after["total_amount"]}).fetchone()
        if r is None:
            raise Rejected("total does not equal the sum of the invoice's lines")
        rv = r[0]
    else:
        raise Rejected(f"no repair rule for {etype}")
    if rv != nxt:
        raise Rejected(f"row version {rv} out of step with history ({nxt})")
    change_event(c, txn, etype, eid, nxt, "UPDATE", before, after, reason)
    for i in step["inputs"]:
        sv, _ = latest(c, i["source"]["entityType"], i["source"]["entityId"])
        c.execute(q("INSERT_PROVENANCE_EDGE"), {"orgId": ORG, "txnId": txn, "sourceType": i["source"]["entityType"],
                                                "sourceId": i["source"]["entityId"], "sourceVersion": sv,
                                                "sourceField": i["sourceField"], "targetType": etype, "targetId": eid,
                                                "targetVersion": nxt, "targetField": i["targetField"],
                                                "relation": i["relation"], "operation": "recovery.update_fields"})
    return nxt


def credit(c, txn, step, reason):
    n = c.execute(q("NEXT_DOCUMENT_NUMBER"), {"orgId": ORG, "kind": "CREDIT_NOTE"}).fetchone()[0]
    r = c.execute(q("RECOVERY_CREDIT_NOTE"), {"orgId": ORG, "creditNoteNumber": f"CN-{n:06d}",
                                              "invoiceId": step["key"]["entityId"], "amount": step["amount"],
                                              "reason": reason}).fetchone()
    if r is None:
        raise Rejected("invoice not ISSUED or credit exceeds what remains")
    cid, inv, number, amount, rsn, status, issued, _, _ = r
    change_event(c, txn, "credit_note", str(cid), 0, "INSERT", None,
                 {"invoice_id": str(inv), "credit_note_number": number, "amount": amount, "reason": rsn,
                  "status": status, "issued_at": issued.isoformat()}, reason)
    return cid


def execute(db, pid, plan, after_step=None):
    """One transaction per step, as RecoveryWorkflow does. after_step(n) is called after step n commits."""
    with connect(db) as c:
        c.execute(q("LOCK_PLAN"), {"orgId": ORG, "id": pid}).fetchone()   # the one full read, as at start
        c.execute(q("SET_PLAN_STATUS"), {"orgId": ORG, "id": pid, "from": "APPROVED", "to": "EXECUTING"})
    status = {}
    times = []
    origin = (plan["steps"][0]["key"]["entityType"], plan["steps"][0]["key"]["entityId"]) if plan["steps"] else None
    with connect(db) as c:
        for s in plan["steps"]:
            t = time.perf_counter()
            n = s["number"]
            txn = str(uuid.uuid4())
            reason = f"Recovery plan step {n}: {s['reason']}"[:500]
            outcome, detail, rv, rid = None, "", None, None
            for d in s["dependsOn"]:
                dep = plan["steps"][d - 1]
                origin_review = dep["action"] == "MANUAL_REVIEW" and (dep["key"]["entityType"], dep["key"]["entityId"]) == origin
                if status.get(d) not in ("DONE", "SKIPPED") and not origin_review:
                    outcome, detail = "BLOCKED", f"Step {d} did not complete ({status.get(d)})"
                    break
            try:
                with c.transaction():
                    c.execute(q("LOCK_PLAN_STATUS"), {"orgId": ORG, "id": pid}).fetchone()
                    if outcome is None:
                        a = s["action"]
                        if a == "NO_ACTION":
                            outcome = "SKIPPED"
                        elif a in ("MANUAL_REVIEW", "SUPPLEMENTARY_CHARGE"):
                            outcome = "AWAITING_ACKNOWLEDGEMENT"
                        else:
                            k = s["key"]
                            locked = c.execute(q("LOCK_RECORD", ExecutionTables[k["entityType"]]),
                                               {"orgId": ORG, "id": k["entityId"]}).fetchone()
                            v, _ = latest(c, k["entityType"], k["entityId"])
                            if locked is None:
                                outcome, detail = "FAILED", "The record no longer exists"
                            elif v != s["expectedVersion"]:
                                outcome, detail = "FAILED", (f"Changed since planning: expected version {s['expectedVersion']},"
                                                             f" found {v}. Not overwritten.")
                            elif a == "ISSUE_CREDIT_NOTE":
                                rid = credit(c, txn, s, reason)
                                outcome, detail = "DONE", "credit note issued"
                            else:
                                rv = apply_fields(c, txn, s, reason)
                                outcome, detail = "DONE", f"Wrote version {rv}"
                    record_action(c, pid, s, outcome, detail, rv, rid, txn)
            except (Rejected, psycopg.errors.IntegrityError) as e:
                with c.transaction():
                    c.execute(q("LOCK_PLAN_STATUS"), {"orgId": ORG, "id": pid})
                    outcome = "FAILED"
                    record_action(c, pid, s, outcome, f"Rejected: {str(e).splitlines()[0]}"[:1000], None, None, txn)
            status[n] = outcome
            times.append(time.perf_counter() - t)
            if after_step:
                after_step(n)
        failed = any(v in ("FAILED", "BLOCKED") for v in status.values())
        c.execute(q("SET_PLAN_STATUS"), {"orgId": ORG, "id": pid, "from": "EXECUTING", "to": "FAILED" if failed else "EXECUTED"})
        c.commit()
    return status, times


ExecutionTables = {"product": "products", "order": "orders", "order_line": "order_lines", "invoice": "invoices",
                   "invoice_line": "invoice_lines"}


def record_action(c, pid, s, status, detail, rv, rid, txn):
    c.execute(q("INSERT_ACTION"), {"orgId": ORG, "planId": pid, "stepNo": s["number"], "action": s["action"],
                                   "entityType": s["key"]["entityType"], "entityId": s["key"]["entityId"],
                                   "expectedVersion": s["expectedVersion"], "status": status, "detail": detail or status,
                                   "resultVersion": rv, "resultEntityId": rid, "txnId": txn, "executedBy": EXECUTOR})


# ------------------------------------------------------------------ verification
def verify(db, pid, plan, status, fault_event, inc, work):
    checks = {}
    with connect(db) as c:
        c.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ")
        bad = 0
        n = 0
        for inv in plan["invariants"]:
            if inv["kind"] != "FIELD_EQUALS":
                continue
            n += 1
            row = c.execute(q("CURRENT_ROW", ExecutionTables[inv["key"]["entityType"]]),
                            {"orgId": ORG, "id": inv["key"]["entityId"]}).fetchone()
            if row is None or not same(dec_json(row[0]).get(inv["field"]), inv["expected"]):
                bad += 1
        checks["fieldEquals"] = {"checked": n, "failed": bad}
        o, d, cr = c.execute(TOTAL_VIOLATIONS, {"org": ORG}).fetchone()
        checks["totalsAllOrdersAndDrafts"] = {"orderViolations": o, "draftInvoiceViolations": d, "overCredited": cr}
        written = {(t, str(i)) for t, i in c.execute(q("WRITTEN_BY_PLAN"), {"orgId": ORG, "planId": pid})}
        actions = c.execute(q("LIST_ACTIONS"), {"orgId": ORG, "planId": pid}).fetchall()
        expected = set()
        for a in actions:
            if a[5] == "DONE":
                expected.add(("credit_note", str(a[8])) if a[8] else (a[2], str(a[3])))
        checks["scope"] = {"written": len(written), "expected": len(expected), "unexpected": len(written - expected),
                           "missing": len(expected - written)}
        checks["auditChainIntact"] = c.execute(q("VERIFY_CHAIN"), {"orgId": ORG}).fetchone()[0] is None
    t = time.perf_counter()
    again = pascar_load(db, fault_event, work / "after", incident=inc)
    checks["replan"] = {"automaticSteps": again["automaticSteps"], "manualSteps": again["manualSteps"],
                        "ms": ms(t)}
    automatic_done = all(status[s["number"]] == "DONE" for s in plan["steps"]
                         if s["action"] in ("RESTORE_FIELDS", "UPDATE_FIELDS", "ISSUE_CREDIT_NOTE"))
    passed = (automatic_done and checks["fieldEquals"]["failed"] == 0 and o == 0 and d == 0 and cr == 0
              and checks["scope"]["unexpected"] == 0 and checks["scope"]["missing"] == 0
              and checks["auditChainIntact"] and again["automaticSteps"] == 0)
    pending = sum(1 for v in status.values() if v == "AWAITING_ACKNOWLEDGEMENT")
    outcome = "FAILED" if not passed else ("INCOMPLETE" if pending else "PASSED")
    failed = 0 if passed else 1
    with connect(db) as c:
        c.execute(q("INSERT_VERIFICATION"), {"orgId": ORG, "planId": pid, "outcome": outcome, "checkCount": 6,
                                             "failedCount": failed, "pendingManual": pending, "checks": json.dumps(
                                             [{"kind": k, **v} if isinstance(v, dict) else {"kind": k, "passed": v}
                                              for k, v in checks.items()], default=str), "verifiedBy": APPROVER})
    return outcome, checks


# ------------------------------------------------------------------ one run
def run(orders, seed, scenario, contention_k, keep):
    tag = f"b{orders}_{seed}_{scenario.lower()}_{os.getpid()}"
    work = Path(tempfile.mkdtemp(prefix=tag + "_"))
    data = work / "data"
    res = {"orders": orders, "seed": seed, "scenario": scenario, "data": "synthetic"}
    t = time.perf_counter()
    sh("java", "-Xmx3g", "-cp", JAVA_CP, "com.atlas.recovery.engine.BenchmarkExport", str(orders), str(seed), scenario, str(data))
    res["exportMs"] = ms(t)
    meta = json.loads((data / "meta.json").read_text())
    res.update({k: meta[k] for k in ("versions", "edges", "truth")})
    truth = read_set(data / "truth.csv")
    db = tag
    try:
        t = time.perf_counter()
        create_db(db)
        load(db, data)
        res["loadMs"] = ms(t)
        with connect(db) as c:
            res["dbSizeMB"] = round(c.execute("SELECT pg_database_size(current_database())").fetchone()[0] / 2 ** 20, 1)
            fault_event, fault_idx = c.execute(
                "SELECT id, chain_index FROM change_events WHERE organization_id = %s AND entity_type = 'product' "
                "AND entity_id = %s AND entity_version = %s", (ORG, meta["faultyProduct"], meta["faultyVersion"])).fetchone()
            base = {}
            for name, query, params in (
                    ("NAIVE_FK", NAIVE_FK, {"org": ORG, "p": meta["faultyProduct"]}),
                    ("RECORD_LEVEL", RECORD_LEVEL, {"org": ORG, "p": meta["faultyProduct"], "v": meta["faultyVersion"]}),
                    ("FULL_RESTORE", FULL_RESTORE, {"org": ORG, "idx": fault_idx})):
                tq = time.perf_counter()
                selected = {(a, b) for a, b in c.execute(query, params)}
                base[name] = {**score(selected, truth), "queryMs": ms(tq)}
        # The full-restore cost: dump and restore the whole database (pre-recovery state).
        dump = work / "db.dump"
        t = time.perf_counter()
        sh("pg_dump", "-Fc", "-f", str(dump), db)
        base["FULL_RESTORE"]["pgDumpMs"] = ms(t)
        t = time.perf_counter()
        create_db_empty(db + "_r")
        sh("pg_restore", "-d", db + "_r", "--no-owner", "--disable-triggers", str(dump))
        base["FULL_RESTORE"]["pgRestoreMs"] = ms(t)
        res["baselines"] = base

        p = pascar_load(db, fault_event, work / "pascar", truth_dir=data)
        res["pascar"] = {k: p[k] for k in ("selected", "precision", "recall", "truePositives", "graphEdges", "graphRecords",
                                           "graphVersions", "parseMs", "analysisMs", "planMs", "steps", "automaticSteps",
                                           "manualSteps", "counts", "totalsChecked", "totalsMismatched", "dbLoadMs")}
        res["pascar"]["unaffectedTouched"] = p["selected"] - p["truePositives"]
        plan_json = (work / "pascar" / "plan.json").read_text()
        plan = json.loads(plan_json, parse_float=Decimal)

        with connect(db) as c:
            inc, pid = store_plan(c, fault_event, plan_json, plan)
        t = time.perf_counter()
        status, times = execute(db, pid, plan)
        exec_ms = ms(t)
        counts = {}
        for v in status.values():
            counts[v] = counts.get(v, 0) + 1
        times.sort()
        res["execution"] = {"ms": exec_ms, "steps": len(times), "outcomes": counts,
                            "stepMsP50": round(times[len(times) // 2] * 1000, 2) if times else 0,
                            "stepMsP95": round(times[int(len(times) * 0.95)] * 1000, 2) if times else 0,
                            "orchestration": "python-replay"}
        outcome, checks = verify(db, pid, plan, status, fault_event, inc, work)
        res["verification"] = {"outcome": outcome, **checks}
        with connect(db) as c:
            written = {(t_, str(i)) for t_, i in c.execute(q("WRITTEN_BY_PLAN"), {"orgId": ORG, "planId": pid})}
        res["execution"]["recordsWritten"] = len(written)
        res["execution"]["creditNotes"] = sum(1 for w in written if w[0] == "credit_note")
        res["execution"]["writtenOutsideTruth"] = len({w for w in written if w[0] != "credit_note"} - truth)

        if contention_k:
            res["contention"] = contention(db + "_r", fault_event, plan, plan_json, contention_k, work)
    finally:
        if not keep:
            drop_db(db)
            drop_db(db + "_r")
    return res


def create_db_empty(name):
    with connect("postgres", autocommit=True) as c:
        c.execute(f"DROP DATABASE IF EXISTS {name}")
        c.execute(f"CREATE DATABASE {name}")


def contention(db, fault_event, plan, plan_json, k, work):
    """A person edits k not-yet-reached draft order lines right after step 1 commits."""
    targets = [s for s in plan["steps"] if s["action"] == "UPDATE_FIELDS" and s["key"]["entityType"] == "order_line"
               and s["number"] > 1][-k:]
    edited = {}

    def person_edits(n):
        if n != 1:
            return
        with connect(db) as c:
            for s in targets:
                eid = s["key"]["entityId"]
                v, before = latest(c, "order_line", eid)
                new_price = Decimal("1234.5600")
                rv, lt = c.execute("UPDATE order_lines SET unit_price = %s, version = version + 1 WHERE id = %s "
                                   "RETURNING version, line_total", (new_price, eid)).fetchone()
                after = dict(before, unit_price=new_price, line_total=lt)
                c.execute(q("INSERT_CHANGE_EVENT"), {"orgId": ORG, "txnId": str(uuid.uuid4()), "actorKind": "USER",
                                                     "actorUserId": PERSON, "entityType": "order_line", "entityId": eid,
                                                     "version": v + 1, "operation": "UPDATE",
                                                     "changedFields": json.dumps(["line_total", "unit_price"]),
                                                     "before": _exact(before), "after": _exact(after),
                                                     "reason": "manual fix during recovery (synthetic)", "correlationId": None})
                edited[eid] = new_price

    out = {"editedLines": len(targets)}
    with connect(db) as c:
        inc, pid = store_plan(c, fault_event, plan_json, plan)
    status, _ = execute(db, pid, plan, after_step=person_edits)
    by_key = {s["key"]["entityId"]: s["number"] for s in plan["steps"]}
    out["conflictsDetected"] = sum(1 for eid in edited if status[by_key[eid]] == "FAILED")
    with connect(db) as c:
        lost = 0
        for eid, price in edited.items():
            cur = c.execute("SELECT unit_price FROM order_lines WHERE id = %s", (eid,)).fetchone()[0]
            if cur != price:
                lost += 1
        out["updatesLost"] = lost
        out["blocked"] = sum(1 for v in status.values() if v == "BLOCKED")
        out["done"] = sum(1 for v in status.values() if v == "DONE")
        out["planStatus"] = c.execute("SELECT status FROM recovery_plans WHERE id = %s", (pid,)).fetchone()[0]
    again = pascar_load(db, fault_event, work / "contention-after", incident=inc)
    out["fingerprintChanged"] = again["fingerprint"] != plan["fingerprint"]
    out["replanAutomaticSteps"] = again["automaticSteps"]
    out["replanManualSteps"] = again["manualSteps"]
    out["replanConflicted"] = again["counts"].get("CONFLICTED", 0)
    return out


JAVA_CP = os.environ.get("ATLAS_BENCH_CP", "")

if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--orders", type=int, required=True)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--scenario", default="LIVE", choices=["LIVE", "OVERWRITTEN", "OVERBILLED"])
    ap.add_argument("--contention", type=int, default=5)
    ap.add_argument("--out")
    ap.add_argument("--keep", action="store_true")
    a = ap.parse_args()
    if not JAVA_CP:
        sys.exit("set ATLAS_BENCH_CP to the compiled engine + test classes and Jackson (see scripts/bench/run.sh)")
    r = run(a.orders, a.seed, a.scenario, a.contention, a.keep)
    line = json.dumps(r, default=str)
    print(line)
    if a.out:
        with open(a.out, "a") as f:
            f.write(line + "\n")
