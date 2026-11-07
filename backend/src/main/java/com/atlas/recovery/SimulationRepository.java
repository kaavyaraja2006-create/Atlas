package com.atlas.recovery;

import com.atlas.common.db.Db;
import com.atlas.common.json.Json;
import com.atlas.provenance.VersionRef;
import com.atlas.recovery.engine.EntityKey;
import com.atlas.recovery.engine.StrategyComparison;
import com.atlas.recovery.engine.StrategyRecords;
import com.atlas.recovery.engine.StrategyResult;
import com.atlas.recovery.engine.VersionInfo;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Snapshot loading and persistence for counterfactual simulations (V12). */
@Repository
class SimulationRepository {

    record Snapshot(long chainIndex, Map<EntityKey, List<VersionInfo>> histories, Map<VersionRef, Long> sequence) {
    }

    record Run(UUID id, UUID incidentId, String status, Long snapshotChainIndex, String fingerprint, Integer records,
               Integer versions, String recommendedStrategy, String riskLevel, Object assessment, String failure,
               UUID startedBy, String startedByName, Instant startedAt, Instant finishedAt, Integer durationMs) {
    }

    record ResultRow(String strategy, String letter, boolean applicable, String notApplicableReason,
                     int recordsInspected, int recordsModified, int recordsDeleted, int faultyRecords, int repaired,
                     int collateralModified, int legitimateOverwritten, int conflictsSurfaced, int conflictsIgnored,
                     int invariantViolations, int fieldWrites, int compensations, int manualSteps, long simulationMicros,
                     Object violations, Object notes, Details details) {
    }

    /**
     * Record-level totals of a result (V15). Null for runs simulated before V15. The counts are
     * exact; {@code stored}/{@code omitted}/{@code truncated} describe the capped detail list.
     */
    record Details(int total, int inspected, int proposed, int modified, int preserved, int conflicting, int unresolved,
                   int preservedLaterChanges, int unresolvedDependencies, int stored, int omitted, boolean truncated) {
    }

    record RecordRow(int seq, String entityType, UUID entityId, String action, boolean inspected, boolean proposed,
                     boolean modified, boolean preserved, boolean conflicting, boolean unresolved, List<String> fields,
                     List<String> provenancePath, String conflictKind) {
    }

    /** @param differences null in listings; the stored differences when one comparison is read */
    record ComparisonRow(String strategyA, String strategyB, int onlyA, int onlyB, int both, int sameAction,
                         int totalDifferences, int storedDifferences, int omitted, boolean truncated, String sha256,
                         Instant createdAt, Object differences) {
    }

    static final String SNAPSHOT_HEAD = """
            SELECT COALESCE((SELECT last_index FROM audit_chain_heads WHERE organization_id = :orgId), 0)
            """;

    /**
     * Every version of: the records provenance reaches from the faulty version (as the analysis
     * loads them), the records summed into them, and, for a product origin, every record linked
     * to the product by foreign keys with their sibling lines (so foreign-key strategies and
     * totals can be modelled). With each version's audit-chain position.
     */
    static final String LOAD_SIMULATION_VERSIONS = """
            WITH RECURSIVE reach(entity_type, entity_id, min_version) AS (
                SELECT CAST(:entityType AS varchar(64)), CAST(:entityId AS uuid), CAST(:version AS bigint)
              UNION
                SELECT e.target_entity_type, e.target_entity_id, e.target_entity_version
                  FROM provenance_edges e
                  JOIN reach r ON e.source_entity_type = r.entity_type AND e.source_entity_id = r.entity_id
                              AND e.source_entity_version >= r.min_version
                 WHERE e.organization_id = :orgId
            ), reached AS (
                SELECT DISTINCT entity_type, entity_id FROM reach
            ), fk_lines AS (
                SELECT l.id, l.order_id FROM order_lines l
                 WHERE l.organization_id = :orgId AND CAST(:entityType AS text) = 'product' AND l.product_id = :entityId
            ), fk_orders AS (
                SELECT DISTINCT order_id AS id FROM fk_lines
            ), fk_invoices AS (
                SELECT DISTINCT il.invoice_id AS id FROM invoice_lines il
                  JOIN fk_lines l ON l.id = il.order_line_id
                 WHERE il.organization_id = :orgId
            ), entities AS (
                SELECT entity_type, entity_id FROM reached
              UNION
                SELECT e.source_entity_type, e.source_entity_id
                  FROM provenance_edges e
                  JOIN reached x ON x.entity_type = e.target_entity_type AND x.entity_id = e.target_entity_id
                 WHERE e.organization_id = :orgId
              UNION
                SELECT CAST('order' AS varchar(64)), id FROM fk_orders
              UNION
                SELECT CAST('order_line' AS varchar(64)), l.id FROM order_lines l
                  JOIN fk_orders o ON o.id = l.order_id WHERE l.organization_id = :orgId
              UNION
                SELECT CAST('invoice' AS varchar(64)), id FROM fk_invoices
              UNION
                SELECT CAST('invoice_line' AS varchar(64)), il.id FROM invoice_lines il
                  JOIN fk_invoices i ON i.id = il.invoice_id WHERE il.organization_id = :orgId
            )
            SELECT c.entity_type, c.entity_id, c.entity_version, c.operation, c.actor_kind,
                   CAST(to_jsonb(c.changed_fields) AS text) AS changed_fields, CAST(c.after_state AS text) AS after_state,
                   c.chain_index
              FROM change_events c
              JOIN entities n ON n.entity_type = c.entity_type AND n.entity_id = c.entity_id
             WHERE c.organization_id = :orgId
             ORDER BY c.entity_type, c.entity_id, c.entity_version
            """;

