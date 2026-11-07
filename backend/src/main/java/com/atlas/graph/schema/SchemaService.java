package com.atlas.graph.schema;

import com.atlas.common.error.ApiException;
import com.atlas.common.json.Json;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * ER Schema Explorer (Graph Intelligence, step 1). Every public method checks the access
 * policy first and records the access in the platform audit trail.
 */
@Service
public class SchemaService {

    /** The live schema plus whether it differs from the latest stored snapshot. */
    public record LiveSchema(String flywayVersion, String sha256, SchemaModel schema,
                             SchemaSnapshotRepository.Snapshot latestSnapshot, boolean changedSinceSnapshot) {
    }

    public record DiffView(String from, String to, SchemaDiff.Result diff) {
    }

    static final Set<String> EXPORT_FORMATS = Set.of("SVG", "PNG");

    private final SchemaIntrospector introspector;
    private final SchemaSnapshotRepository snapshots;
    private final SchemaAccessPolicy access;
    private final PlatformAudit audit;
    private final Json json;

    public SchemaService(SchemaIntrospector introspector, SchemaSnapshotRepository snapshots, SchemaAccessPolicy access,
                         PlatformAudit audit, Json json) {
        this.introspector = introspector;
        this.snapshots = snapshots;
        this.access = access;
        this.audit = audit;
        this.json = json;
    }

    public LiveSchema live(UUID userId) {
        access.requireSchemaAccess(userId, "view schema");
        SchemaModel model = introspector.read();
        String hash = hash(model);
        Optional<SchemaSnapshotRepository.Snapshot> latest = snapshots.latest();
        boolean changed = latest.map(s -> !s.sha256().equals(hash)).orElse(true);
        String version = introspector.flywayVersion().orElse(null);
        audit.record(userId, PlatformAudit.SCHEMA_VIEW, "live", detail("sha256", hash, "flywayVersion", version));
        return new LiveSchema(version, hash, model, latest.orElse(null), changed);
    }

    public List<SchemaSnapshotRepository.Snapshot> versions(UUID userId) {
        access.requireSchemaAccess(userId, "list schema versions");
        List<SchemaSnapshotRepository.Snapshot> list = snapshots.list();
        audit.record(userId, PlatformAudit.SCHEMA_VERSIONS, null, Map.of("count", list.size()));
        return list;
    }

    /** @param from a snapshot id; @param to a snapshot id, or "live" for the current database */
    public DiffView diff(UUID userId, String from, String to) {
        access.requireSchemaAccess(userId, "compare schema versions");
        SchemaModel a = model(from);
        SchemaModel b = model(to);
        SchemaDiff.Result result = SchemaDiff.compare(a, b);
        audit.record(userId, PlatformAudit.SCHEMA_DIFF, from + ".." + to,
                Map.of("added", result.added(), "removed", result.removed(), "changed", result.changed()));
        return new DiffView(from, to, result);
    }

    /**
     * Records an export the browser is about to make. The image is produced in the browser, so
     * this proves the export was requested through the application, not what the user saved.
     */
    public UUID recordExport(UUID userId, String format, String version, int tables) {
        access.requireSchemaAccess(userId, "export schema diagram");
        if (format == null || !EXPORT_FORMATS.contains(format)) {
            throw ApiException.badRequest("format must be SVG or PNG");
        }
        return audit.record(userId, PlatformAudit.SCHEMA_EXPORT, version, detail("format", format, "tables", tables));
    }

    /** Stores the live schema when its structure is new. Called at start-up; not user-facing. */
    public Optional<UUID> captureSnapshot() {
        SchemaModel model = introspector.read();
        return snapshots.insert(introspector.flywayVersion().orElse("unknown"), hash(model), model.tables().size(),
                model.relationships().size(), json.write(model));
    }

    private SchemaModel model(String ref) {
        if ("live".equals(ref)) {
            return introspector.read();
        }
        UUID id;
        try {
            id = UUID.fromString(ref);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw ApiException.badRequest("Version must be a snapshot id or \"live\"");
        }
        String catalog = snapshots.catalog(id).orElseThrow(() -> ApiException.notFound("Schema snapshot"));
        return json.read(catalog, SchemaModel.class);
    }

    String hash(SchemaModel model) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.write(model).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> detail(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i + 1] != null) {
                m.put((String) kv[i], kv[i + 1]);
            }
        }
        return m;
    }
}
