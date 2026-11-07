#!/usr/bin/env bash
# End-to-end audit anchoring against PostgreSQL without Spring (see AnchorEndToEnd).
# Needs JDK 21, psql and PG* variables for a superuser (the test plays a superuser attack).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="$(mktemp -d)"
STUBS="$(mktemp -d)"
trap 'rm -rf "$BUILD" "$STUBS"' EXIT
# AnchorRepository is read only for its SQL constants; a two-line stub stands in for
# Spring's JdbcClient so the class can load without Spring on the classpath.
mkdir -p "$STUBS/org/springframework/jdbc/core/simple" "$STUBS/org/springframework/stereotype"
cat > "$STUBS/org/springframework/jdbc/core/simple/JdbcClient.java" <<'J'
package org.springframework.jdbc.core.simple; public interface JdbcClient { StatementSpec sql(String s);
  interface StatementSpec { StatementSpec param(String n, Object v); <T> MappedQuerySpec<T> query(Class<T> c);
    <T> MappedQuerySpec<T> query(org.springframework.jdbc.core.RowMapper<T> m); int update(); }
  interface MappedQuerySpec<T> { java.util.List<T> list(); java.util.Optional<T> optional(); T single(); } }
J
mkdir -p "$STUBS/org/springframework/jdbc/core"
echo 'package org.springframework.jdbc.core; public interface RowMapper<T> { T mapRow(java.sql.ResultSet rs, int n) throws java.sql.SQLException; }' \
  > "$STUBS/org/springframework/jdbc/core/RowMapper.java"
echo 'package org.springframework.stereotype; public @interface Repository {}' > "$STUBS/org/springframework/stereotype/Repository.java"
SRC="$ROOT/backend/src"
javac -proc:none -d "$BUILD" -sourcepath "$STUBS:$SRC/main/java" \
  "$SRC"/main/java/com/atlas/audit/anchor/*.java "$SRC/main/java/com/atlas/audit/AnchorRepository.java" \
  "$SRC/test/java/com/atlas/audit/anchor/AnchorEndToEnd.java"
java -cp "$BUILD" com.atlas.audit.anchor.AnchorEndToEnd "$ROOT/backend/src/main/resources/db/migration"
