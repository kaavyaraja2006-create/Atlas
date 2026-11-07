#!/usr/bin/env python3
"""
SQL harness for the ATLAS X modules: runs their SQL (extracted from the Java source)
on a throwaway PostgreSQL 16 database built from the real Flyway migrations, against a
small hand-made history whose right answers are known. Every ATLAS X statement must be
exercised. Needs psql and PG* variables with a role that may create databases.
"""
import json
import sys
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from sqlharness import (ATLASX_FILES, JAVA, Harness, atlasx_statement_names, create_database,  # noqa: E402
                        drop_database, extract)

DB = "atlas_x_harness"
H = Harness(DB, extract())
run, psql, check = H.run, H.psql, H.check
U = lambda: str(uuid.uuid4())  # noqa: E731


def event(org, etype, eid, version, op, changed, after, before=None, actor="USER", user=None, txn=None, reason=None):
    if actor == "USER" and user is None:
        user = USER   # a USER event must name its user (schema check)
    return run("INSERT_CHANGE_EVENT", orgId=org, txnId=txn or U(), actorKind=actor, actorUserId=user,
               entityType=etype, entityId=eid, version=version, operation=op, changedFields=json.dumps(changed),
               before=json.dumps(before) if before is not None else None, after=json.dumps(after), reason=reason,
               correlationId=None)[0]


def edge(org, src, sf, dst, df, relation, operation="order.add_line"):
    run("INSERT_PROVENANCE_EDGE", orgId=org, txnId=U(), sourceType=src[0], sourceId=src[1], sourceVersion=src[2],
        sourceField=sf, targetType=dst[0], targetId=dst[1], targetVersion=dst[2], targetField=df,
        relation=relation, operation=operation)


def lineage(org, etype, eid, version=None, direction="LINEAGE_DOWNSTREAM", depth=6):
    rows = run(direction, orgId=org, entityType=etype, entityId=eid, version=version, maxDepth=depth)
    out = []
    for r in rows:
        f = r.split("|")
        out.append(dict(depth=int(f[0]), src=(f[1], f[2], int(f[3])), sf=f[4], dst=(f[5], f[6], int(f[7])), df=f[8],
                        relation=f[9], operation=f[10], evidence=f[11], confidence=f[12], sv=f[13], tv=f[14],
                        actor=f[16]))
    return out


