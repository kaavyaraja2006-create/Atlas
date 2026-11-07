package com.atlas.graph.schema;

import com.atlas.common.json.Json;
import com.atlas.graph.schema.SchemaModel.Column;
import com.atlas.graph.schema.SchemaModel.Constraint;
import com.atlas.graph.schema.SchemaModel.Index;
import com.atlas.graph.schema.SchemaModel.Table;
import com.atlas.graph.schema.SchemaModel.Trigger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Reads the application schema from PostgreSQL's catalog (the schema the application's
 * connection uses, {@code current_schema()}). Flyway's own history table is excluded.
 * Nothing about the schema is hardcoded: every table, column, key, index and trigger comes
 * from these queries. Trigger function bodies and check-constraint internals beyond
 * {@code pg_get_constraintdef} are not read.
 */
@Component
public class SchemaIntrospector {

    static final String SCHEMA_TABLES = """
            SELECT c.relname AS table_name, CASE WHEN c.relkind = 'v' THEN 'VIEW' ELSE 'TABLE' END AS kind
              FROM pg_class c
              JOIN pg_namespace n ON n.oid = c.relnamespace
             WHERE n.nspname = current_schema() AND c.relkind IN ('r', 'p', 'v')
               AND c.relname NOT LIKE 'flyway\\_%'
             ORDER BY c.relname
            """;

    static final String SCHEMA_COLUMNS = """
            SELECT c.relname AS table_name, a.attname AS column_name, a.attnum AS position,
                   format_type(a.atttypid, a.atttypmod) AS data_type, NOT a.attnotnull AS nullable,
                   pg_get_expr(d.adbin, d.adrelid) AS default_value
              FROM pg_attribute a
              JOIN pg_class c ON c.oid = a.attrelid
              JOIN pg_namespace n ON n.oid = c.relnamespace
              LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
             WHERE n.nspname = current_schema() AND c.relkind IN ('r', 'p', 'v')
               AND c.relname NOT LIKE 'flyway\\_%' AND a.attnum > 0 AND NOT a.attisdropped
             ORDER BY c.relname, a.attnum
            """;

    static final String SCHEMA_CONSTRAINTS = """
            SELECT c.relname AS table_name, k.conname AS name,
                   CASE k.contype WHEN 'p' THEN 'PRIMARY_KEY' WHEN 'f' THEN 'FOREIGN_KEY' WHEN 'u' THEN 'UNIQUE'
                                  WHEN 'c' THEN 'CHECK' WHEN 'x' THEN 'EXCLUSION' ELSE k.contype::text END AS kind,
                   CAST(COALESCE((SELECT json_agg(a.attname ORDER BY x.ord)
                                    FROM unnest(k.conkey) WITH ORDINALITY x(attnum, ord)
                                    JOIN pg_attribute a ON a.attrelid = k.conrelid AND a.attnum = x.attnum), '[]') AS text)
                       AS columns,
                   pg_get_constraintdef(k.oid) AS definition,
                   r.relname AS references_table,
                   CAST((SELECT json_agg(a.attname ORDER BY x.ord)
                           FROM unnest(k.confkey) WITH ORDINALITY x(attnum, ord)
                           JOIN pg_attribute a ON a.attrelid = k.confrelid AND a.attnum = x.attnum) AS text)
                       AS referenced_columns,
                   k.condeferrable AS deferrable, k.convalidated AS validated
              FROM pg_constraint k
              JOIN pg_class c ON c.oid = k.conrelid
              JOIN pg_namespace n ON n.oid = c.relnamespace
              LEFT JOIN pg_class r ON r.oid = k.confrelid
             WHERE n.nspname = current_schema() AND c.relname NOT LIKE 'flyway\\_%'
               AND k.contype IN ('p', 'f', 'u', 'c', 'x')
             ORDER BY c.relname, k.conname
            """;

    static final String SCHEMA_INDEXES = """
            SELECT c.relname AS table_name, i.relname AS name,
                   CAST(COALESCE((SELECT json_agg(COALESCE(a.attname, '(expression)') ORDER BY x.ord)
                                    FROM unnest(ix.indkey::int2[]) WITH ORDINALITY x(attnum, ord)
                                    LEFT JOIN pg_attribute a ON a.attrelid = ix.indrelid AND a.attnum = x.attnum
                                   WHERE x.ord <= ix.indnkeyatts), '[]') AS text) AS columns,
                   ix.indisunique AS is_unique, ix.indisprimary AS is_primary,
                   pg_get_expr(ix.indpred, ix.indrelid) AS predicate, pg_get_indexdef(ix.indexrelid) AS definition
              FROM pg_index ix
              JOIN pg_class i ON i.oid = ix.indexrelid
              JOIN pg_class c ON c.oid = ix.indrelid
              JOIN pg_namespace n ON n.oid = c.relnamespace
             WHERE n.nspname = current_schema() AND c.relname NOT LIKE 'flyway\\_%'
             ORDER BY c.relname, i.relname
            """;

