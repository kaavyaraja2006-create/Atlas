#!/usr/bin/env bash
# Applies every Flyway migration to a fresh, throwaway database with plain
# psql, then runs the schema guarantee assertions. Needs only a reachable
# PostgreSQL 16+ server (no JVM, no Docker).
#
# Usage:  PGHOST=localhost PGPORT=5432 PGUSER=atlas scripts/verify-schema.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MIGRATIONS="$ROOT/backend/src/main/resources/db/migration"
ASSERTIONS="$ROOT/backend/src/test/resources/db/schema-assertions.sql"
DB="atlas_schema_check_$$"

cleanup() { psql -q -d postgres -c "DROP DATABASE IF EXISTS $DB" >/dev/null 2>&1 || true; }
trap cleanup EXIT

psql -q -d postgres -c "CREATE DATABASE $DB" >/dev/null

echo "== Applying migrations"
for f in $(ls "$MIGRATIONS"/V*__*.sql | sort -V); do
  echo "   $(basename "$f")"
  psql -q -v ON_ERROR_STOP=1 -d "$DB" --single-transaction -f "$f" >/dev/null
done

echo "== Schema summary"
psql -At -d "$DB" -c "SELECT '   tables: ' || count(*) FROM information_schema.tables WHERE table_schema = 'public'"
psql -At -d "$DB" -c "SELECT '   triggers: ' || count(*) FROM pg_trigger WHERE NOT tgisinternal"

echo "== Running schema assertions"
psql -q -t -v ON_ERROR_STOP=1 -d "$DB" -f "$ASSERTIONS" 2>&1 | sed -E "s/^psql:[^ ]+ (NOTICE|ERROR):  /   /" | grep -v "^\s*$" | grep -v "truncate cascades"
