#!/usr/bin/env bash
# Stress check for the audit hash chain: N concurrent writers append
# change events for the same organization; afterwards the chain must be
# gap-free and verify. Uses pgbench (ships with PostgreSQL).
#
# Usage: PGHOST=localhost PGUSER=atlas scripts/chain-concurrency-check.sh [clients] [txns-per-client]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CLIENTS="${1:-8}"
TXNS="${2:-250}"
DB="atlas_chain_check_$$"
ORG="a0000000-0000-0000-0000-000000000001"
WORK="$(mktemp -d)"

cleanup() { psql -q -d postgres -c "DROP DATABASE IF EXISTS $DB" >/dev/null 2>&1 || true; rm -rf "$WORK"; }
trap cleanup EXIT

psql -q -d postgres -c "CREATE DATABASE $DB" >/dev/null
for f in $(ls "$ROOT"/backend/src/main/resources/db/migration/V*__*.sql | sort -V); do
  psql -q -v ON_ERROR_STOP=1 -d "$DB" --single-transaction -f "$f" >/dev/null
done
psql -q -d "$DB" -c "INSERT INTO organizations (id, slug, name) VALUES ('$ORG', 'chain-test', 'Chain test (synthetic)')"

# Phase A: each transaction records one synthetic entity creation.
cat > "$WORK/append.sql" <<SQL
INSERT INTO change_events (organization_id, txn_id, actor_kind, entity_type, entity_id,
                           entity_version, operation, after_state)
VALUES ('$ORG', gen_random_uuid(), 'SYSTEM', 'synthetic', gen_random_uuid(), 0, 'INSERT',
        jsonb_build_object('client', :client_id));
SQL

echo "== $CLIENTS concurrent writers x $TXNS appends"
pgbench -n -c "$CLIENTS" -j "$CLIENTS" -t "$TXNS" -f "$WORK/append.sql" "$DB" 2>&1 \
  | grep -E "number of (transactions actually processed|failed)|tps =" | sed 's/^/   /'

echo "== $CLIENTS concurrent writers x $TXNS invoice numbers (each also appends a change event)"
cat > "$WORK/number.sql" <<SQL
INSERT INTO change_events (organization_id, txn_id, actor_kind, entity_type, entity_id,
                           entity_version, operation, after_state)
VALUES ('$ORG', gen_random_uuid(), 'SYSTEM', 'invoice_number', gen_random_uuid(), 0, 'INSERT',
        jsonb_build_object('n', next_document_number('$ORG', 'INVOICE')));
SQL
pgbench -n -c "$CLIENTS" -j "$CLIENTS" -t "$TXNS" -f "$WORK/number.sql" "$DB" 2>&1 \
  | grep -E "number of (transactions actually processed|failed)" | sed 's/^/   /'

EXPECTED=$((CLIENTS * TXNS))
read -r COUNT MAXIDX BROKEN <<<"$(psql -At -F ' ' -d "$DB" -c \
  "SELECT count(*), max(chain_index), coalesce(verify_audit_chain('$ORG')::text, 'none')
     FROM change_events WHERE organization_id = '$ORG'")"
read -r NCOUNT NDISTINCT NMIN NMAX <<<"$(psql -At -F ' ' -d "$DB" -c \
  "SELECT count(*), count(DISTINCT after_state->>'n'), min((after_state->>'n')::int), max((after_state->>'n')::int)
     FROM change_events WHERE organization_id = '$ORG' AND entity_type = 'invoice_number'")"

TOTAL=$((EXPECTED * 2))
echo "== Result"
echo "   events: $COUNT (expected $TOTAL), max chain_index: $MAXIDX, first broken link: $BROKEN"
echo "   invoice numbers: $NCOUNT issued, $NDISTINCT distinct, range $NMIN..$NMAX (expected 1..$EXPECTED)"
if [[ "$COUNT" == "$TOTAL" && "$MAXIDX" == "$TOTAL" && "$BROKEN" == "none" ]]; then
  echo "   PASS  chain is gap-free and verifies under concurrent appends"
else
  echo "   FAIL  chain integrity under concurrency"; exit 1
fi
if [[ "$NCOUNT" == "$EXPECTED" && "$NDISTINCT" == "$EXPECTED" && "$NMIN" == "1" && "$NMAX" == "$EXPECTED" ]]; then
  echo "   PASS  invoice numbers are unique and gapless under concurrency"
else
  echo "   FAIL  invoice numbering under concurrency"; exit 1
fi