    static final String INSERT_SIMULATION_RUN = """
            INSERT INTO simulation_runs (organization_id, incident_id, started_by)
            VALUES (:orgId, :incidentId, :startedBy)
            RETURNING id
            """;

    static final String COMPLETE_SIMULATION_RUN = """
            UPDATE simulation_runs
               SET status = 'COMPLETED', snapshot_chain_index = :chainIndex, fingerprint = :fingerprint,
                   records = :records, versions = :versions, recommended_strategy = :recommended,
                   risk_level = :riskLevel, assessment = CAST(:assessment AS jsonb), finished_at = now(),
                   duration_ms = :durationMs
             WHERE organization_id = :orgId AND id = :id AND status = 'RUNNING'
            """;

    static final String FAIL_SIMULATION_RUN = """
            UPDATE simulation_runs
               SET status = 'FAILED', failure = :failure, finished_at = now(), duration_ms = :durationMs
             WHERE organization_id = :orgId AND id = :id AND status = 'RUNNING'
            """;

    static final String INSERT_SIMULATION_RESULT = """
            INSERT INTO simulation_results (organization_id, run_id, strategy, letter, applicable, not_applicable_reason,
                   records_inspected, records_modified, records_deleted, faulty_records, repaired, collateral_modified,
                   legitimate_overwritten, conflicts_surfaced, conflicts_ignored, invariant_violations, field_writes,
                   compensations, manual_steps, simulation_micros, violations, notes, records_total, records_proposed,
                   records_preserved, records_conflicting, records_unresolved, preserved_later_changes,
                   unresolved_dependencies, details_stored, details_truncated)
            VALUES (:orgId, :runId, :strategy, :letter, :applicable, :notApplicableReason, :recordsInspected,
                    :recordsModified, :recordsDeleted, :faultyRecords, :repaired, :collateralModified,
                    :legitimateOverwritten, :conflictsSurfaced, :conflictsIgnored, :invariantViolations, :fieldWrites,
                    :compensations, :manualSteps, :simulationMicros, CAST(:violations AS jsonb), CAST(:notes AS jsonb),
                    :recordsTotal, :recordsProposed, :recordsPreserved, :recordsConflicting, :recordsUnresolved,
                    :preservedLaterChanges, :unresolvedDependencies, :detailsStored, :detailsTruncated)
            """;

    /** All of one strategy's stored details in one statement (JSON rows; identifiers and field names only). */
    static final String INSERT_SIMULATION_RECORDS = """
            INSERT INTO simulation_result_records (organization_id, run_id, strategy, seq, entity_type, entity_id, action,
                   inspected, proposed, modified, preserved, conflicting, unresolved, fields, provenance_path, conflict_kind)
            SELECT :orgId, :runId, :strategy, r.seq, r.entity_type, r.entity_id, r.action, r.inspected, r.proposed,
                   r.modified, r.preserved, r.conflicting, r.unresolved,
                   ARRAY(SELECT jsonb_array_elements_text(r.fields)), r.provenance_path, r.conflict_kind
              FROM jsonb_to_recordset(CAST(:rows AS jsonb)) AS r(seq integer, entity_type varchar, entity_id uuid,
                   action varchar, inspected boolean, proposed boolean, modified boolean, preserved boolean,
                   conflicting boolean, unresolved boolean, fields jsonb, provenance_path jsonb, conflict_kind varchar)
            """;

