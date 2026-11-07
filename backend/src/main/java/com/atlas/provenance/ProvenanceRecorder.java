package com.atlas.provenance;

import com.atlas.audit.TransactionIds;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records "this version's field was computed from that version's field" in
 * the same transaction as the derived write (ADR-0003). Both versions must
 * have change events by commit time; the deferred foreign keys reject the
 * whole transaction otherwise, so a fabricated edge cannot be stored.
 */
@Component
public class ProvenanceRecorder {

    public static final String DERIVED_VALUE = "DERIVED_VALUE";
    public static final String AGGREGATED_INTO = "AGGREGATED_INTO";
    public static final String COPIED_INTO = "COPIED_INTO";

    static final String INSERT_PROVENANCE_EDGE = """
            INSERT INTO provenance_edges (organization_id, txn_id,
                    source_entity_type, source_entity_id, source_entity_version, source_field,
                    target_entity_type, target_entity_id, target_entity_version, target_field,
                    relation, evidence, operation)
            VALUES (:orgId, :txnId,
                    :sourceType, :sourceId, :sourceVersion, :sourceField,
                    :targetType, :targetId, :targetVersion, :targetField,
                    :relation, 'RECORDED_AT_WRITE', :operation)
            """;

    private final JdbcClient jdbc;

    public ProvenanceRecorder(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Business operations that record provenance (V10: stored on every edge). */
    public static final String OP_ORDER_ADD_LINE = "order.add_line";
    public static final String OP_INVOICE_FROM_ORDER = "invoice.create_from_order";
    public static final String OP_RECOVERY_UPDATE = "recovery.update_fields";

    /**
     * @param operation the business operation that computed the target value, e.g.
     *                  {@link #OP_ORDER_ADD_LINE}; stored so lineage can say HOW a value was produced
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(UUID orgId, VersionRef source, String sourceField, VersionRef target, String targetField,
                       String relation, String operation) {
        if (!relation.equals(DERIVED_VALUE) && !relation.equals(AGGREGATED_INTO) && !relation.equals(COPIED_INTO)) {
            throw new IllegalArgumentException("Unknown provenance relation: " + relation);
        }
        if (operation == null || !operation.matches("^[a-z][a-z_]*\\.[a-z][a-z_]*$")) {
            throw new IllegalArgumentException("Provenance needs a business operation like 'order.add_line': " + operation);
        }
        jdbc.sql(INSERT_PROVENANCE_EDGE)
                .param("orgId", orgId)
                .param("txnId", TransactionIds.current())
                .param("sourceType", source.entityType()).param("sourceId", source.entityId())
                .param("sourceVersion", source.version()).param("sourceField", sourceField)
                .param("targetType", target.entityType()).param("targetId", target.entityId())
                .param("targetVersion", target.version()).param("targetField", targetField)
                .param("relation", relation)
                .param("operation", operation)
                .update();
    }
}
