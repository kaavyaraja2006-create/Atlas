#!/usr/bin/env python3
"""Summarise the ATLAS X Milestone 8 experiment (synthetic data).

Reads the JSON lines that AtlasXExperiment prints and writes Markdown tables:
per strategy, totals over every run, and the oracle cross-checks. Only numbers
that are in the input appear in the output; nothing is estimated.

    python3 scripts/bench/atlasx_report.py docs/experiments/atlasx/sweep.jsonl [scale.jsonl]
"""
import json
import statistics
import sys
from collections import defaultdict

LETTERS = "ABCDEF"
NAMES = {
    "A": "Point-in-time restore", "B": "Foreign keys, whole rows", "C": "Provenance, whole rows",
    "D": "Foreign keys, single fields", "E": "Provenance, conflicts ignored", "F": "PASCAR (provenance + conflicts)",
}


def load(path):
    with open(path, encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def main():
    runs = load(sys.argv[1])
    out = []
    sizes = sorted({r["orders"] for r in runs})
    seeds = sorted({r["seed"] for r in runs})
    scenarios = sorted({r["scenario"] for r in runs})
    out.append(f"Runs: {len(runs)} (orders {sizes}, seeds {seeds[0]}–{seeds[-1]}, scenarios {', '.join(scenarios)}); "
               f"note rate {runs[0]['noteRate']}, conflict rate {runs[0]['conflictRate']}. Dataset: synthetic.\n")

    tot = defaultdict(lambda: defaultdict(int))
    feasible = defaultdict(int)
    for r in runs:
        for s in r["strategies"]:
            t = tot[s["letter"]]
            for k in ("faulty", "repaired", "modified", "deleted", "collateral", "legitimateOverwritten",
                      "conflictsSurfaced", "conflictsIgnored", "invariantViolations", "fieldWrites", "compensations",
                      "manualSteps"):
                t[k] += s[k]
            feasible[s["letter"]] += 1 if s["feasible"] else 0
    out.append("| Strategy | Repaired / faulty | Collateral records | Later values lost | Deleted | Rule breaks "
               "| Conflicts surfaced / ignored | Field writes | Manual steps | Meets every constraint |")
    out.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|")
    for L in LETTERS:
        t = tot[L]
        rate = t["repaired"] / t["faulty"] if t["faulty"] else 0
        out.append(f"| {L}. {NAMES[L]} | {t['repaired']} / {t['faulty']} ({rate:.1%}) | {t['collateral']} "
                   f"| {t['legitimateOverwritten']} | {t['deleted']} | {t['invariantViolations']} "
                   f"| {t['conflictsSurfaced']} / {t['conflictsIgnored']} | {t['fieldWrites']} | {t['manualSteps']} "
                   f"| {feasible[L]} of {len(runs)} runs |")
    out.append("")

    rec = defaultdict(int)
    for r in runs:
        rec[r["recommended"]] += 1
    out.append("Recommended by the optimizer: " + ", ".join(f"{k}: {v}" for k, v in sorted(rec.items(), key=str)) + "\n")

    # Oracle cross-checks (oracle counts come from the generator, never from provenance).
    injected = sum(r["oracle"]["conflicts"] for r in runs)
    notes = sum(r["oracle"]["legitimateUpdates"] for r in runs)
    notes_faulty = sum(r["oracle"]["legitimateOnFaultyRecords"] for r in runs)
    f = [next(s for s in r["strategies"] if s["letter"] == "F") for r in runs]
    target_ok = sum(1 for r, s in zip(runs, f)
                    if r["scenario"] == "OVERWRITTEN" or s["faulty"] == r["oracle"]["faultyRecords"] - r["oracle"]["conflicts"])
    out.append("| Oracle cross-check (all runs) | Value |")
    out.append("|---|---:|")
    out.append(f"| Later delivery notes injected (on faulty records) | {notes} ({notes_faulty}) |")
    out.append(f"| Same-field re-prices injected (conflicts) | {injected} |")
    out.append(f"| F: conflicts surfaced (incl. origin conflicts in OVERWRITTEN) | {sum(s['conflictsSurfaced'] for s in f)} |")
    out.append(f"| F: later values lost | {sum(s['legitimateOverwritten'] for s in f)} |")
    out.append(f"| Runs where F's repair target = oracle faulty − conflicts (LIVE, OVERBILLED) | "
               f"{target_ok - sum(1 for r in runs if r['scenario'] == 'OVERWRITTEN')} of "
               f"{sum(1 for r in runs if r['scenario'] != 'OVERWRITTEN')} |")
    out.append("")

    if len(sys.argv) > 2:
        scale = load(sys.argv[2])
        out.append("| Orders | Records | Versions | Edges | F simulate (ms) | All six (ms) |")
        out.append("|---:|---:|---:|---:|---:|---:|")
        for r in sorted(scale + runs, key=lambda x: (x["orders"], x["seed"], x["scenario"])):
            if r["seed"] != 1 or r["scenario"] != "LIVE":
                continue
            fs = next(s for s in r["strategies"] if s["letter"] == "F")
            out.append(f"| {r['orders']} | {r['records']} | {r['versions']} | {r['edges']} | {fs['micros'] / 1000:.0f} "
                       f"| {sum(s['micros'] for s in r['strategies']) / 1000:.0f} |")
        out.append("")
        out.append("Timings are wall-clock on one machine and one JVM run each; they show the order of magnitude only.")
    print("\n".join(out))


if __name__ == "__main__":
    main()