    static final String INSERT_SIMULATION_COMPARISON = """
            INSERT INTO simulation_comparisons (organization_id, run_id, strategy_a, strategy_b, only_a, only_b,
                   both_strategies, same_action, total_differences, stored_differences, truncated, sha256, differences)
            VALUES (:orgId, :runId, :strategyA, :strategyB, :onlyA, :onlyB, :both, :sameAction, :totalDifferences,
                    :storedDifferences, :truncated, :sha256, CAST(:differences AS jsonb))
            """;

    /** One page of a strategy's STORED details, optionally only one category. */
    static final String LIST_SIMULATION_RECORDS = """
            SELECT seq, entity_type, entity_id, action, inspected, proposed, modified, preserved, conflicting, unresolved,
                   CAST(to_jsonb(fields) AS text) AS fields, CAST(provenance_path AS text) AS provenance_path, conflict_kind
              FROM simulation_result_records
             WHERE organization_id = :orgId AND run_id = :runId AND strategy = :strategy
               AND (CAST(:category AS text) IS NULL
                    OR (CAST(:category AS text) = 'INSPECTED' AND inspected)
                    OR (CAST(:category AS text) = 'PROPOSED' AND proposed)
                    OR (CAST(:category AS text) = 'MODIFIED' AND modified)
                    OR (CAST(:category AS text) = 'PRESERVED' AND preserved)
                    OR (CAST(:category AS text) = 'CONFLICTING' AND conflicting)
                    OR (CAST(:category AS text) = 'UNRESOLVED' AND unresolved))
             ORDER BY seq
             LIMIT :limit OFFSET :offset
            """;

    static final String COUNT_STORED_SIMULATION_RECORDS = """
            SELECT count(*)
              FROM simulation_result_records
             WHERE organization_id = :orgId AND run_id = :runId AND strategy = :strategy
               AND (CAST(:category AS text) IS NULL
                    OR (CAST(:category AS text) = 'INSPECTED' AND inspected)
                    OR (CAST(:category AS text) = 'PROPOSED' AND proposed)
                    OR (CAST(:category AS text) = 'MODIFIED' AND modified)
                    OR (CAST(:category AS text) = 'PRESERVED' AND preserved)
                    OR (CAST(:category AS text) = 'CONFLICTING' AND conflicting)
                    OR (CAST(:category AS text) = 'UNRESOLVED' AND unresolved))
            """;

    static final String LIST_SIMULATION_COMPARISONS = """
            SELECT strategy_a, strategy_b, only_a, only_b, both_strategies, same_action, total_differences,
                   stored_differences, truncated, sha256, created_at
              FROM simulation_comparisons
             WHERE organization_id = :orgId AND run_id = :runId
             ORDER BY strategy_a, strategy_b
            """;

    static final String FIND_SIMULATION_COMPARISON = """
            SELECT strategy_a, strategy_b, only_a, only_b, both_strategies, same_action, total_differences,
                   stored_differences, truncated, sha256, created_at, CAST(differences AS text) AS differences
              FROM simulation_comparisons
             WHERE organization_id = :orgId AND run_id = :runId
               AND ((strategy_a = :a AND strategy_b = :b) OR (strategy_a = :b AND strategy_b = :a))
            """;

    private static final String RUN_COLUMNS = """
            SELECT r.id, r.incident_id, r.status, r.snapshot_chain_index, r.fingerprint, r.records, r.versions,
                   r.recommended_strategy, r.risk_level, CAST(r.assessment AS text) AS assessment, r.failure,
                   r.started_by, u.display_name AS started_by_name, r.started_at, r.finished_at, r.duration_ms
              FROM simulation_runs r
              JOIN users u ON u.id = r.started_by
            """;

    static final String FIND_SIMULATION_RUN = RUN_COLUMNS + """
             WHERE r.organization_id = :orgId AND r.id = :id
            """;

