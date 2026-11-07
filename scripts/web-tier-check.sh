#!/usr/bin/env bash
# Tests the production web tier (frontend/deploy/Caddyfile) with the built SPA and a
# stub backend: routing, caching, security headers, the CSP in Chromium, and that a
# client cannot choose the address the backend rate-limits by.
# Needs: caddy (CADDY=path), Python 3, a frontend build (npm run build) and Playwright.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CADDY="${CADDY:-caddy}"
W="$(mktemp -d)"
UP=18081; WEB=18080
cleanup() { kill "${PIDS[@]}" 2>/dev/null || true; rm -rf "$W"; }
PIDS=(); trap cleanup EXIT
printf 'header Content-Security-Policy "%s"\n' "$(node "$ROOT/frontend/e2e/csp-policy.mjs" "$ROOT/frontend/dist")" > "$W/csp.caddy"
sed "s#/etc/caddy/csp.caddy#$W/csp.caddy#; s#root \* /srv#root * $ROOT/frontend/dist#" \
  "$ROOT/frontend/deploy/Caddyfile" > "$W/Caddyfile"
"$CADDY" validate --config "$W/Caddyfile" --adapter caddyfile >/dev/null
python3 -I "$ROOT/scripts/web-tier-check.py" stub "$UP" & PIDS+=($!)
ATLAS_WEB_PORT=$WEB ATLAS_API_UPSTREAM=127.0.0.1:$UP "$CADDY" run --config "$W/Caddyfile" --adapter caddyfile \
  >"$W/caddy.log" 2>&1 & PIDS+=($!)
for _ in $(seq 50); do curl -s -o /dev/null "http://127.0.0.1:$WEB/" && break; sleep 0.2; done
python3 -I "$ROOT/scripts/web-tier-check.py" check "http://127.0.0.1:$WEB"
(cd "$ROOT/frontend" && node e2e/csp-check.mjs --base "http://127.0.0.1:$WEB")
