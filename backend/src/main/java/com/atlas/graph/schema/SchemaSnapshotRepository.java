package com.atlas.graph.schema;

import com.atlas.common.db.Db;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Stored schema snapshots (V14): append-only, one row per distinct structure. */
@Repository
public class SchemaSnapshotRepository {

    public record Snapshot(UUID id, String flywayVersion, String sha256, int tableCount, int relationshipCount,
                           Instant capturedAt) {
    }

    static final String INSERT_SCHEMA_SNAPSHOT = """
            INSERT INTO schema_snapshots (flyway_version, sha256, table_count, relationship_count, catalog)
            VALUES (:flywayVersion, :sha256, :tableCount, :relationshipCount, CAST(:catalog AS jsonb))
            ON CONFLICT (sha256) DO NOTHING
            RETURNING id
            """;

    static final String LIST_SCHEMA_SNAPSHOTS = """
            SELECT id, flyway_version, sha256, table_count, relationship_count, captured_at
              FROM schema_snapshots
             ORDER BY captured_at DESC, id
             LIMIT 200
            """;

    static final String FIND_SCHEMA_SNAPSHOT = """
            SELECT CAST(catalog AS text) AS catalog FROM schema_snapshots WHERE id = :id
            """;

    static final String LATEST_SCHEMA_SNAPSHOT = """
            SELECT id, flyway_version, sha256, table_count, relationship_count, captured_at
              FROM schema_snapshots
             ORDER BY captured_at DESC, id
             LIMIT 1
            """;

    private final JdbcClient jdbc;

    public SchemaSnapshotRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return the new snapshot's id, or empty when an identical structure was already stored */
    public Optional<UUID> insert(String flywayVersion, String sha256, int tables, int relationships, String catalog) {
        return jdbc.sql(INSERT_SCHEMA_SNAPSHOT).param("flywayVersion", flywayVersion).param("sha256", sha256)
                .param("tableCount", tables).param("relationshipCount", relationships).param("catalog", catalog)
                .query(UUID.class).optional();
    }

    public List<Snapshot> list() {
        return jdbc.sql(LIST_SCHEMA_SNAPSHOTS).query((rs, n) -> map(rs)).list();
    }

    public Optional<Snapshot> latest() {
        return jdbc.sql(LATEST_SCHEMA_SNAPSHOT).query((rs, n) -> map(rs)).optional();
    }

    public Optional<String> catalog(UUID id) {
        return jdbc.sql(FIND_SCHEMA_SNAPSHOT).param("id", id).query(String.class).optional();
    }

    private static Snapshot map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Snapshot(rs.getObject("id", UUID.class), rs.getString("flyway_version"), rs.getString("sha256"),
                rs.getInt("table_count"), rs.getInt("relationship_count"), Db.instant(rs, "captured_at"));
    }
}