    static final String LIST_SIMULATION_RUNS = RUN_COLUMNS + """
             WHERE r.organization_id = :orgId AND r.incident_id = :incidentId
             ORDER BY r.started_at DESC
             LIMIT 50
            """;

    static final String LATEST_COMPLETED_SIMULATION = RUN_COLUMNS + """
             WHERE r.organization_id = :orgId AND r.incident_id = :incidentId AND r.status = 'COMPLETED'
             ORDER BY r.finished_at DESC
             LIMIT 1
            """;

    static final String LIST_SIMULATION_RESULTS = """
            SELECT strategy, letter, applicable, not_applicable_reason, records_inspected, records_modified,
                   records_deleted, faulty_records, repaired, collateral_modified, legitimate_overwritten,
                   conflicts_surfaced, conflicts_ignored, invariant_violations, field_writes, compensations, manual_steps,
                   simulation_micros, CAST(violations AS text) AS violations, CAST(notes AS text) AS notes,
                   records_total, records_proposed, records_preserved, records_conflicting, records_unresolved,
                   preserved_later_changes, unresolved_dependencies, details_stored, details_truncated
              FROM simulation_results
             WHERE organization_id = :orgId AND run_id = :runId
             ORDER BY letter
            """;

    static final String DELETE_OLD_SIMULATIONS = """
            DELETE FROM simulation_runs
             WHERE status <> 'RUNNING' AND started_at < now() - make_interval(days => :days)
            """;

    private final JdbcClient jdbc;
    private final Json json;

    SimulationRepository(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    Snapshot snapshot(UUID orgId, VersionRef faulty) {
        long head = jdbc.sql(SNAPSHOT_HEAD).param("orgId", orgId).query(Long.class).single();
        Map<EntityKey, List<VersionInfo>> histories = new LinkedHashMap<>();
        Map<VersionRef, Long> sequence = new HashMap<>();
        jdbc.sql(LOAD_SIMULATION_VERSIONS)
                .param("orgId", orgId).param("entityType", faulty.entityType())
                .param("entityId", faulty.entityId()).param("version", faulty.version())
                .query((ResultSet rs) -> {
                    while (rs.next()) {
                        EntityKey key = new EntityKey(rs.getString("entity_type"), rs.getObject("entity_id", UUID.class));
                        long v = rs.getLong("entity_version");
                        histories.computeIfAbsent(key, k -> new ArrayList<>()).add(new VersionInfo(v,
                                rs.getString("operation"), rs.getString("actor_kind"),
                                new HashSet<>(json.stringList(rs.getString("changed_fields"))),
                                json.exactObject(rs.getString("after_state"))));
                        sequence.put(key.at(v), rs.getLong("chain_index"));
                    }
                    return null;
                });
        return new Snapshot(head, histories, sequence);
    }

    UUID start(UUID orgId, UUID incidentId, UUID startedBy) {
        return jdbc.sql(INSERT_SIMULATION_RUN).param("orgId", orgId).param("incidentId", incidentId)
                .param("startedBy", startedBy).query(UUID.class).single();
    }

    void complete(UUID orgId, UUID id, long chainIndex, String fingerprint, int records, int versions, String recommended,
                  String riskLevel, Object assessment, int durationMs) {
        jdbc.sql(COMPLETE_SIMULATION_RUN).param("orgId", orgId).param("id", id).param("chainIndex", chainIndex)
                .param("fingerprint", fingerprint).param("records", records).param("versions", versions)
                .param("recommended", recommended).param("riskLevel", riskLevel)
                .param("assessment", json.write(assessment)).param("durationMs", durationMs).update();
    }

    void fail(UUID orgId, UUID id, String failure, int durationMs) {
        jdbc.sql(FAIL_SIMULATION_RUN).param("orgId", orgId).param("id", id)
                .param("failure", failure.length() > 1000 ? failure.substring(0, 1000) : failure)
                .param("durationMs", durationMs).update();
    }

    void insertResult(UUID orgId, UUID runId, StrategyResult r, StrategyRecords d) {
        jdbc.sql(INSERT_SIMULATION_RESULT).param("orgId", orgId).param("runId", runId)
                .param("strategy", r.strategy().name()).param("letter", r.strategy().letter())
                .param("applicable", r.applicable()).param("notApplicableReason", r.notApplicableReason())
                .param("recordsInspected", r.recordsInspected()).param("recordsModified", r.recordsModified())
                .param("recordsDeleted", r.recordsDeleted()).param("faultyRecords", r.faultyRecords())
                .param("repaired", r.repaired()).param("collateralModified", r.collateralModified())
                .param("legitimateOverwritten", r.legitimateOverwritten()).param("conflictsSurfaced", r.conflictsSurfaced())
                .param("conflictsIgnored", r.conflictsIgnored()).param("invariantViolations", r.invariantViolations())
                .param("fieldWrites", r.fieldWrites()).param("compensations", r.compensations())
                .param("manualSteps", r.manualSteps()).param("simulationMicros", r.simulationMicros())
                .param("violations", json.write(r.violations())).param("notes", json.write(r.notes()))
                .param("recordsTotal", d.total()).param("recordsProposed", d.proposed())
                .param("recordsPreserved", d.preserved()).param("recordsConflicting", d.conflicting())
                .param("recordsUnresolved", d.unresolved()).param("preservedLaterChanges", d.preservedLaterChanges())
                .param("unresolvedDependencies", d.unresolvedDependencies()).param("detailsStored", d.stored().size())
                .param("detailsTruncated", d.truncated()).update();
        if (!d.stored().isEmpty()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = 0; i < d.stored().size(); i++) {
                StrategyRecords.RecordDetail x = d.stored().get(i);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("seq", i);
                row.put("entity_type", x.key().entityType());
                row.put("entity_id", x.key().entityId());
                row.put("action", x.action());
                row.put("inspected", x.inspected());
                row.put("proposed", x.proposed());
                row.put("modified", x.modified());
                row.put("preserved", x.preserved());
                row.put("conflicting", x.conflicting());
                row.put("unresolved", x.unresolved());
                row.put("fields", x.fields());
                row.put("provenance_path", x.provenancePath());
                row.put("conflict_kind", x.conflictKind());
                rows.add(row);
            }
            jdbc.sql(INSERT_SIMULATION_RECORDS).param("orgId", orgId).param("runId", runId)
                    .param("strategy", r.strategy().name()).param("rows", json.write(rows)).update();
        }
    }

