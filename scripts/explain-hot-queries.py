#!/usr/bin/env python3
"""
Runs the application's hot SQL (extracted from the Java source, as the SQL harness
does) under EXPLAIN (ANALYZE, BUFFERS) against a large database, and reports the
median execution time of 5 runs, the plan's top node, and any sequential scan of
a table with more than 10,000 rows.

Parameters are taken from real rows of the database (an order with lines, the most
consumed product, a mid-history transaction, ...), so the plans are the ones a
busy tenant would get.

Usage: PG* env vars set; scripts/explain-hot-queries.py <database> [--markdown out.md]
The database is typically one loaded by scripts/bench (synthetic, 100k orders).
"""
import argparse
import json
import statistics
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "bench"))
import os  # noqa: E402

os.environ.setdefault("ATLAS_BENCH_CP", "unused")
import benchmark as b  # noqa: E402  (SQL extraction + helpers)
import psycopg  # noqa: E402

BIG = 10_000
ALL_NULL_EVENTS = dict(entityType=None, entityId=None, actorUserId=None, txnId=None, fromTs=None, toTs=None)


def cases(c):
    org = b.ORG
    one = lambda sql: c.execute(sql).fetchone()  # noqa: E731
    order_id, = one("SELECT order_id FROM order_lines GROUP BY order_id ORDER BY count(*) DESC, order_id LIMIT 1")
    product_id, = one("SELECT product_id FROM order_lines GROUP BY product_id ORDER BY count(*) DESC LIMIT 1")
    line_id, = one(f"SELECT id FROM order_lines WHERE order_id = '{order_id}' ORDER BY line_no LIMIT 1")
    event_id, txn_id, ts = one("SELECT id, txn_id, occurred_at FROM change_events ORDER BY chain_index "
                               "OFFSET (SELECT count(*) / 2 FROM change_events) LIMIT 1")
    customer_id, = one("SELECT customer_id FROM orders LIMIT 1")
    page = dict(limit=20, offset=0)
    return [
        ("Audit list, newest first (no filter)", "LIST_EVENTS", None, dict(orgId=org, **ALL_NULL_EVENTS, **page)),
        ("Audit list, page 500", "LIST_EVENTS", None, dict(orgId=org, **ALL_NULL_EVENTS, limit=20, offset=10_000)),
        ("Audit total (no filter)", "COUNT_EVENTS", None, dict(orgId=org, **ALL_NULL_EVENTS)),
        ("Audit list for one record (history page)", "LIST_EVENTS", None,
         dict(orgId=org, **{**ALL_NULL_EVENTS, "entityType": "order", "entityId": order_id}, **page)),
        ("Audit total for one record", "COUNT_EVENTS", None,
         dict(orgId=org, **{**ALL_NULL_EVENTS, "entityType": "order", "entityId": order_id})),
        ("Audit list for one transaction", "LIST_EVENTS", None,
         dict(orgId=org, **{**ALL_NULL_EVENTS, "txnId": txn_id}, **page)),
        ("Audit list, one-hour window", "LIST_EVENTS", None,
         dict(orgId=org, **{**ALL_NULL_EVENTS, "fromTs": ts, "toTs": ts + __import__("datetime").timedelta(hours=1)},
              **page)),
        ("Audit list by record type only", "LIST_EVENTS", None,
         dict(orgId=org, **{**ALL_NULL_EVENTS, "entityType": "invoice"}, **page)),
        ("Audit total by record type only", "COUNT_EVENTS", None,
         dict(orgId=org, **{**ALL_NULL_EVENTS, "entityType": "invoice"})),
        ("One audit event", "FIND_EVENT", None, dict(orgId=org, id=event_id)),
        ("Provenance: what a busy product fed into", "LIST_DOWNSTREAM_EDGES", None,
         dict(orgId=org, entityType="product", entityId=product_id, version=None)),
        ("Provenance: what an order was computed from", "LIST_UPSTREAM_EDGES", None,
         dict(orgId=org, entityType="order", entityId=order_id, version=None)),
        ("Provenance: one line version, downstream", "LIST_DOWNSTREAM_EDGES", None,
         dict(orgId=org, entityType="order_line", entityId=line_id, version=0)),
        ("Orders list, newest first", "LIST_ORDERS", "created_at DESC", dict(orgId=org, status=None, customerId=None, **page)),
        ("Orders list, CONFIRMED", "LIST_ORDERS", "created_at DESC",
         dict(orgId=org, status="CONFIRMED", customerId=None, **page)),
        ("Orders total", "COUNT_ORDERS", None, dict(orgId=org, status=None, customerId=None)),
        ("Orders of one customer", "LIST_ORDERS", "created_at DESC",
         dict(orgId=org, status=None, customerId=customer_id, **page)),
        ("Invoices list, ISSUED", "LIST_INVOICES", "created_at DESC", dict(orgId=org, status="ISSUED", orderId=None, **page)),
        ("Invoices total", "COUNT_INVOICES", None, dict(orgId=org, status=None, orderId=None)),
        ("Lines of an order", "LIST_ORDER_LINES", None, dict(orgId=org, orderId=order_id)),
        ("Products search 'widget 1'", "LIST_PRODUCTS", "created_at DESC",
         dict(orgId=org, pattern="%widget 1%", active=None, **page)),
        ("Sum of an order's lines", "SUM_ORDER_LINES", None, dict(orgId=org, id=order_id)),
    ]


