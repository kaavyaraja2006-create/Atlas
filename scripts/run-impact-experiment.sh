#!/usr/bin/env bash
# Reproduces the Phase 5 impact-analysis experiment (synthetic, seeded).
# Writes one JSON line per (orders, seed, scenario) to the given file.
#
# Usage: scripts/run-impact-experiment.sh [out.jsonl]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$ROOT/docs/experiments/raw/phase5-impact-analysis.jsonl}"
cd "$ROOT/backend"
mvn -q -B -DskipTests test-compile
CP="target/test-classes:target/classes"
: > "$OUT"
for n in 1000 10000 100000; do
  for seed in 1 2 3; do
    for scenario in LIVE OVERWRITTEN; do
      java -Xmx5g -cp "$CP" com.atlas.recovery.engine.ImpactExperiment "$n" "$seed" "$scenario" >> "$OUT"
    done
  done
done
echo "wrote $(wc -l < "$OUT") results to $OUT"
