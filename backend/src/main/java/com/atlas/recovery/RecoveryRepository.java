package com.atlas.recovery;

import com.atlas.common.db.Db;
import com.atlas.common.json.Json;
import com.atlas.provenance.VersionRef;
import com.atlas.recovery.engine.Edge;
import com.atlas.recovery.engine.EntityKey;
import com.atlas.recovery.engine.IncidentGraph;
import com.atlas.recovery.engine.ProvenanceRequirements;
import com.atlas.recovery.engine.VersionInfo;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class RecoveryRepository {

    record ChangeEventRow(UUID id, String entityType, UUID entityId, long version, String operation,
                          List<String> changedFields, Map<String, Object> before, Map<String, Object> after) {
    }

    record Incident(UUID id, String incidentNumber, UUID faultyChangeEventId, String title, String reason,
                    String status, UUID openedBy, Instant openedAt, long version) {
    }

    record PlanRow(UUID id, UUID incidentId, int planNumber, String fingerprint, String risk, boolean requiresApproval,
                   int stepCount, int manualStepCount, String status, Object impact, Object plan, UUID createdBy,
                   Instant createdAt) {
    }

    static final String FIND_CHANGE_EVENT = """
            SELECT id, entity_type, entity_id, entity_version, operation,
                   CAST(to_jsonb(changed_fields) AS text) AS changed_fields,
                   CAST(before_state AS text) AS before_state, CAST(after_state AS text) AS after_state
              FROM change_events
             WHERE organization_id = :orgId AND id = :id
            """;

    /*
     * Everything reachable from the faulty version through provenance, at
     * entity level, following an edge from any version at or after the one
     * reached (that is what carry-forward can need). UNION removes repeated
     * rows, so cycles terminate. The engine then filters by field and version.
     */
    static final String LOAD_INCIDENT_EDGES = """
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
            )
            SELECT e.source_entity_type, e.source_entity_id, e.source_entity_version, e.source_field,
                   e.target_entity_type, e.target_entity_id, e.target_entity_version, e.target_field,
                   e.relation, e.evidence
              FROM provenance_edges e
             WHERE e.organization_id = :orgId
               AND (EXISTS (SELECT 1 FROM reached x WHERE x.entity_type = e.source_entity_type AND x.entity_id = e.source_entity_id)
                 OR EXISTS (SELECT 1 FROM reached x WHERE x.entity_type = e.target_entity_type AND x.entity_id = e.target_entity_id))
            """;

    /** Full version history of every reached record and of every record summed into one (siblings). */
    static final String LOAD_INCIDENT_VERSIONS = """
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
            ), entities AS (
                SELECT entity_type, entity_id FROM reached
              UNION
                SELECT e.source_entity_type, e.source_entity_id
                  FROM provenance_edges e
                  JOIN reached x ON x.entity_type = e.target_entity_type AND x.entity_id = e.target_entity_id
                 WHERE e.organization_id = :orgId
            )
            SELECT c.entity_type, c.entity_id, c.entity_version, c.operation, c.actor_kind,
                   CAST(to_jsonb(c.changed_fields) AS text) AS changed_fields, CAST(c.after_state AS text) AS after_state
              FROM change_events c
              JOIN entities n ON n.entity_type = c.entity_type AND n.entity_id = c.entity_id
             WHERE c.organization_id = :orgId
             ORDER BY c.entity_type, c.entity_id, c.entity_version
            """;

    /**
     * Required provenance that was never recorded, for every record in the incident's scope
     * (reached records and records summed into one). Same requirements as /provenance/gaps
     * (ProvenanceRequirements.ALL; a test keeps the three lists equal).
     */
    static final String LOAD_INCIDENT_GAPS = """
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
            ), entities AS (
                SELECT entity_type, entity_id FROM reached
              UNION
                SELECT e.source_entity_type, e.source_entity_id
                  FROM provenance_edges e
                  JOIN reached x ON x.entity_type = e.target_entity_type AND x.entity_id = e.target_entity_id
                 WHERE e.organization_id = :orgId
            ), req(entity_type, field, zero_allowed, requirement) AS (VALUES
                ('order_line',   'unit_price',   false, 'priced from a product version (order.add_line)'),
                ('order',        'total_amount', true,  'summed from its lines (order.add_line)'),
                ('invoice_line', 'unit_price',   false, 'copied from an order line (invoice.create_from_order)'),
                ('invoice',      'total_amount', true,  'summed from its lines (invoice.create_from_order)'))
            SELECT c.entity_type, c.entity_id, c.entity_version, r.field, r.requirement
              FROM change_events c
              JOIN entities n ON n.entity_type = c.entity_type AND n.entity_id = c.entity_id
              JOIN req r ON r.entity_type = c.entity_type
             WHERE c.organization_id = :orgId
               AND c.operation IN ('INSERT', 'UPDATE')
               AND r.field = ANY (c.changed_fields)
               AND NOT (r.zero_allowed AND jsonb_typeof(c.after_state -> r.field) = 'number'
                        AND (c.after_state ->> r.field)::numeric = 0)
               AND NOT EXISTS (
                   SELECT 1 FROM provenance_edges p
                    WHERE p.organization_id = c.organization_id AND p.target_entity_type = c.entity_type
                      AND p.target_entity_id = c.entity_id AND p.target_entity_version = c.entity_version
                      AND p.target_field = r.field)
             ORDER BY c.entity_type, c.entity_id, c.entity_version, r.field
            """;

    static final String INSERT_INCIDENT = """
            INSERT INTO recovery_incidents (organization_id, incident_number, faulty_change_event_id, title, reason, opened_by)
            VALUES (:orgId, :incidentNumber, :changeEventId, :title, :reason, :openedBy)
            RETURNING id, incident_number, faulty_change_event_id, title, reason, status, opened_by, opened_at, version
            """;

    static final String FIND_INCIDENT = """
            SELECT id, incident_number, faulty_change_event_id, title, reason, status, opened_by, opened_at, version
              FROM recovery_incidents
             WHERE organization_id = :orgId AND id = :id
            """;

    static final String FIND_INCIDENT_FOR_UPDATE = """
            SELECT id, incident_number, faulty_change_event_id, title, reason, status, opened_by, opened_at, version
              FROM recovery_incidents
             WHERE organization_id = :orgId AND id = :id
               FOR UPDATE
            """;

    static final String LIST_INCIDENTS = """
            SELECT id, incident_number, faulty_change_event_id, title, reason, status, opened_by, opened_at, version
              FROM recovery_incidents
             WHERE organization_id = :orgId
             ORDER BY opened_at DESC, id
             LIMIT :limit OFFSET :offset
            """;

    static final String COUNT_INCIDENTS = """
            SELECT count(*) FROM recovery_incidents WHERE organization_id = :orgId
            """;

    static final String SET_INCIDENT_STATUS = """
            UPDATE recovery_incidents
               SET status = :status, updated_at = now(), version = version + 1
             WHERE organization_id = :orgId AND id = :id
            RETURNING version
            """;

    static final String SUPERSEDE_PROPOSED_PLANS = """
            UPDATE recovery_plans
               SET status = 'SUPERSEDED'
             WHERE organization_id = :orgId AND incident_id = :incidentId AND status IN ('PROPOSED', 'APPROVED')
            """;

    static final String NEXT_PLAN_NUMBER = """
            SELECT COALESCE(max(plan_number), 0) + 1 FROM recovery_plans
             WHERE organization_id = :orgId AND incident_id = :incidentId
            """;

    static final String INSERT_PLAN = """
            INSERT INTO recovery_plans (organization_id, incident_id, plan_number, fingerprint, risk, requires_approval,
                                        step_count, manual_step_count, impact, plan, created_by)
            VALUES (:orgId, :incidentId, :planNumber, :fingerprint, :risk, :requiresApproval,
                    :stepCount, :manualStepCount, CAST(:impact AS jsonb), CAST(:plan AS jsonb), :createdBy)
            RETURNING id, incident_id, plan_number, fingerprint, risk, requires_approval, step_count, manual_step_count,
                      status, CAST(impact AS text) AS impact, CAST(plan AS text) AS plan, created_by, created_at
            """;

    static final String FIND_PLAN = """
            SELECT id, incident_id, plan_number, fingerprint, risk, requires_approval, step_count, manual_step_count,
                   status, CAST(impact AS text) AS impact, CAST(plan AS text) AS plan, created_by, created_at
              FROM recovery_plans
             WHERE organization_id = :orgId AND id = :id
            """;

    static final String LIST_PLANS_FOR_INCIDENT = """
            SELECT id, incident_id, plan_number, fingerprint, risk, requires_approval, step_count, manual_step_count,
                   status, CAST(impact AS text) AS impact, CAST(plan AS text) AS plan, created_by, created_at
              FROM recovery_plans
             WHERE organization_id = :orgId AND incident_id = :incidentId
             ORDER BY plan_number DESC
            """;

    private final JdbcClient jdbc;
    private final Json json;

    RecoveryRepository(JdbcClient jdbc, Json json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    Optional<ChangeEventRow> changeEvent(UUID orgId, UUID id) {
        return jdbc.sql(FIND_CHANGE_EVENT).param("orgId", orgId).param("id", id)
                .query((rs, n) -> new ChangeEventRow(rs.getObject("id", UUID.class), rs.getString("entity_type"),
                        rs.getObject("entity_id", UUID.class), rs.getLong("entity_version"), rs.getString("operation"),
                        json.stringList(rs.getString("changed_fields")), json.exactObject(rs.getString("before_state")),
                        json.exactObject(rs.getString("after_state"))))
                .optional();
    }

    /** Loads the provenance neighbourhood of one faulty version. */
    IncidentGraph loadGraph(UUID orgId, VersionRef faulty) {
        List<Edge> edges = loadEdges(orgId, faulty);
        Map<EntityKey, List<VersionInfo>> histories = new LinkedHashMap<>();
        jdbc.sql(LOAD_INCIDENT_VERSIONS)
                .param("orgId", orgId).param("entityType", faulty.entityType())
                .param("entityId", faulty.entityId()).param("version", faulty.version())
                .query((ResultSet rs) -> {
                    while (rs.next()) {
                        EntityKey key = new EntityKey(rs.getString("entity_type"), rs.getObject("entity_id", UUID.class));
                        histories.computeIfAbsent(key, k -> new ArrayList<>()).add(new VersionInfo(
                                rs.getLong("entity_version"), rs.getString("operation"), rs.getString("actor_kind"),
                                new HashSet<>(json.stringList(rs.getString("changed_fields"))),
                                json.exactObject(rs.getString("after_state"))));
                    }
                    return null;
                });
        return new IncidentGraph(edges, histories).withGaps(loadGaps(orgId, faulty));
    }

    /** Required-but-missing provenance within the incident's scope (see LOAD_INCIDENT_GAPS). */
    List<ProvenanceRequirements.Gap> loadGaps(UUID orgId, VersionRef faulty) {
        return jdbc.sql(LOAD_INCIDENT_GAPS)
                .param("orgId", orgId).param("entityType", faulty.entityType())
                .param("entityId", faulty.entityId()).param("version", faulty.version())
                .query((rs, n) -> new ProvenanceRequirements.Gap(
                        new EntityKey(rs.getString("entity_type"), rs.getObject("entity_id", UUID.class)),
                        rs.getLong("entity_version"), rs.getString("field"), rs.getString("requirement")))
                .list();
    }

    List<Edge> loadEdges(UUID orgId, VersionRef faulty) {
        return jdbc.sql(LOAD_INCIDENT_EDGES)
                .param("orgId", orgId).param("entityType", faulty.entityType())
                .param("entityId", faulty.entityId()).param("version", faulty.version())
                .query((rs, n) -> new Edge(
                        new VersionRef(rs.getString("source_entity_type"), rs.getObject("source_entity_id", UUID.class),
                                rs.getLong("source_entity_version")),
                        rs.getString("source_field"),
                        new VersionRef(rs.getString("target_entity_type"), rs.getObject("target_entity_id", UUID.class),
                                rs.getLong("target_entity_version")),
                        rs.getString("target_field"),
                        Edge.Relation.valueOf(rs.getString("relation")),
                        "RECORDED_AT_WRITE".equals(rs.getString("evidence"))))
                .list();
    }

    Incident insertIncident(UUID orgId, String number, UUID changeEventId, String title, String reason, UUID openedBy) {
        return jdbc.sql(INSERT_INCIDENT).param("orgId", orgId).param("incidentNumber", number)
                .param("changeEventId", changeEventId).param("title", title).param("reason", reason)
                .param("openedBy", openedBy).query(RecoveryRepository::mapIncident).single();
    }

    Optional<Incident> incident(UUID orgId, UUID id) {
        return jdbc.sql(FIND_INCIDENT).param("orgId", orgId).param("id", id).query(RecoveryRepository::mapIncident).optional();
    }

    Optional<Incident> incidentForUpdate(UUID orgId, UUID id) {
        return jdbc.sql(FIND_INCIDENT_FOR_UPDATE).param("orgId", orgId).param("id", id)
                .query(RecoveryRepository::mapIncident).optional();
    }

    List<Incident> incidents(UUID orgId, int limit, long offset) {
        return jdbc.sql(LIST_INCIDENTS).param("orgId", orgId).param("limit", limit).param("offset", offset)
                .query(RecoveryRepository::mapIncident).list();
    }

    long countIncidents(UUID orgId) {
        return jdbc.sql(COUNT_INCIDENTS).param("orgId", orgId).query(Long.class).single();
    }

    long setIncidentStatus(UUID orgId, UUID id, String status) {
        return jdbc.sql(SET_INCIDENT_STATUS).param("orgId", orgId).param("id", id).param("status", status)
                .query(Long.class).single();
    }

    void supersedePlans(UUID orgId, UUID incidentId) {
        jdbc.sql(SUPERSEDE_PROPOSED_PLANS).param("orgId", orgId).param("incidentId", incidentId).update();
    }

    int nextPlanNumber(UUID orgId, UUID incidentId) {
        return jdbc.sql(NEXT_PLAN_NUMBER).param("orgId", orgId).param("incidentId", incidentId)
                .query(Integer.class).single();
    }

    PlanRow insertPlan(UUID orgId, UUID incidentId, int number, String fingerprint, String risk, boolean approval,
                       int steps, int manualSteps, String impactJson, String planJson, UUID createdBy) {
        return jdbc.sql(INSERT_PLAN).param("orgId", orgId).param("incidentId", incidentId).param("planNumber", number)
                .param("fingerprint", fingerprint).param("risk", risk).param("requiresApproval", approval)
                .param("stepCount", steps).param("manualStepCount", manualSteps)
                .param("impact", impactJson).param("plan", planJson).param("createdBy", createdBy)
                .query((rs, n) -> mapPlan(rs)).single();
    }

    Optional<PlanRow> plan(UUID orgId, UUID id) {
        return jdbc.sql(FIND_PLAN).param("orgId", orgId).param("id", id).query((rs, n) -> mapPlan(rs)).optional();
    }

    List<PlanRow> plans(UUID orgId, UUID incidentId) {
        return jdbc.sql(LIST_PLANS_FOR_INCIDENT).param("orgId", orgId).param("incidentId", incidentId)
                .query((rs, n) -> mapPlan(rs)).list();
    }

    private static Incident mapIncident(ResultSet rs, int n) throws SQLException {
        return new Incident(rs.getObject("id", UUID.class), rs.getString("incident_number"),
                rs.getObject("faulty_change_event_id", UUID.class), rs.getString("title"), rs.getString("reason"),
                rs.getString("status"), rs.getObject("opened_by", UUID.class), Db.instant(rs, "opened_at"),
                rs.getLong("version"));
    }

    private PlanRow mapPlan(ResultSet rs) throws SQLException {
        return new PlanRow(rs.getObject("id", UUID.class), rs.getObject("incident_id", UUID.class),
                rs.getInt("plan_number"), rs.getString("fingerprint"), rs.getString("risk"),
                rs.getBoolean("requires_approval"), rs.getInt("step_count"), rs.getInt("manual_step_count"),
                rs.getString("status"), json.tree(rs.getString("impact")), json.tree(rs.getString("plan")),
                rs.getObject("created_by", UUID.class), Db.instant(rs, "created_at"));
    }
}
