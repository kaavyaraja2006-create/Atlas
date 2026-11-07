#!/usr/bin/env python3
"""
SQL harness for Graph Intelligence: runs its SQL (extracted from the Java source) on a
throwaway PostgreSQL 16 database built from the real Flyway migrations. Checks the schema
introspection queries against the real ATLAS schema, the platform audit trail, schema
snapshots and the platform-admin access check. Every graph statement must be exercised.

With --dump-catalog FILE it also writes the introspection rows as JSON, so the Java
relationship classifier can be run on the real catalog (SchemaCatalogCheck).
"""
import json
import sys
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from sqlharness import GRAPH_FILES, Harness, create_database, drop_database, extract, graph_statement_names  # noqa: E402

DB = "atlas_graph_harness"
H = Harness(DB, extract())
run, psql, check = H.run, H.psql, H.check
U = lambda: str(uuid.uuid4())  # noqa: E731
dump = sys.argv[sys.argv.index("--dump-catalog") + 1] if "--dump-catalog" in sys.argv else None

create_database(DB)
try:
    print(f"== {len(graph_statement_names())} graph statements in {len(GRAPH_FILES)} files")

    # ------------------------------------------------------------------ schema introspection
    print("== schema introspection on the real migrations")
    check("without Flyway's table, no version is claimed", run("HAS_FLYWAY") == ["f"])
    psql("CREATE TABLE flyway_schema_history (installed_rank int PRIMARY KEY, version varchar(50), success boolean)")
    psql("INSERT INTO flyway_schema_history VALUES (1, '13', true), (2, '14', true), (3, '15', false), (4, NULL, true)")
    check("Flyway's table is detected", run("HAS_FLYWAY") == ["t"])
    check("the version is the latest SUCCESSFUL versioned migration", run("FLYWAY_VERSION") == ["14"])

    tables = dict(r.split("|") for r in run("SCHEMA_TABLES"))
    expected_count = int(psql("SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                              "WHERE n.nspname = 'public' AND c.relkind IN ('r','p','v') "
                              "AND c.relname <> 'flyway_schema_history'")[0])
    check("every table and view of the schema is found, Flyway's own excluded",
          len(tables) == expected_count and "flyway_schema_history" not in tables and expected_count >= 38,
          (len(tables), expected_count))
    check("views are told apart from tables", tables.get("field_changes") == "VIEW" and tables.get("orders") == "TABLE")

    cols = [r.split("|") for r in run("SCHEMA_COLUMNS")]
    note = [c for c in cols if c[0] == "orders" and c[1] == "delivery_note"]
    check("columns carry their type and nullability", note and note[0][3] == "character varying(500)" and note[0][4] == "t",
          note)
    pos = [int(c[2]) for c in cols if c[0] == "orders"]
    check("columns come in table order", pos == sorted(pos) and pos[0] == 1)

    cons = [r.split("|") for r in run("SCHEMA_CONSTRAINTS")]
    by = {(c[0], c[1]): c for c in cons}
    prov = [c for c in cons if c[0] == "provenance_edges" and c[2] == "FOREIGN_KEY"]
    check("deferrable foreign keys are reported as such", len(prov) == 2 and all(c[7] == "t" for c in prov), prov)
    check("composite foreign-key columns keep their order",
          any(c[0] == "order_lines" and c[2] == "FOREIGN_KEY" and json.loads(c[3]) == ["organization_id", "order_id"]
              and c[5] == "orders" and json.loads(c[6]) == ["organization_id", "id"] for c in cons))
    check("every constraint is validated today (no NOT VALID)", all(c[8] == "t" for c in cons))
    fk_count = int(psql("SELECT count(*) FROM pg_constraint k JOIN pg_namespace n ON n.oid = k.connamespace "
                        "WHERE n.nspname = 'public' AND k.contype = 'f'")[0])
    check("every foreign key is found", sum(1 for c in cons if c[2] == "FOREIGN_KEY") == fk_count, fk_count)

    idx = [r.split("|") for r in run("SCHEMA_INDEXES")]
    partial = [i for i in idx if i[1] == "ux_invoices_active_per_order"]
    check("partial unique indexes keep their predicate", partial and partial[0][3] == "t" and "VOID" in partial[0][5],
          partial)
    check("primary-key indexes are flagged", any(i[1] == "orders_pkey" and i[4] == "t" for i in idx))

    trg = [r.split("|") for r in run("SCHEMA_TRIGGERS")]
    pa = [t for t in trg if t[1] == "platform_audit_events_append_only"]
    check("triggers report timing, events and function name only",
          pa and pa[0][2] == "BEFORE" and json.loads(pa[0][3]) == ["UPDATE", "DELETE"] and pa[0][4] == "graph_append_only",
          pa)

    if dump:
        Path(dump).write_text(json.dumps({"tables": run("SCHEMA_TABLES"), "columns": run("SCHEMA_COLUMNS"),
                                          "constraints": run("SCHEMA_CONSTRAINTS"), "indexes": run("SCHEMA_INDEXES"),
                                          "triggers": run("SCHEMA_TRIGGERS")}))
        print(f"   wrote catalog rows to {dump}")

    # ------------------------------------------------------------------ snapshots
    print("== schema snapshots")
    h1, h2 = "a" * 64, "b" * 64
    s1 = run("INSERT_SCHEMA_SNAPSHOT", flywayVersion="13", sha256=h1, tableCount=37, relationshipCount=60,
             catalog='{"tables": [], "relationships": []}')
    again = run("INSERT_SCHEMA_SNAPSHOT", flywayVersion="13", sha256=h1, tableCount=37, relationshipCount=60,
                catalog='{"tables": [], "relationships": []}')
    check("an identical structure is stored once", len(s1) == 1 and again == [])
    psql("SELECT pg_sleep(0.01)")
    s2 = run("INSERT_SCHEMA_SNAPSHOT", flywayVersion="14", sha256=h2, tableCount=39, relationshipCount=64,
             catalog='{"tables": [{"name": "x"}], "relationships": []}')
    listed = [r.split("|") for r in run("LIST_SCHEMA_SNAPSHOTS")]
    check("versions are listed newest first", [r[1] for r in listed] == ["14", "13"], listed)
    check("the latest snapshot is the newest", run("LATEST_SCHEMA_SNAPSHOT")[0].split("|")[0] == s2[0])
    check("a snapshot's catalog is read back", '"x"' in run("FIND_SCHEMA_SNAPSHOT", id=s2[0])[0]
          and run("FIND_SCHEMA_SNAPSHOT", id=U()) == [])
    ok, err = psql(f"UPDATE schema_snapshots SET flyway_version = 'x' WHERE id = '{s1[0]}'", expect_error="append-only")
    check("snapshots are append-only", ok, err)
    ok, err = psql(f"INSERT INTO schema_snapshots (flyway_version, sha256, table_count, relationship_count, catalog) "
                   f"VALUES ('1', 'NOT-A-HASH', 0, 0, '{{}}')", expect_error="check constraint")
    check("a snapshot hash must be 64 lowercase hex digits", ok, err)

    # ------------------------------------------------------------------ access and audit
    print("== platform-admin access check and platform audit")
    admin = run("INSERT_USER", email="root@atlas.test", displayName="Root (test)", passwordHash="{bcrypt}x")[0].split("|")[0]
    plain = run("INSERT_USER", email="plain@atlas.test", displayName="Plain (test)", passwordHash="{bcrypt}x")[0].split("|")[0]
    psql(f"UPDATE users SET platform_admin = true WHERE id = '{admin}'")
    check("an active platform admin may inspect the schema", run("IS_ACTIVE_PLATFORM_ADMIN", userId=admin) == ["t"])
    check("an ordinary user may not", run("IS_ACTIVE_PLATFORM_ADMIN", userId=plain) == ["f"])
    check("an unknown user may not", run("IS_ACTIVE_PLATFORM_ADMIN", userId=U()) == ["f"])
    psql(f"UPDATE users SET status = 'DISABLED' WHERE id = '{admin}'")
    check("a disabled platform admin may not", run("IS_ACTIVE_PLATFORM_ADMIN", userId=admin) == ["f"])
    psql(f"UPDATE users SET status = 'ACTIVE' WHERE id = '{admin}'")

    e1 = run("INSERT_PLATFORM_AUDIT_EVENT", actor=plain, action="SCHEMA_ACCESS_DENIED", target="view schema",
             detail='{"rule": "platform administrators only"}', correlationId="req-1")
    e2 = run("INSERT_PLATFORM_AUDIT_EVENT", actor=admin, action="SCHEMA_EXPORT", target="live",
             detail='{"format": "SVG", "tables": 38}', correlationId=None)
    check("denials and exports are recorded", len(e1) == 1 and len(e2) == 1)
    ok, err = psql("INSERT INTO platform_audit_events (actor_user_id, action) "
                   f"VALUES ('{admin}', 'SOMETHING_ELSE')", expect_error="check constraint")
    check("only known platform actions are accepted", ok, err)
    ok, err = psql(f"INSERT INTO platform_audit_events (actor_user_id, action) VALUES ('{U()}', 'SCHEMA_VIEW')",
                   expect_error="foreign key")
    check("the actor must be a real user", ok, err)
    ok, err = psql(f"DELETE FROM platform_audit_events WHERE id = '{e1[0]}'", expect_error="append-only")
    check("platform audit events cannot be deleted", ok, err)
    ok, err = psql(f"UPDATE platform_audit_events SET action = 'SCHEMA_VIEW' WHERE id = '{e1[0]}'",
                   expect_error="append-only")
    check("platform audit events cannot be edited", ok, err)

    # ------------------------------------------------------------------ coverage
    missing = sorted(graph_statement_names() - H.used)
    check("every graph SQL statement was exercised", not missing, missing)
finally:
    drop_database(DB)

print(f"== {H.passes} passed, {H.failures} failed")
sys.exit(1 if H.failures else 0)