create_database(DB)
try:
    print(f"== {len(atlasx_statement_names())} ATLAS X statements in {len(ATLASX_FILES)} files")
    o1 = run("INSERT_ORGANIZATION", slug="x-one", name="X one (test)")[0].split("|")[0]
    o2 = run("INSERT_ORGANIZATION", slug="x-two", name="X two (test)")[0].split("|")[0]
    user = USER = run("INSERT_USER", email="x@atlas.test", displayName="X (test)", passwordHash="{bcrypt}x")[0].split("|")[0]

    # ------------------------------------------------------------------ Milestone 2: provenance
    print("== provenance: lineage, confidence, gaps, isolation")
    P, Q, C, L1, L2, L3, L4, O, IL1, IL2, INV = (U() for _ in range(11))
    prod = {"sku": "P1", "name": "Bottle", "unit_price": 1000.0, "active": True}
    event(o1, "product", P, 0, "INSERT", ["sku", "name", "unit_price", "active"], prod, user=user)
    FAULT = event(o1, "product", P, 1, "UPDATE", ["unit_price"], {**prod, "unit_price": 100.0}, before=prod, user=user,
                  reason="the faulty edit (test)")
    event(o1, "product", Q, 0, "INSERT", ["unit_price"], {"unit_price": 50.0}, user=user)
    event(o1, "customer", C, 0, "INSERT", ["name"], {"name": "Customer (test)"}, user=user)
    # An order references the customer by foreign key, but consumes none of its values: no edge.
    event(o1, "order", O, 0, "INSERT", ["customer_id", "total_amount"], {"customer_id": C, "total_amount": 0}, user=user)
    event(o1, "order_line", L1, 0, "INSERT", ["unit_price", "quantity", "line_total"],
          {"order_id": O, "product_id": P, "unit_price": 100.0, "quantity": 2, "line_total": 200.0}, user=user)
    edge(o1, ("product", P, 1), "unit_price", ("order_line", L1, 0), "unit_price", "DERIVED_VALUE")
    event(o1, "order_line", L2, 0, "INSERT", ["unit_price", "quantity", "line_total"],
          {"order_id": O, "product_id": Q, "unit_price": 50.0, "quantity": 1, "line_total": 50.0}, user=user)
    edge(o1, ("product", Q, 0), "unit_price", ("order_line", L2, 0), "unit_price", "DERIVED_VALUE")
    event(o1, "order", O, 1, "UPDATE", ["total_amount"], {"customer_id": C, "total_amount": 250.0},
          before={"customer_id": C, "total_amount": 0}, user=user)
    edge(o1, ("order_line", L1, 0), "line_total", ("order", O, 1), "total_amount", "AGGREGATED_INTO")
    edge(o1, ("order_line", L2, 0), "line_total", ("order", O, 1), "total_amount", "AGGREGATED_INTO")
    event(o1, "invoice_line", IL1, 0, "INSERT", ["unit_price", "line_total"], {"unit_price": 100.0, "line_total": 200.0})
    edge(o1, ("order_line", L1, 0), "unit_price", ("invoice_line", IL1, 0), "unit_price", "COPIED_INTO",
         "invoice.create_from_order")
    event(o1, "invoice_line", IL2, 0, "INSERT", ["unit_price", "line_total"], {"unit_price": 50.0, "line_total": 50.0})
    edge(o1, ("order_line", L2, 0), "unit_price", ("invoice_line", IL2, 0), "unit_price", "COPIED_INTO",
         "invoice.create_from_order")
    event(o1, "invoice", INV, 0, "INSERT", ["total_amount"], {"total_amount": 250.0})
    for il in (IL1, IL2):
        edge(o1, ("invoice_line", il, 0), "line_total", ("invoice", INV, 0), "total_amount", "AGGREGATED_INTO",
             "invoice.create_from_order")
    # A line written with NO provenance at all (a gap), and one whose recorded edge disagrees with the data.
    event(o1, "order_line", L3, 0, "INSERT", ["unit_price", "quantity", "line_total"],
          {"product_id": P, "unit_price": 100.0, "quantity": 1, "line_total": 100.0}, user=user)
    event(o1, "order_line", L4, 0, "INSERT", ["unit_price", "quantity", "line_total"],
          {"product_id": P, "unit_price": 900.0, "quantity": 1, "line_total": 900.0}, user=user)
    edge(o1, ("product", P, 0), "unit_price", ("order_line", L4, 0), "unit_price", "DERIVED_VALUE")

    down = lineage(o1, "product", P, version=1)
    keyset = {(e["src"][0], e["dst"][0], e["dst"][1], e["depth"]) for e in down}
    check("direct consumption: product@1 -> line 1 at depth 1", ("product", "order_line", L1, 1) in keyset, keyset)
    check("multi-level: line -> order total (2), line -> invoice line (2), invoice line -> invoice (3)",
          {("order_line", "order", O, 2), ("order_line", "invoice_line", IL1, 2),
           ("invoice_line", "invoice", INV, 3)} <= keyset, keyset)
    check("a sibling input of the same order is not downstream of the faulty product",
          not any(e["dst"][1] == IL2 for e in down), down)
    check("lineage from one version does not include other versions' consumers (line 4 used v0)",
          not any(e["dst"][1] == L4 for e in down))
    d = {(e["src"][1], e["dst"][1]): e for e in down}
    check("a value copied exactly is VERIFIED", d[(P, L1)]["confidence"] == "VERIFIED", d[(P, L1)])
    check("each edge says which operation produced it", d[(P, L1)]["operation"] == "order.add_line"
          and d[(L1, IL1)]["operation"] == "invoice.create_from_order")
    check("the actor is taken from the target's change event", d[(P, L1)]["actor"] == "USER")
    up = lineage(o1, "order", O, version=1, direction="LINEAGE_UPSTREAM")
    agg = [e for e in up if e["dst"][1] == O]
    check("multiple source records into one field: both lines feed the order total",
          {e["src"][1] for e in agg} == {L1, L2}, agg)
    check("an aggregate equal to the sum of all its recorded inputs is VERIFIED",
          all(e["confidence"] == "VERIFIED" for e in agg), agg)
    check("upstream reaches both products at depth 2",
          {(e["src"][1], e["depth"]) for e in up if e["src"][0] == "product"} == {(P, 2), (Q, 2)}, up)
    l4 = lineage(o1, "order_line", L4, direction="LINEAGE_UPSTREAM")
    check("a recorded edge whose values disagree is only DECLARED", l4 and l4[0]["confidence"] == "DECLARED"
          and l4[0]["sv"] == "1000.0" and l4[0]["tv"] == "900.0", l4)
    check("reference without consumption: the customer has no downstream lineage",
          lineage(o1, "customer", C) == [])
    check("depth limit is honoured", max(e["depth"] for e in lineage(o1, "product", P, version=1, depth=2)) == 2)

    # Backfilled (inferred) edge: written by a backfill job, never by an operation.
    psql(f"""INSERT INTO provenance_edges (organization_id, txn_id, source_entity_type, source_entity_id,
             source_entity_version, source_field, target_entity_type, target_entity_id, target_entity_version,
             target_field, relation, evidence)
             VALUES ('{o1}', '{U()}', 'product', '{P}', 1, 'unit_price', 'order_line', '{L3}', 0, 'unit_price',
                     'DERIVED_VALUE', 'BACKFILLED')""")
    l3 = lineage(o1, "order_line", L3, direction="LINEAGE_UPSTREAM")
    check("a backfilled edge is INFERRED even though its values agree", l3[0]["confidence"] == "INFERRED"
          and l3[0]["operation"] == "", l3)

    gaps = run("PROVENANCE_GAPS", orgId=o1, entityType=None, limit=100)
    gap_keys = {(g.split("|")[0], g.split("|")[1], g.split("|")[3]) for g in gaps}
    check("a value with only inferred provenance is INFERRED, not a gap", ("order_line", L3, "unit_price") not in gap_keys)
    ok, err = psql(f"DELETE FROM provenance_edges WHERE organization_id = '{o1}' AND evidence = 'BACKFILLED'",
                   expect_error="append-only")
    check("provenance stays append-only", ok, err)
    L5 = U()
    event(o1, "order_line", L5, 0, "INSERT", ["unit_price", "quantity", "line_total"],
          {"unit_price": 70.0, "quantity": 1, "line_total": 70.0}, user=user)
    gaps = run("PROVENANCE_GAPS", orgId=o1, entityType=None, limit=100)
    gap_keys = {(g.split("|")[0], g.split("|")[1], g.split("|")[3]) for g in gaps}
    check("missing provenance is reported (UNKNOWN), with its requirement",
          ("order_line", L5, "unit_price") in gap_keys and any("priced from a product" in g for g in gaps), gaps)
    check("an empty order (total 0) needs no provenance", ("order", O, "total_amount") not in gap_keys, gap_keys)
    check("values with recorded provenance are not gaps",
          not gap_keys & {("order_line", L1, "unit_price"), ("order", O, "total_amount"), ("invoice", INV, "total_amount")})
    check("gaps can be filtered by record type",
          all(g.startswith("order_line|") for g in run("PROVENANCE_GAPS", orgId=o1, entityType="order_line", limit=100)))

    # Cross-tenant: another organization's edges never appear, even for the same entity id.
    X = U()
    event(o2, "product", P, 1, "INSERT", ["unit_price"], {"unit_price": 100.0})
    event(o2, "order_line", X, 0, "INSERT", ["unit_price"], {"unit_price": 100.0})
    edge(o2, ("product", P, 1), "unit_price", ("order_line", X, 0), "unit_price", "DERIVED_VALUE")
    check("tenant isolation: organization 1 does not see organization 2's consumers of the same id",
          not any(e["dst"][1] == X for e in lineage(o1, "product", P, version=1)))
    check("and organization 2 sees only its own", [e["dst"][1] for e in lineage(o2, "product", P)] == [X])
    check("gaps are per organization", all(g.split("|")[1] != X for g in run("PROVENANCE_GAPS", orgId=o1,
                                                                             entityType=None, limit=100)))

    # Cycle: A@0 -> B@0 -> A@0 (impossible in normal operation, possible in a corrupted graph).
    A, B = U(), U()
    event(o1, "order", A, 0, "INSERT", ["total_amount"], {"total_amount": 5.0})
    event(o1, "order", B, 0, "INSERT", ["total_amount"], {"total_amount": 5.0})
    edge(o1, ("order", A, 0), "total_amount", ("order", B, 0), "total_amount", "COPIED_INTO")
    edge(o1, ("order", B, 0), "total_amount", ("order", A, 0), "total_amount", "COPIED_INTO")
    cyc = psql("SET statement_timeout = '5s'; " + H.bind("LINEAGE_DOWNSTREAM", dict(
        orgId=o1, entityType="order", entityId=A, version=0, maxDepth=12)))
    cyc = [r for r in cyc if r != "SET"]
    check("cycle-safe: lineage over a cycle terminates and lists each edge once", len(cyc) == 2, cyc)

    # ------------------------------------------------------------------ Milestone 3: history and conflicts
    print("== field history (view over change_events) and conflict reviews")
    event(o1, "order", O, 2, "UPDATE", ["delivery_note"], {"customer_id": C, "total_amount": 250.0,
          "delivery_note": "Leave at gate 2"}, before={"customer_id": C, "total_amount": 250.0, "delivery_note": None},
          user=user, reason="delivery note changed")
    rows = [r.split("|") for r in run("FIELD_HISTORY", orgId=o1, entityType="order", entityId=O, field=None)]
    check("field history: one row per changed field per version",
          [(r[2], r[4]) for r in rows] == [("customer_id", "0"), ("total_amount", "0"), ("total_amount", "1"),
                                           ("delivery_note", "2")], [(r[2], r[4]) for r in rows])
    tot = [r for r in rows if r[2] == "total_amount" and r[4] == "1"][0]
    check("previous and new value and version of a field change", tot[3] == "0" and tot[5] == "0" and tot[6] == "250.0", tot)
    check("an INSERT has no previous version", rows[0][3] == "" and rows[0][5] == "", rows[0])
    dn = [r for r in rows if r[2] == "delivery_note"][0]
    check("actor, reason and time come from the change event", dn[8] == "USER" and dn[10] == "X (test)"
          and dn[12] == "delivery note changed" and dn[14] != "", dn)
    check("field filter", [r.split("|")[2] for r in run("FIELD_HISTORY", orgId=o1, entityType="order", entityId=O,
                                                         field="delivery_note")] == ["delivery_note"])
    check("field history is per organization",
          run("FIELD_HISTORY", orgId=o2, entityType="order", entityId=O, field=None) == [])
    tl = [r.split("|") for r in run("TIMELINE", orgId=o1, entityType="order", entityId=O)]
    check("timeline: every version in order with its changed fields",
          [(r[0], r[3]) for r in tl] == [("0", '["customer_id", "total_amount"]'), ("1", '["total_amount"]'),
                                         ("2", '["delivery_note"]')], tl)
    check("view and history agree: the view has no rows the history does not explain",
          psql(f"SELECT count(*) FROM field_changes WHERE organization_id = '{o1}' AND entity_id = '{O}'") == ["4"])

    inc = run("INSERT_INCIDENT", orgId=o1, incidentNumber="INC-000001", changeEventId=FAULT, title="Bottle price",
              reason="extra zero missing (test)", openedBy=user)[0].split("|")[0]
    run("INSERT_CONFLICT_REVIEW", orgId=o1, incidentId=inc, entityType="order_line", entityId=L1, field="unit_price",
        kind="SAME_FIELD_CONFLICT", decision="NEEDS_INVESTIGATION", note="asking sales", reviewedBy=user)
    run("INSERT_CONFLICT_REVIEW", orgId=o1, incidentId=inc, entityType="order_line", entityId=L1, field="unit_price",
        kind="SAME_FIELD_CONFLICT", decision="KEEP_LATER_CHANGE", note="sales confirmed the discount", reviewedBy=user)
    run("INSERT_CONFLICT_REVIEW", orgId=o1, incidentId=inc, entityType="order", entityId=O, field="total_amount",
        kind="DIFFERENT_FIELD_UPDATE", decision="RECOVERY_MAY_OVERWRITE", note="only the total", reviewedBy=user)
    latest = [r.split("|") for r in run("LATEST_CONFLICT_REVIEWS", orgId=o1, incidentId=inc)]
    by = {(r[0], r[2]): r for r in latest}
    check("the latest decision per (record, field) wins", len(latest) == 2
          and by[("order_line", "unit_price")][4] == "KEEP_LATER_CHANGE"
          and by[("order_line", "unit_price")][7] == "X (test)", latest)
    ok, err = psql(f"UPDATE recovery_conflict_reviews SET decision = 'RECOVERY_MAY_OVERWRITE'", expect_error="append-only")
    check("reviews are append-only", ok, err)
    ok, err = psql(H.bind("INSERT_CONFLICT_REVIEW", dict(orgId=o1, incidentId=inc, entityType="order", entityId=O,
                   field="total_amount", kind="SAME_FIELD_CONFLICT", decision="DELETE_IT", note="nope", reviewedBy=user)),
                   expect_error="check constraint")
    check("an unknown decision is rejected", ok, err)
    ok, err = psql(H.bind("INSERT_CONFLICT_REVIEW", dict(orgId=o2, incidentId=inc, entityType="order", entityId=O,
                   field="total_amount", kind="SAME_FIELD_CONFLICT", decision="KEEP_LATER_CHANGE", note="cross tenant",
                   reviewedBy=user)), expect_error="foreign key")
    check("a review cannot attach to another organization's incident", ok, err)
    check("reviews are per organization", run("LATEST_CONFLICT_REVIEWS", orgId=o2, incidentId=inc) == [])

    # ------------------------------------------------------------------ Milestone 4: simulation snapshot and runs
    print("== simulation: consistent snapshot, foreign-key closure, run lifecycle, retention")
    OE, LE = U(), U()
    # Business rows, so the foreign-key part of the snapshot has something to follow.
    psql(f"""INSERT INTO customers (id, organization_id, name) VALUES ('{C}', '{o1}', 'Customer (test)');
        INSERT INTO products (id, organization_id, sku, name, unit_price, unit_cost) VALUES
            ('{P}', '{o1}', 'P1', 'Bottle', 100, 50), ('{Q}', '{o1}', 'Q1', 'Cap', 50, 20);
        INSERT INTO orders (id, organization_id, order_number, customer_id, created_by) VALUES
            ('{O}', '{o1}', 'SO-1', '{C}', '{user}'), ('{OE}', '{o1}', 'SO-0', '{C}', '{user}');
        INSERT INTO order_lines (id, organization_id, order_id, line_no, product_id, description, quantity, unit_price) VALUES
            ('{L1}', '{o1}', '{O}', 1, '{P}', 'Bottle', 2, 100), ('{L2}', '{o1}', '{O}', 2, '{Q}', 'Cap', 1, 50),
            ('{LE}', '{o1}', '{OE}', 1, '{P}', 'Bottle', 1, 1000);
        INSERT INTO invoices (id, organization_id, invoice_number, order_id) VALUES ('{INV}', '{o1}', 'INV-1', '{O}');
        INSERT INTO invoice_lines (id, organization_id, invoice_id, line_no, order_line_id, description, quantity, unit_price)
            VALUES ('{IL1}', '{o1}', '{INV}', 1, '{L1}', 'Bottle', 2, 100), ('{IL2}', '{o1}', '{INV}', 2, '{L2}', 'Cap', 1, 50);""")
    # An order priced from the product BEFORE the fault: linked by foreign key, not reached by provenance.
    event(o1, "order", OE, 0, "INSERT", ["customer_id", "total_amount"], {"customer_id": C, "total_amount": 1000.0})
    event(o1, "order_line", LE, 0, "INSERT", ["unit_price", "quantity", "line_total"],
          {"order_id": OE, "product_id": P, "unit_price": 1000.0, "quantity": 1, "line_total": 1000.0})
    edge(o1, ("product", P, 0), "unit_price", ("order_line", LE, 0), "unit_price", "DERIVED_VALUE")
    head = int(run("SNAPSHOT_HEAD", orgId=o1)[0])
    check("snapshot position is the organization's chain head", head == int(psql(
        f"SELECT max(chain_index) FROM change_events WHERE organization_id = '{o1}'")[0]), head)
    check("an organization without history is at position 0", run("SNAPSHOT_HEAD", orgId=U()) == ["0"])
    snap = [r.split("|") for r in run("LOAD_SIMULATION_VERSIONS", orgId=o1, entityType="product", entityId=P, version=1)]
    ents = {(r[0], r[1]) for r in snap}
    check("snapshot holds the records provenance reaches (origin, lines, order, invoice)",
          {("product", P), ("order_line", L1), ("order", O), ("invoice_line", IL1), ("invoice", INV)} <= ents, ents)
    check("and the foreign-key closure: the order priced before the fault and its line",
          {("order", OE), ("order_line", LE)} <= ents, ents)
    check("and sibling lines needed to check totals", {("order_line", L2), ("invoice_line", IL2)} <= ents)
    check("but not a line priced from another version that no key or edge links (line 4)", ("order_line", L4) not in ents)
    check("nor another organization's records", ("order_line", X) not in ents)
    check("every version carries its audit-chain position, all distinct",
          len({r[7] for r in snap}) == len(snap) and all(r[7].isdigit() for r in snap))
    check("histories are complete: both product versions", sorted(r[2] for r in snap if r[1] == P) == ["0", "1"])
    fk_only = [r for r in run("LOAD_SIMULATION_VERSIONS", orgId=o1, entityType="order", entityId=O, version=1)]
    check("the foreign-key closure applies to product origins only",
          not any(r.split("|")[1] == OE for r in fk_only), fk_only)

    r1 = run("INSERT_SIMULATION_RUN", orgId=o1, incidentId=inc, startedBy=user)[0]
    ok, err = psql(H.bind("INSERT_SIMULATION_RUN", dict(orgId=o1, incidentId=inc, startedBy=user)),
                   expect_error="ux_simulation_running")
    check("one running simulation per incident", ok, err)
    def details(total, stored, truncated):
        return dict(recordsTotal=total, recordsProposed=5, recordsPreserved=1, recordsConflicting=1, recordsUnresolved=1,
                    preservedLaterChanges=2, unresolvedDependencies=1, detailsStored=stored, detailsTruncated=truncated)
    for strategy, letter, repaired, d in (("FULL_RESTORE", "A", 3, details(9, 2, True)),
                                          ("PROVENANCE_CONFLICT_AWARE", "F", 5, details(3, 3, False))):
        run("INSERT_SIMULATION_RESULT", orgId=o1, runId=r1, strategy=strategy, letter=letter, applicable=True,
            notApplicableReason=None, recordsInspected=9, recordsModified=5, recordsDeleted=0, faultyRecords=5,
            repaired=repaired, collateralModified=0, legitimateOverwritten=0, conflictsSurfaced=0, conflictsIgnored=0,
            invariantViolations=0, fieldWrites=7, compensations=1, manualSteps=0, simulationMicros=812,
            violations="[]", notes='["modelled only"]', **d)
    ok, err = psql(H.bind("INSERT_SIMULATION_RESULT", dict(orgId=o1, runId=r1, strategy="NAIVE_FK_ROW", letter="B",
                   applicable=True, notApplicableReason=None, recordsInspected=1, recordsModified=1, recordsDeleted=0,
                   faultyRecords=1, repaired=2, collateralModified=0, legitimateOverwritten=0, conflictsSurfaced=0,
                   conflictsIgnored=0, invariantViolations=0, fieldWrites=1, compensations=0, manualSteps=0,
                   simulationMicros=1, violations="[]", notes="[]", **details(1, 1, False))), expect_error="check constraint")
    check("a result cannot repair more records than were faulty", ok, err)
    ok, err = psql(H.bind("INSERT_SIMULATION_RESULT", dict(orgId=o1, runId=r1, strategy="NAIVE_FK_ROW", letter="B",
                   applicable=True, notApplicableReason=None, recordsInspected=1, recordsModified=1, recordsDeleted=0,
                   faultyRecords=1, repaired=1, collateralModified=0, legitimateOverwritten=0, conflictsSurfaced=0,
                   conflictsIgnored=0, invariantViolations=0, fieldWrites=1, compensations=0, manualSteps=0,
                   simulationMicros=1, violations="[]", notes="[]", **details(9, 2, False))),
                   expect_error="simulation_results_details_consistent")
    check("a result cannot store fewer details than its total without saying it was truncated", ok, err)
    ok, err = psql(f"UPDATE simulation_runs SET status = 'COMPLETED' WHERE id = '{r1}'", expect_error="check constraint")
    check("a completed run must carry its assessment", ok, err)
    done = run("COMPLETE_SIMULATION_RUN", orgId=o1, id=r1, chainIndex=head, fingerprint="sha256:abc", records=9,
               versions=14, recommended="PROVENANCE_CONFLICT_AWARE", riskLevel="MEDIUM",
               assessment='{"summary": "Recommended: F"}', durationMs=42)
    check("a run completes once", done.tags == ["UPDATE 1"] and run("COMPLETE_SIMULATION_RUN", orgId=o1, id=r1,
          chainIndex=head, fingerprint="x", records=1, versions=1, recommended=None, riskLevel="LOW",
          assessment="{}", durationMs=1).tags == ["UPDATE 0"])
    r2 = run("INSERT_SIMULATION_RUN", orgId=o1, incidentId=inc, startedBy=user)[0]
    run("FAIL_SIMULATION_RUN", orgId=o1, id=r2, failure="Too large to simulate", durationMs=3)
    found = run("FIND_SIMULATION_RUN", orgId=o1, id=r1)[0].split("|")
    check("a run is found with its assessment and who started it", found[2] == "COMPLETED" and found[7] ==
          "PROVENANCE_CONFLICT_AWARE" and '"Recommended: F"' in found[9] and found[12] == "X (test)", found)
    check("runs list newest first, failures included",
          [r.split("|")[2] for r in run("LIST_SIMULATION_RUNS", orgId=o1, incidentId=inc)] == ["FAILED", "COMPLETED"])
    check("the latest completed run is the one assessments use",
          run("LATEST_COMPLETED_SIMULATION", orgId=o1, incidentId=inc)[0].startswith(r1))
    res = [r.split("|") for r in run("LIST_SIMULATION_RESULTS", orgId=o1, runId=r1)]
    check("results come back per strategy, A before F", [r[1] for r in res] == ["A", "F"] and res[1][8] == "5"
          and res[0][19] == '["modelled only"]', res)
    check("runs are per organization", run("FIND_SIMULATION_RUN", orgId=o2, id=r1) == []
          and run("LIST_SIMULATION_RESULTS", orgId=o2, runId=r1) == [])
    check("results carry exact totals and the truncation of their details",
          res[0][20:29] == ["9", "5", "1", "1", "1", "2", "1", "2", "t"] and res[1][28] == "f", res[0][20:])

    # ------------------------------------------------------------------ Graph Intelligence step 2: record details
    print("== simulation record details and comparisons (V15)")
    def rec(seq, etype, eid, action, **flags):
        base = dict(seq=seq, entity_type=etype, entity_id=eid, action=action, inspected=True, proposed=False,
                    modified=False, preserved=False, conflicting=False, unresolved=False, fields=[], provenance_path=[],
                    conflict_kind=None)
        base.update(flags)
        return base
    a_rows = [rec(0, "order", OE, "DELETE", proposed=True, modified=True, fields=["total_amount"]),
              rec(1, "order_line", LE, "MODIFY", proposed=True, modified=True, fields=["unit_price", "line_total"])]
    f_rows = [rec(0, "order_line", L1, "MODIFY", proposed=True, modified=True, fields=["line_total", "unit_price"],
                  provenance_path=[f"product:{P}@1", f"order_line:{L1}@0"]),
              rec(1, "order_line", L2, "REVIEW", proposed=True, conflicting=True, unresolved=True,
                  fields=["unit_price"], conflict_kind="SAME_FIELD_CONFLICT"),
              rec(2, "order", O, "NONE", preserved=True)]
    run("INSERT_SIMULATION_RECORDS", orgId=o1, runId=r1, strategy="FULL_RESTORE", rows=json.dumps(a_rows))
    ins = run("INSERT_SIMULATION_RECORDS", orgId=o1, runId=r1, strategy="PROVENANCE_CONFLICT_AWARE", rows=json.dumps(f_rows))
    check("one statement stores a strategy's details", ins.tags == ["INSERT 0 3"], ins.tags)
    page = [r.split("|") for r in run("LIST_SIMULATION_RECORDS", orgId=o1, runId=r1, strategy="PROVENANCE_CONFLICT_AWARE",
                                      category=None, limit=2, offset=0)]
    check("details are paged in priority order", [p[0] for p in page] == ["0", "1"] and page[0][3] == "MODIFY"
          and json.loads(page[0][10]) == ["line_total", "unit_price"] and len(json.loads(page[0][11])) == 2, page)
    conflicting = [r.split("|") for r in run("LIST_SIMULATION_RECORDS", orgId=o1, runId=r1,
                                             strategy="PROVENANCE_CONFLICT_AWARE", category="CONFLICTING", limit=10, offset=0)]
    check("details filter by category", [c[2] for c in conflicting] == [L2] and conflicting[0][12] == "SAME_FIELD_CONFLICT")
    check("stored details are counted per category",
          run("COUNT_STORED_SIMULATION_RECORDS", orgId=o1, runId=r1, strategy="PROVENANCE_CONFLICT_AWARE", category="PRESERVED")
          == ["1"] and run("COUNT_STORED_SIMULATION_RECORDS", orgId=o1, runId=r1, strategy="FULL_RESTORE", category=None)
          == ["2"])
    check("details are per organization",
          run("LIST_SIMULATION_RECORDS", orgId=o2, runId=r1, strategy="FULL_RESTORE", category=None, limit=10, offset=0) == []
          and run("COUNT_STORED_SIMULATION_RECORDS", orgId=o2, runId=r1, strategy="FULL_RESTORE", category=None) == ["0"])
    ok, err = psql(f"UPDATE simulation_result_records SET action = 'NONE' WHERE run_id = '{r1}'", expect_error="immutable")
    check("stored details cannot be edited", ok, err)
    ok, err = psql(f"UPDATE simulation_results SET repaired = 0 WHERE run_id = '{r1}'", expect_error="immutable")
    check("stored results cannot be edited", ok, err)
    ok, err = psql(H.bind("INSERT_SIMULATION_RECORDS", dict(orgId=o1, runId=r1, strategy="PROVENANCE_CONFLICT_AWARE",
                   rows=json.dumps([rec(7, "order", OE, "NONE", modified=True)]))), expect_error="check constraint")
    check("a modified record must say how it was modified", ok, err)
    ok, err = psql(H.bind("INSERT_SIMULATION_RECORDS", dict(orgId=o1, runId=r1, strategy="PROVENANCE_CONFLICT_AWARE",
                   rows=json.dumps([rec(8, "order", OE, "NONE", conflicting=True)]))), expect_error="check constraint")
    check("a conflicting record must name its conflict", ok, err)
    ok, err = psql(H.bind("INSERT_SIMULATION_RECORDS", dict(orgId=o2, runId=r1, strategy="FULL_RESTORE",
                   rows=json.dumps([rec(9, "order", U(), "NONE")]))), expect_error="foreign key")
    check("details cannot be attached to another organization's run", ok, err)
    cols = psql("SELECT string_agg(column_name, ',') FROM information_schema.columns WHERE table_name = 'simulation_result_records'")[0]
    check("details hold identifiers and field names only, no values", "value" not in cols and "state" not in cols, cols)

    diffs = [{"entityType": "order", "entityId": OE, "actionA": "DELETE", "fieldsA": [], "actionB": "NONE", "fieldsB": []}]
    run("INSERT_SIMULATION_COMPARISON", orgId=o1, runId=r1, strategyA="FULL_RESTORE", strategyB="PROVENANCE_CONFLICT_AWARE",
        onlyA=2, onlyB=1, both=1, sameAction=0, totalDifferences=4, storedDifferences=1, truncated=True, sha256="d" * 64,
        differences=json.dumps(diffs))
    listed = [r.split("|") for r in run("LIST_SIMULATION_COMPARISONS", orgId=o1, runId=r1)]
    check("comparisons are listed with exact counts and truncation", listed and listed[0][6:9] == ["4", "1", "t"], listed)
    either = run("FIND_SIMULATION_COMPARISON", orgId=o1, runId=r1, a="PROVENANCE_CONFLICT_AWARE", b="FULL_RESTORE")
    check("a comparison is found in either order, with its stored differences", either and OE in either[0].split("|")[11])
    check("comparisons are per organization", run("LIST_SIMULATION_COMPARISONS", orgId=o2, runId=r1) == []
          and run("FIND_SIMULATION_COMPARISON", orgId=o2, runId=r1, a="FULL_RESTORE", b="PROVENANCE_CONFLICT_AWARE") == [])
    ok, err = psql(H.bind("INSERT_SIMULATION_COMPARISON", dict(orgId=o1, runId=r1, strategyA="NAIVE_FK_ROW",
                   strategyB="FIELD_LEVEL_FK", onlyA=1, onlyB=1, both=0, sameAction=0, totalDifferences=5,
                   storedDifferences=0, truncated=True, sha256="e" * 64, differences="[]")), expect_error="check constraint")
    check("a comparison's total must equal its parts", ok, err)
    ok, err = psql(H.bind("INSERT_SIMULATION_COMPARISON", dict(orgId=o1, runId=r1, strategyA="NAIVE_FK_ROW",
                   strategyB="FIELD_LEVEL_FK", onlyA=1, onlyB=1, both=0, sameAction=0, totalDifferences=2,
                   storedDifferences=2, truncated=False, sha256="e" * 64, differences="[]")), expect_error="check constraint")
    check("a comparison stores exactly the differences it says it stores", ok, err)
    ok, err = psql(f"UPDATE simulation_comparisons SET only_a = 0 WHERE run_id = '{r1}'", expect_error="immutable")
    check("comparisons cannot be edited", ok, err)

    r3 = run("INSERT_SIMULATION_RUN", orgId=o1, incidentId=inc, startedBy=user)[0]
    deleted = run("DELETE_OLD_SIMULATIONS", days=0)
    check("retention deletes finished runs with their results, never a running one",
          deleted.tags == ["DELETE 2"] and psql(f"SELECT count(*) FROM simulation_results WHERE run_id = '{r1}'") == ["0"]
          and run("FIND_SIMULATION_RUN", orgId=o1, id=r3) != [], deleted.tags)
    check("retention removes details and comparisons with their run",
          psql(f"SELECT (SELECT count(*) FROM simulation_result_records WHERE run_id = '{r1}') + "
               f"(SELECT count(*) FROM simulation_comparisons WHERE run_id = '{r1}')") == ["0"])

    # ------------------------------------------------------------------ Milestone 6: integrity monitoring
    print("== integrity: rule evaluators, de-duplicated incidents, clearing, history, versions")

    def findings(name, org=o1, tolerance=0):
        params = dict(orgId=org)
        if ":tolerance" in H.sql[name]:
            params["tolerance"] = tolerance
        if ":limit" in H.sql[name]:
            params["limit"] = 500
        return [r.split("|") for r in run(name, **params)]

    tot = {r[1]: r for r in findings("RULE_ORDER_TOTALS")}
    check("order totals: both orders whose stored total (0) disagrees with their lines are found",
          set(tot) == {O, OE} and '"linesSum": 250.0000' in tot[O][3], tot)
    psql(f"UPDATE orders SET total_amount = 250 WHERE id = '{O}'; UPDATE orders SET total_amount = 1000.004 WHERE id = '{OE}'")
    check("a consistent total is not a finding; a tolerance absorbs rounding",
          [r[1] for r in findings("RULE_ORDER_TOTALS")] == [OE]
          and findings("RULE_ORDER_TOTALS", tolerance=0.01) == [])
    check("invoice totals: an invoice not matching its lines is found",
          [r[1] for r in findings("RULE_INVOICE_TOTALS")] == [INV])
    psql(f"UPDATE invoices SET status = 'VOID', issued_at = now() WHERE id = '{INV}'")
    check("a void invoice is not checked", findings("RULE_INVOICE_TOTALS") == [])
    check("derived values: no finding while every line price equals its recorded source",
          findings("RULE_DERIVED_VALUES") == [])
    psql(f"UPDATE order_lines SET unit_price = 90 WHERE id = '{L1}'")
    dv = findings("RULE_DERIVED_VALUES")
    check("a line price changed without provenance is found, with its source and value",
          [r[1] for r in dv] == [L1] and f"product:{P}@1" in dv[0][3] and '"sourceValue": 100.0' in dv[0][3], dv)
    rp = {r[1]: r for r in findings("RULE_REQUIRED_PROVENANCE")}
    check("required provenance: records written without it are found, one finding per record",
          L5 in rp and L1 not in rp and '"fields": ["unit_price"]' in rp[L5][3], rp)
    check("audit chain intact: no finding", findings("RULE_AUDIT_CHAIN") == [])
    psql(f"""BEGIN; SET LOCAL session_replication_role = replica;
             UPDATE change_events SET reason = 'tampered' WHERE organization_id = '{o2}' AND chain_index = 2; COMMIT;""")
    ac = findings("RULE_AUDIT_CHAIN", org=o2)
    check("audit chain broken: one finding about the organization, at the first broken event",
          len(ac) == 1 and ac[0][0] == "organization" and ac[0][1] == o2 and "#2" in ac[0][2], ac)
    check("other organizations are unaffected by that break", findings("RULE_AUDIT_CHAIN") == [])

    rule = run("INSERT_INTEGRITY_RULE", orgId=o1, code="ORDER_TOTALS", kind="ORDER_TOTAL_MATCHES_LINES",
               name="Order totals match their lines", severity="HIGH", enabled=True, config='{"tolerance": "0.00"}',
               createdBy=user)[0].split("|")
    rid = rule[0]
    run("INSERT_RULE_VERSION", orgId=o1, ruleId=rid, version=0, kind="ORDER_TOTAL_MATCHES_LINES",
        name="Order totals match their lines", severity="HIGH", enabled=True, config='{"tolerance": "0.00"}', changedBy=user)
    ok, err = psql(H.bind("INSERT_INTEGRITY_RULE", dict(orgId=o1, code="ORDER_TOTALS", kind="AUDIT_CHAIN_INTACT",
                   name="dup", severity="LOW", enabled=True, config="{}", createdBy=user)), expect_error="unique")
    check("rule codes are unique per organization", ok, err)
    ok, err = psql(H.bind("INSERT_INTEGRITY_RULE", dict(orgId=o1, code="STOCK", kind="STOCK_LEVELS", name="Stock",
                   severity="LOW", enabled=True, config="{}", createdBy=user)), expect_error="check constraint")
    check("only rule kinds the application can evaluate are accepted", ok, err)
    check("organizations with enabled rules are scheduled", run("ORGANIZATIONS_WITH_RULES") == [o1])

    run1 = run("INSERT_CHECK_RUN", orgId=o1, trigger="MANUAL", startedBy=user)[0]
    ok, err = psql(H.bind("INSERT_CHECK_RUN", dict(orgId=o1, trigger="SCHEDULED", startedBy=user)), expect_error="check")
    check("a scheduled run has no person; a manual one names who started it", ok, err)
    up = dict(orgId=o1, ruleId=rid, ruleVersion=0, subjectType="order", subjectId=OE, severity="HIGH",
              title="Order SO-0: total differs", detail='{"linesSum": 1000}', runId=run1)
    first = run("UPSERT_INCIDENT", **up)[0].split("|")
    again = run("UPSERT_INCIDENT", **{**up, "title": "Order SO-0: total still differs"})[0].split("|")
    check("a finding opens an incident once; the same finding again refreshes it (no duplicate)",
          first[1] == "t" and again == [first[0], "f"]
          and psql(f"SELECT count(*), max(occurrences) FROM integrity_incidents WHERE rule_id = '{rid}'") == ["1|2"])
    run("INSERT_INCIDENT_EVENT", orgId=o1, incidentId=first[0], fromStatus=None, toStatus="OPEN", actorUserId=None,
        note="found by ORDER_TOTALS", runId=run1)
    other = run("UPSERT_INCIDENT", **{**up, "subjectId": O, "title": "Order SO-1"})[0].split("|")[0]
    run2 = run("INSERT_CHECK_RUN", orgId=o1, trigger="SCHEDULED", startedBy=None)[0]
    run("UPSERT_INCIDENT", **{**up, "runId": run2})
    cleared = run("CLEAR_UNSEEN_INCIDENTS", orgId=o1, ruleId=rid, runId=run2)
    check("a finding the next run no longer sees is cleared; one it still sees stays open",
          cleared == [other] and run("FIND_INTEGRITY_INCIDENT", orgId=o1, id=first[0])[0].split("|")[8] == "OPEN")
    run("FINISH_CHECK_RUN", orgId=o1, id=run2, status="COMPLETED", rulesEvaluated=1, findings=1, opened=0, cleared=1,
        failure=None)
    runs = [r.split("|") for r in run("LIST_CHECK_RUNS", orgId=o1)]
    check("check runs are listed newest first with their counts", runs[0][0] == run2 and runs[0][3] == "COMPLETED"
          and runs[0][7] == "1" and runs[1][3] == "RUNNING", runs)
    lst = [r.split("|") for r in run("LIST_INTEGRITY_INCIDENTS", orgId=o1, status=None, severity=None, limit=20, offset=0)]
    check("incidents list with their rule, filters and count", len(lst) == 2 and lst[0][2] == "ORDER_TOTALS"
          and run("COUNT_INTEGRITY_INCIDENTS", orgId=o1, status="OPEN", severity="HIGH") == ["1"]
          and run("LIST_INTEGRITY_INCIDENTS", orgId=o1, status="RESOLVED", severity=None, limit=20, offset=0) == [])
    check("acknowledge with the current version", run("SET_INTEGRITY_INCIDENT_STATUS", orgId=o1, id=first[0], expectedVersion=0,
                                                      status="ACKNOWLEDGED").tags == ["UPDATE 1"])
    check("a stale transition is refused", run("SET_INTEGRITY_INCIDENT_STATUS", orgId=o1, id=first[0], expectedVersion=0,
                                               status="RESOLVED").tags == ["UPDATE 0"])
    run("SET_INTEGRITY_INCIDENT_STATUS", orgId=o1, id=first[0], expectedVersion=1, status="RESOLVED")
    run("INSERT_INCIDENT_EVENT", orgId=o1, incidentId=first[0], fromStatus="ACKNOWLEDGED", toStatus="RESOLVED",
        actorUserId=user, note="corrected through recovery INC-000001", runId=None)
    ev = [r.split("|") for r in run("LIST_INCIDENT_EVENTS", orgId=o1, incidentId=first[0])]
    check("resolution history: every status change with who and why", [(e[0], e[1]) for e in ev] ==
          [("", "OPEN"), ("ACKNOWLEDGED", "RESOLVED")] and ev[1][3] == "X (test)", ev)
    ok, err = psql(f"DELETE FROM integrity_incident_events WHERE incident_id = '{first[0]}'", expect_error="append-only")
    check("incident history is append-only", ok, err)
    reopened = run("UPSERT_INCIDENT", **{**up, "runId": run2})[0].split("|")
    check("after resolution, a recurring finding opens a NEW incident", reopened[1] == "t" and reopened[0] != first[0])
    check("a resolved incident cannot be moved again", run("SET_INTEGRITY_INCIDENT_STATUS", orgId=o1, id=first[0],
                                                           expectedVersion=2, status="ACKNOWLEDGED").tags == ["UPDATE 0"])

    upd = run("UPDATE_INTEGRITY_RULE", orgId=o1, id=rid, expectedVersion=0, name="Order totals (1 paisa)",
              severity="MEDIUM", enabled=True, config='{"tolerance": "0.01"}')[0].split("|")
    run("INSERT_RULE_VERSION", orgId=o1, ruleId=rid, version=1, kind="ORDER_TOTAL_MATCHES_LINES",
        name="Order totals (1 paisa)", severity="MEDIUM", enabled=True, config='{"tolerance": "0.01"}', changedBy=user)
    check("a rule change is optimistic and versioned", upd[7] == "1" and run("UPDATE_INTEGRITY_RULE", orgId=o1, id=rid,
          expectedVersion=0, name="x", severity="LOW", enabled=False, config="{}") == [])
    vers = [r.split("|") for r in run("LIST_RULE_VERSIONS", orgId=o1, ruleId=rid)]
    check("every version of a rule is kept, newest first", [v[0] for v in vers] == ["1", "0"]
          and '"0.01"' in vers[0][5] and vers[0][7] == "X (test)", vers)
    check("rules are listed and found per organization", [r.split("|")[1] for r in run("LIST_INTEGRITY_RULES", orgId=o1)]
          == ["ORDER_TOTALS"] and run("FIND_INTEGRITY_RULE", orgId=o2, id=rid) == []
          and run("FIND_INTEGRITY_RULE", orgId=o1, id=rid) != [])
    check("monitoring wrote no business record: totals are as the test left them",
          psql(f"SELECT total_amount FROM orders WHERE id = '{OE}'") == ["1000.0040"])

    # ------------------------------------------------------------------ coverage
    missing = sorted(atlasx_statement_names() - H.used)
    check("every ATLAS X SQL statement was exercised", not missing, missing)
finally:
    drop_database(DB)

print(f"== {H.passes} passed, {H.failures} failed")
sys.exit(1 if H.failures else 0)
