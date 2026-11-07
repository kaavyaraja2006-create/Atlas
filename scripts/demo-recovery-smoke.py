#!/usr/bin/env python3
"""
End-to-end recovery of the demo's planted mistake, through the real HTTP API.

Run after scripts/seed-demo.py (it reads that script's --summary-out and
--credentials-out files). The roles are the product's own separation of duties:
  admin    opens the incident, analyses it, generates the plan, executes, verifies
  approver approves (the plan's author cannot)
  ops      must NOT be able to approve (checked: 403)

Checks, each printed PASS/FAIL; exits non-zero if any fails:
  * the analysis finds the faulty product, affected lines/orders and issued invoices
  * the author cannot approve their own plan; a stale fingerprint is refused
  * execution finishes, verification PASSES, the price is back to 649.00
  * the audit chain still verifies, and recovery writes are attributed to RECOVERY

Usage: scripts/demo-recovery-smoke.py --api URL --summary demo-summary.json --credentials demo-credentials.txt
"""
import argparse
import importlib.util
import json
import sys
from decimal import Decimal
from pathlib import Path

spec = importlib.util.spec_from_file_location("seed_demo", Path(__file__).with_name("seed-demo.py"))
seed_demo = importlib.util.module_from_spec(spec)
spec.loader.exec_module(seed_demo)
Api, ApiError = seed_demo.Api, seed_demo.ApiError

failures = 0


def expect(what, ok, detail=""):
    global failures
    print(("PASS  " if ok else "FAIL  ") + what + (f"  ({detail})" if detail and not ok else ""))
    failures += 0 if ok else 1


def denied(call):
    try:
        call()
        return False
    except ApiError as e:
        return "-> 403" in str(e) or "-> 409" in str(e)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--api", required=True)
    ap.add_argument("--summary", required=True)
    ap.add_argument("--credentials", required=True)
    a = ap.parse_args()
    summary = json.loads(Path(a.summary).read_text())
    creds = {}
    for line in Path(a.credentials).read_text().splitlines():
        if line and not line.startswith("#"):
            key, email, password = line.split()
            creds[key] = (email, password)
    org = summary["organization"]["id"]
    mistake = summary["plantedMistake"]

    def as_user(key):
        api = Api(a.api)
        api.login(*creds[key])
        api.org = org
        return api

    admin, approver, ops = as_user("admin"), as_user("approver"), as_user("ops")

    incident = admin.request("POST", "/api/v1/recovery/incidents", {
        "changeEventId": mistake["changeEventId"],
        "title": "Water bottle priced 6,490 instead of 649",
        "reason": "Extra zero typed in the monsoon price update (synthetic demo data)"}, expect=(201,))
    impact = admin.request("POST", f"/api/v1/recovery/incidents/{incident['id']}/impact")
    counts = impact["counts"]
    expect("analysis finds the origin", counts.get("ORIGIN") == 1, counts)
    expect("analysis finds directly and indirectly affected records",
           counts.get("DIRECTLY_AFFECTED", 0) > 0 and counts.get("INDIRECTLY_AFFECTED", 0) > 0, counts)
    expect("issued invoices are classified for compensation", counts.get("NON_REVERSIBLE", 0) > 0, counts)

    plan = admin.request("POST", f"/api/v1/recovery/incidents/{incident['id']}/plans", expect=(201,))
    expect("plan has automatic steps", plan["stepCount"] > plan["manualStepCount"], plan["stepCount"])
    url = f"/api/v1/recovery/plans/{plan['id']}"

    expect("the plan's author cannot approve it",
           denied(lambda: admin.request("POST", url + "/approval", {"fingerprint": plan["fingerprint"]})))
    expect("a user without recovery.approve cannot approve",
           denied(lambda: ops.request("POST", url + "/approval", {"fingerprint": plan["fingerprint"]})))
    expect("a wrong fingerprint is refused",
           denied(lambda: approver.request("POST", url + "/approval", {"fingerprint": "0" * 64})))
    decision = approver.request("POST", url + "/approval",
                                {"fingerprint": plan["fingerprint"], "comment": "Checked the diff (demo)"},
                                expect=(200, 201))
    expect("a separate approver approves", decision["approved"] is True)

    report = admin.request("POST", url + "/execution", expect=(200, 201))
    statuses = {s["status"] for s in report["steps"]}
    expect("execution finishes", report["status"] == "EXECUTED", report["status"])
    expect("no step failed or was blocked", not statuses & {"FAILED", "BLOCKED"}, statuses)

    verification = admin.request("POST", url + "/verifications", expect=(200, 201))
    expect("verification passes", verification["outcome"] == "PASSED",
           [c for c in verification["checks"] if not c["passed"]][:3])

    product = admin.request("GET", f"/api/v1/products/{mistake['productId']}")
    expect("the price is back to 649.00", Decimal(str(product["unitPrice"])) == Decimal("649"), product["unitPrice"])

    chain = admin.request("POST", "/api/v1/audit/verify")
    expect("the audit chain still verifies", chain.get("intact") is True, chain)
    events = admin.request("GET", f"/api/v1/audit/events?entityType=product&entityId={mistake['productId']}&size=5")
    expect("the correction is attributed to RECOVERY, not a person",
           events["items"][0]["actorKind"] == "RECOVERY", events["items"][0]["actorKind"])
    expect("the faulty version is still in the history (nothing erased)",
           any(e["id"] == mistake["changeEventId"] for e in events["items"]))

    print(f"== {'all checks passed' if failures == 0 else f'{failures} FAILED'}")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
