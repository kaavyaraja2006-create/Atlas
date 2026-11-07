#!/usr/bin/env bash
# Validates the observability configuration with promtool: the Prometheus config, the
# alert rules and their unit tests, and the syntax of every Grafana panel query.
# Needs promtool (PROMTOOL=path) and Python 3 with PyYAML.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROMTOOL="${PROMTOOL:-promtool}"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
cp "$ROOT/ops/prometheus/alerts.yml" "$T/"; echo placeholder > "$T/metrics-password"
sed "s#/etc/prometheus/#$T/#g" "$ROOT/ops/prometheus/prometheus.yml" > "$T/prometheus.yml"
"$PROMTOOL" check config "$T/prometheus.yml"
"$PROMTOOL" check rules "$ROOT/ops/prometheus/alerts.yml"
(cd "$ROOT/ops/prometheus" && "$PROMTOOL" test rules alerts.test.yml)
python3 -I - "$ROOT/ops/grafana/dashboards" "$T/panels.yml" <<'PY'
import json, sys, pathlib, yaml
rules = []
for f in sorted(pathlib.Path(sys.argv[1]).glob("*.json")):
    for p in json.loads(f.read_text())["panels"]:
        for t in p["targets"]:
            rules.append({"record": f"dashboard:{f.stem.replace('-', '_')}:{p['id']}_{t['refId']}", "expr": t["expr"]})
yaml.safe_dump({"groups": [{"name": "dashboards", "rules": rules}]}, open(sys.argv[2], "w"))
print(f"{len(rules)} dashboard queries")
PY
"$PROMTOOL" check rules "$T/panels.yml"