def walk(node, out):
    out.append(node)
    for child in node.get("Plans", []):
        walk(child, out)
    return out


def explain(c, name, fmt, params, sizes):
    sql = b.q(name, fmt)
    times, plan = [], None
    for _ in range(5):
        r = c.execute("EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + sql, params).fetchone()[0][0]
        times.append(r["Execution Time"])
        plan = r["Plan"]
    nodes = walk(plan, [])
    seq = sorted({n["Relation Name"] for n in nodes
                  if n["Node Type"] == "Seq Scan" and sizes.get(n.get("Relation Name"), 0) > BIG})
    used = sorted({n["Index Name"] for n in nodes if "Index Name" in n})
    return {"ms": round(statistics.median(times), 2), "top": plan["Node Type"], "seqScans": seq,
            "indexes": used, "rows": plan.get("Actual Rows"),
            "buffers": plan.get("Shared Hit Blocks", 0) + plan.get("Shared Read Blocks", 0)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("database")
    ap.add_argument("--markdown")
    ap.add_argument("--json")
    a = ap.parse_args()
    results = []
    with psycopg.connect(dbname=a.database, autocommit=True) as c:
        sizes = dict(c.execute("SELECT relname, n_live_tup FROM pg_stat_user_tables").fetchall())
        for label, name, fmt, params in cases(c):
            r = explain(c, name, fmt, params, sizes)
            r.update(label=label, statement=name)
            results.append(r)
            flag = "  SEQ SCAN: " + ", ".join(r["seqScans"]) if r["seqScans"] else ""
            print(f"{r['ms']:9.2f} ms  {label:45} {r['top']:16} rows={r['rows']}{flag}")
        t = __import__("time").perf_counter()
        broken = c.execute(b.q("VERIFY_CHAIN"), {"orgId": b.ORG}).fetchone()[0]
        chain_ms = round((__import__("time").perf_counter() - t) * 1000)
        print(f"{chain_ms:9} ms  full audit-chain verification ({sizes.get('change_events')} events), "
              f"first broken: {broken}")
    if a.json:
        Path(a.json).write_text(json.dumps({"results": results, "verifyChainMs": chain_ms, "tableRows": sizes},
                                           default=str, indent=1))
    if a.markdown:
        lines = ["| Query | Statement | Median ms (5 runs) | Plan top | Seq scan of a large table | Indexes used |",
                 "|---|---|---|---|---|---|"]
        for r in results:
            lines.append(f"| {r['label']} | `{r['statement']}` | {r['ms']} | {r['top']} | "
                         f"{', '.join(r['seqScans']) or '—'} | {', '.join(r['indexes']) or '—'} |")
        lines.append(f"| Full audit-chain verification | `VERIFY_CHAIN` | {chain_ms} (1 run) | — | change_events (by design) | — |")
        Path(a.markdown).write_text("\n".join(lines) + "\n")


if __name__ == "__main__":
    main()