    static final String SCHEMA_TRIGGERS = """
            SELECT c.relname AS table_name, t.tgname AS name,
                   CASE WHEN t.tgtype & 64 <> 0 THEN 'INSTEAD OF' WHEN t.tgtype & 2 <> 0 THEN 'BEFORE' ELSE 'AFTER' END
                       AS timing,
                   CAST(to_json(array_remove(ARRAY[
                       CASE WHEN t.tgtype & 4 <> 0 THEN 'INSERT' END,
                       CASE WHEN t.tgtype & 16 <> 0 THEN 'UPDATE' END,
                       CASE WHEN t.tgtype & 8 <> 0 THEN 'DELETE' END,
                       CASE WHEN t.tgtype & 32 <> 0 THEN 'TRUNCATE' END], NULL)) AS text) AS events,
                   p.proname AS function_name
              FROM pg_trigger t
              JOIN pg_class c ON c.oid = t.tgrelid
              JOIN pg_namespace n ON n.oid = c.relnamespace
              JOIN pg_proc p ON p.oid = t.tgfoid
             WHERE n.nspname = current_schema() AND NOT t.tgisinternal AND c.relname NOT LIKE 'flyway\\_%'
             ORDER BY c.relname, t.tgname
            """;

    /** The latest successful migration, or null when Flyway has not run (tests, harness). */
    static final String FLYWAY_VERSION = """
            SELECT version FROM flyway_schema_history
             WHERE success AND version IS NOT NULL
             ORDER BY installed_rank DESC
             LIMIT 1
            """;

    static final String HAS_FLYWAY = """
            SELECT to_regclass('flyway_schema_history') IS NOT NULL AS present
            """;

    private final JdbcClient jdbc;
    private final Json json;

    public SchemaIntrospector(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public SchemaModel read() {
        Map<String, String> kinds = new TreeMap<>();
        jdbc.sql(SCHEMA_TABLES).query((rs, n) -> kinds.put(rs.getString("table_name"), rs.getString("kind"))).list();
        Map<String, List<Column>> columns = new TreeMap<>();
        jdbc.sql(SCHEMA_COLUMNS).query((rs, n) -> columns.computeIfAbsent(rs.getString("table_name"), k -> new ArrayList<>())
                .add(new Column(rs.getString("column_name"), rs.getInt("position"), rs.getString("data_type"),
                        rs.getBoolean("nullable"), rs.getString("default_value")))).list();
        Map<String, List<Constraint>> constraints = new TreeMap<>();
        jdbc.sql(SCHEMA_CONSTRAINTS).query((rs, n) -> constraints.computeIfAbsent(rs.getString("table_name"), k -> new ArrayList<>())
                .add(new Constraint(rs.getString("name"), rs.getString("kind"), json.stringList(rs.getString("columns")),
                        rs.getString("definition"), rs.getString("references_table"),
                        rs.getString("referenced_columns") == null ? List.of() : json.stringList(rs.getString("referenced_columns")),
                        rs.getBoolean("deferrable"), rs.getBoolean("validated")))).list();
        Map<String, List<Index>> indexes = new TreeMap<>();
        jdbc.sql(SCHEMA_INDEXES).query((rs, n) -> indexes.computeIfAbsent(rs.getString("table_name"), k -> new ArrayList<>())
                .add(new Index(rs.getString("name"), json.stringList(rs.getString("columns")), rs.getBoolean("is_unique"),
                        rs.getBoolean("is_primary"), rs.getString("predicate"), rs.getString("definition")))).list();
        Map<String, List<Trigger>> triggers = new TreeMap<>();
        jdbc.sql(SCHEMA_TRIGGERS).query((rs, n) -> triggers.computeIfAbsent(rs.getString("table_name"), k -> new ArrayList<>())
                .add(new Trigger(rs.getString("name"), rs.getString("timing"), json.stringList(rs.getString("events")),
                        rs.getString("function_name")))).list();
        List<Table> tables = new ArrayList<>();
        kinds.forEach((name, kind) -> tables.add(new Table(name, kind, columns.getOrDefault(name, List.of()),
                constraints.getOrDefault(name, List.of()), indexes.getOrDefault(name, List.of()),
                triggers.getOrDefault(name, List.of()))));
        return SchemaModel.of(tables);
    }

    public Optional<String> flywayVersion() {
        Boolean present = jdbc.sql(HAS_FLYWAY).query(Boolean.class).single();
        if (!Boolean.TRUE.equals(present)) {
            return Optional.empty();
        }
        return jdbc.sql(FLYWAY_VERSION).query(String.class).optional();
    }
}
