#!/usr/bin/env python3
"""Turns docs/experiments/raw/phase7-benchmark.jsonl into the report's tables (markdown on stdout).

Means over seeds, with min–max where the seeds differ. Every number comes from the raw file.
"""
import json
import sys
from collections import defaultdict

path = sys.argv[1] if len(sys.argv) > 1 else "docs/experiments/raw/phase7-benchmark.jsonl"
runs = [json.loads(line) for line in open(path)]
groups = defaultdict(list)
for r in runs:
    groups[(r["orders"], r["scenario"])].append(r)
ORDER = ["LIVE", "OVERWRITTEN", "OVERBILLED"]
keys = sorted(groups, key=lambda k: (k[0], ORDER.index(k[1])))


def get(r, path):
    for p in path.split("."):
        r = r[p]
    return r


def agg(rs, path, fmt="{:.0f}", scale=1.0):
    vals = [get(r, path) * scale for r in rs]
    m = sum(vals) / len(vals)
    lo, hi = min(vals), max(vals)
    s = fmt.format(m)
    if len(vals) > 1 and fmt.format(lo) != fmt.format(hi):
        s += f" ({fmt.format(lo)}–{fmt.format(hi)})"
    return s


def n(rs):
    return f"{len(rs)}"


print(f"Runs: {len(runs)}\n")
print("### Selection quality (records chosen for change vs the oracle)\n")
print("| Orders | Scenario | Seeds | Truth | PASCAR P / R | Record-level P / R | Naive FK P (extra) | Full restore P (rewound needlessly) |")
print("|---|---|---|---|---|---|---|---|")
for k in keys:
    rs = groups[k]
    print(f"| {k[0]:,} | {k[1]} | {n(rs)} | {agg(rs, 'truth')} "
          f"| {agg(rs, 'pascar.precision', '{:.3f}')} / {agg(rs, 'pascar.recall', '{:.3f}')} "
          f"| {agg(rs, 'baselines.RECORD_LEVEL.precision', '{:.3f}')} / {agg(rs, 'baselines.RECORD_LEVEL.recall', '{:.3f}')} "
          f"| {agg(rs, 'baselines.NAIVE_FK.precision', '{:.3f}')} ({agg(rs, 'baselines.NAIVE_FK.unaffectedTouched')}) "
          f"| {agg(rs, 'baselines.FULL_RESTORE.precision', '{:.3f}')} ({agg(rs, 'baselines.FULL_RESTORE.unaffectedTouched')}) |")

print("\n### Time (ms, single run each per seed; 2-vCPU sandbox VM)\n")
print("| Orders | Scenario | DB size MB | PASCAR load SQL (edges + versions) | Analysis | Planning | Naive FK query | Record-level query | pg_dump | pg_restore |")
print("|---|---|---|---|---|---|---|---|---|---|")
for k in keys:
    rs = groups[k]
    for r in rs:
        r["_load"] = r["pascar"]["dbLoadMs"]["LOAD_INCIDENT_EDGES"] + r["pascar"]["dbLoadMs"]["LOAD_INCIDENT_VERSIONS"]
    print(f"| {k[0]:,} | {k[1]} | {agg(rs, 'dbSizeMB')} | {agg(rs, '_load')} | {agg(rs, 'pascar.analysisMs')} "
          f"| {agg(rs, 'pascar.planMs')} | {agg(rs, 'baselines.NAIVE_FK.queryMs')} | {agg(rs, 'baselines.RECORD_LEVEL.queryMs')} "
          f"| {agg(rs, 'baselines.FULL_RESTORE.pgDumpMs')} | {agg(rs, 'baselines.FULL_RESTORE.pgRestoreMs')} |")

print("\n### Execution and verification (Python replay of the workflow, the application's SQL)\n")
print("| Orders | Scenario | Steps | Written | Credit notes | Manual | Written outside truth | Step ms p50 / p95 | Total s | Totals violated (DB-wide) | Scope ok | Chain intact | Re-plan automatic steps | Outcome |")
print("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
for k in keys:
    rs = groups[k]
    for r in rs:
        v = r["verification"]
        t = v["totalsAllOrdersAndDrafts"]
        r["_viol"] = t["orderViolations"] + t["draftInvoiceViolations"] + t["overCredited"] + v["fieldEquals"]["failed"]
        r["_scope"] = v["scope"]["unexpected"] + v["scope"]["missing"]
        r["_manual"] = r["execution"]["outcomes"].get("AWAITING_ACKNOWLEDGEMENT", 0)
    outcomes = sorted({r["verification"]["outcome"] for r in rs})
    print(f"| {k[0]:,} | {k[1]} | {agg(rs, 'execution.steps')} | {agg(rs, 'execution.recordsWritten')} "
          f"| {agg(rs, 'execution.creditNotes')} | {agg(rs, '_manual')} | {agg(rs, 'execution.writtenOutsideTruth')} "
          f"| {agg(rs, 'execution.stepMsP50', '{:.1f}')} / {agg(rs, 'execution.stepMsP95', '{:.1f}')} "
          f"| {agg(rs, 'execution.ms', '{:.1f}', 0.001)} | {agg(rs, '_viol')} | {'yes' if all(r['_scope'] == 0 for r in rs) else 'NO'} "
          f"| {'yes' if all(r['verification']['auditChainIntact'] for r in rs) else 'NO'} "
          f"| {agg(rs, 'verification.replan.automaticSteps')} | {', '.join(outcomes)} |")

print("\n### Contention: a person edits 5 unreached lines after step 1 commits\n")
print("| Orders | Scenario | Seeds | Conflicts detected | Updates lost | Steps blocked | Fingerprint changed | Re-plan: conflicted / automatic left |")
print("|---|---|---|---|---|---|---|---|")
for k in keys:
    rs = [r for r in groups[k] if "contention" in r]
    if not rs:
        continue
    print(f"| {k[0]:,} | {k[1]} | {n(rs)} | {agg(rs, 'contention.conflictsDetected')} / {agg(rs, 'contention.editedLines')} "
          f"| {agg(rs, 'contention.updatesLost')} | {agg(rs, 'contention.blocked')} "
          f"| {'all' if all(r['contention']['fingerprintChanged'] for r in rs) else 'NOT ALL'} "
          f"| {agg(rs, 'contention.replanConflicted')} / {agg(rs, 'contention.replanAutomaticSteps')} |")

print("\n### Value accuracy\n")
tc = sum(r["pascar"]["totalsChecked"] for r in runs)
tm = sum(r["pascar"]["totalsMismatched"] for r in runs)
print(f"Planned totals and compensation amounts checked against the oracle's arithmetic: {tc:,} checked, {tm} mismatched.")