    void insertComparison(UUID orgId, UUID runId, StrategyComparison c) {
        List<Map<String, Object>> diffs = new ArrayList<>();
        for (StrategyComparison.Difference d : c.stored()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("entityType", d.key().entityType());
            m.put("entityId", d.key().entityId());
            m.put("actionA", d.actionA());
            m.put("fieldsA", d.fieldsA());
            m.put("actionB", d.actionB());
            m.put("fieldsB", d.fieldsB());
            diffs.add(m);
        }
        jdbc.sql(INSERT_SIMULATION_COMPARISON).param("orgId", orgId).param("runId", runId)
                .param("strategyA", c.a().name()).param("strategyB", c.b().name()).param("onlyA", c.onlyA())
                .param("onlyB", c.onlyB()).param("both", c.both()).param("sameAction", c.sameAction())
                .param("totalDifferences", c.totalDifferences()).param("storedDifferences", c.stored().size())
                .param("truncated", c.truncated()).param("sha256", c.sha256()).param("differences", json.write(diffs))
                .update();
    }

    List<RecordRow> records(UUID orgId, UUID runId, String strategy, String category, int limit, int offset) {
        return jdbc.sql(LIST_SIMULATION_RECORDS).param("orgId", orgId).param("runId", runId).param("strategy", strategy)
                .param("category", category).param("limit", limit).param("offset", offset)
                .query((rs, n) -> new RecordRow(rs.getInt("seq"), rs.getString("entity_type"),
                        rs.getObject("entity_id", UUID.class), rs.getString("action"), rs.getBoolean("inspected"),
                        rs.getBoolean("proposed"), rs.getBoolean("modified"), rs.getBoolean("preserved"),
                        rs.getBoolean("conflicting"), rs.getBoolean("unresolved"), json.stringList(rs.getString("fields")),
                        json.stringList(rs.getString("provenance_path")), rs.getString("conflict_kind")))
                .list();
    }

    long countStoredRecords(UUID orgId, UUID runId, String strategy, String category) {
        return jdbc.sql(COUNT_STORED_SIMULATION_RECORDS).param("orgId", orgId).param("runId", runId)
                .param("strategy", strategy).param("category", category).query(Long.class).single();
    }

