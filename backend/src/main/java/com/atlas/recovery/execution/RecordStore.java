package com.atlas.recovery.execution;

import com.atlas.recovery.engine.EntityKey;
import com.atlas.recovery.engine.RecoveryPlan;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** The business records a plan changes. Called inside a transaction. */
public interface RecordStore {

    /** Who and why, for the change event each write records (actor kind RECOVERY). */
    record StepContext(UUID planId, int stepNo, UUID executedBy, String reason) {
    }

    /** The record's current version with its row locked; empty if it no longer exists. */
    Optional<Long> lockVersion(EntityKey key);

    /**
     * Writes the fields as a new version (expected + 1), records the change
     * event and a provenance edge per input. Throws {@link StepRejected} for
     * deterministic refusals.
     *
     * @return the new version
     */
    long applyFields(EntityKey key, long expectedVersion, Map<String, Object> fields, List<RecoveryPlan.Input> inputs,
                     StepContext context);

    /** Issues a credit note against an issued invoice; returns its id. */
    UUID issueCreditNote(EntityKey invoice, BigDecimal amount, StepContext context);

    // ----------------------------------------------------------- verification reads

    /** A field's current value (numbers as BigDecimal); empty if the record does not exist. */
    Optional<Object> field(EntityKey key, String field);

    /** Sum of the current line totals of an order or invoice. */
    BigDecimal sumOfLineTotals(EntityKey orderOrInvoice);

    Optional<BigDecimal> creditNoteAmount(UUID creditNoteId);

    /** Every record a change event attributes to this plan's execution. */
    Set<EntityKey> writtenByPlan(UUID planId);
}
