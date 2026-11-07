#!/usr/bin/env bash
# Builds the benchmark classes with plain javac (no Maven needed) and runs the
# benchmark matrix. Needs: JDK 21, Python 3 with psycopg 3, PostgreSQL 16
# reachable through PG* variables (a superuser: pg_restore --disable-triggers),
# and Jackson 2.x jars in JACKSON_DIR (any 2.15+; Gradle ships them).
#
# Usage: JACKSON_DIR=/opt/gradle-8.14.3/lib scripts/bench/run.sh "1000:1:LIVE 100000:1:OVERBILLED:0" [out.jsonl]
# Each run is orders:seed:scenario[:contention-edits] (default 5; 0 skips the contention copy).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
BUILD="${BUILD_DIR:-$(mktemp -d)}"
J=$(ls "$JACKSON_DIR"/jackson-{core,databind,annotations}-2*.jar | tr '\n' :)
SRC="$ROOT/backend/src"
javac -proc:none -d "$BUILD" -cp "$J" \
  "$SRC"/main/java/com/atlas/provenance/VersionRef.java \
  "$SRC"/main/java/com/atlas/recovery/engine/*.java \
  "$SRC"/test/java/com/atlas/recovery/engine/{SimulatedCommerce,ImpactExperiment,BenchmarkExport,DbGraphRun}.java
export ATLAS_BENCH_CP="$BUILD:$J"
export PGTZ=UTC
OUT="${2:-$ROOT/docs/experiments/raw/phase7-benchmark.jsonl}"
for run in $1; do
  IFS=: read -r orders seed scenario contention <<<"$run"
  echo "== $orders orders, seed $seed, $scenario" >&2
  python3 "$ROOT/scripts/bench/benchmark.py" --orders "$orders" --seed "$seed" --scenario "$scenario" \
    --contention "${contention:-5}" --out "$OUT" >/dev/null
done