    List<ComparisonRow> comparisons(UUID orgId, UUID runId) {
        return jdbc.sql(LIST_SIMULATION_COMPARISONS).param("orgId", orgId).param("runId", runId)
                .query((rs, n) -> comparison(rs, null)).list();
    }

    Optional<ComparisonRow> comparison(UUID orgId, UUID runId, String a, String b) {
        return jdbc.sql(FIND_SIMULATION_COMPARISON).param("orgId", orgId).param("runId", runId).param("a", a).param("b", b)
                .query((rs, n) -> comparison(rs, json.tree(rs.getString("differences")))).optional();
    }

    private static ComparisonRow comparison(ResultSet rs, Object differences) throws java.sql.SQLException {
        int total = rs.getInt("total_differences");
        int stored = rs.getInt("stored_differences");
        return new ComparisonRow(rs.getString("strategy_a"), rs.getString("strategy_b"), rs.getInt("only_a"),
                rs.getInt("only_b"), rs.getInt("both_strategies"), rs.getInt("same_action"), total, stored, total - stored,
                rs.getBoolean("truncated"), rs.getString("sha256"), Db.instant(rs, "created_at"), differences);
    }

    Optional<Run> run(UUID orgId, UUID id) {
        return jdbc.sql(FIND_SIMULATION_RUN).param("orgId", orgId).param("id", id).query(this::mapRun).optional();
    }

    List<Run> runs(UUID orgId, UUID incidentId) {
        return jdbc.sql(LIST_SIMULATION_RUNS).param("orgId", orgId).param("incidentId", incidentId).query(this::mapRun).list();
    }

    Optional<Run> latestCompleted(UUID orgId, UUID incidentId) {
        return jdbc.sql(LATEST_COMPLETED_SIMULATION).param("orgId", orgId).param("incidentId", incidentId)
                .query(this::mapRun).optional();
    }

    List<ResultRow> results(UUID orgId, UUID runId) {
        return jdbc.sql(LIST_SIMULATION_RESULTS).param("orgId", orgId).param("runId", runId)
                .query((rs, n) -> new ResultRow(rs.getString("strategy"), rs.getString("letter"),
                        rs.getBoolean("applicable"), rs.getString("not_applicable_reason"), rs.getInt("records_inspected"),
                        rs.getInt("records_modified"), rs.getInt("records_deleted"), rs.getInt("faulty_records"),
                        rs.getInt("repaired"), rs.getInt("collateral_modified"), rs.getInt("legitimate_overwritten"),
                        rs.getInt("conflicts_surfaced"), rs.getInt("conflicts_ignored"), rs.getInt("invariant_violations"),
                        rs.getInt("field_writes"), rs.getInt("compensations"), rs.getInt("manual_steps"),
                        rs.getLong("simulation_micros"), json.tree(rs.getString("violations")),
                        json.tree(rs.getString("notes")), details(rs)))
                .list();
    }

    private static Details details(ResultSet rs) throws java.sql.SQLException {
        Integer total = rs.getObject("records_total", Integer.class);
        if (total == null) {
            return null;
        }
        int stored = rs.getInt("details_stored");
        return new Details(total, rs.getInt("records_inspected"), rs.getInt("records_proposed"),
                rs.getInt("records_modified"), rs.getInt("records_preserved"),
                rs.getInt("records_conflicting"), rs.getInt("records_unresolved"), rs.getInt("preserved_later_changes"),
                rs.getInt("unresolved_dependencies"), stored, total - stored, rs.getBoolean("details_truncated"));
    }

    int deleteOlderThan(int days) {
        return jdbc.sql(DELETE_OLD_SIMULATIONS).param("days", days).update();
    }

    private Run mapRun(ResultSet rs, int n) throws java.sql.SQLException {
        String assessment = rs.getString("assessment");
        return new Run(rs.getObject("id", UUID.class), rs.getObject("incident_id", UUID.class), rs.getString("status"),
                rs.getObject("snapshot_chain_index", Long.class), rs.getString("fingerprint"),
                rs.getObject("records", Integer.class), rs.getObject("versions", Integer.class),
                rs.getString("recommended_strategy"), rs.getString("risk_level"),
                assessment == null ? null : json.tree(assessment), rs.getString("failure"),
                rs.getObject("started_by", UUID.class), rs.getString("started_by_name"), Db.instant(rs, "started_at"),
                Db.instant(rs, "finished_at"), rs.getObject("duration_ms", Integer.class));
    }
}
