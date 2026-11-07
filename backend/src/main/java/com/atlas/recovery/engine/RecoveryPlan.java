package com.atlas.recovery.engine;

import java.math.BigDecimal;
import java.util.List;

/** An ordered, explainable recovery plan. Nothing in it has been executed. */
public record RecoveryPlan(String fingerprint, List<Step> steps, Risk risk, boolean requiresApproval,
                           List<Invariant> invariants) {

    public enum Action {
        /** Put the faulty field(s) of the origin back to their values before the faulty change. */
        RESTORE_FIELDS,
        /** Recompute derived fields from repaired inputs. */
        UPDATE_FIELDS,
        /** Over-billed issued invoice: issue a credit note for the difference. */
        ISSUE_CREDIT_NOTE,
        /** Under-billed issued invoice: a supplementary charge is owed; needs a person. */
        SUPPLEMENTARY_CHARGE,
        /** A person must decide (conflict, cycle, unconfirmed provenance, unknown type). */
        MANUAL_REVIEW,
        /** Recorded for completeness; nothing to do (e.g. a void invoice). */
        NO_ACTION
    }

    public enum Risk { LOW, MEDIUM, HIGH }

    public record FieldChange(String field, Object current, Object proposed) {
    }

    /**
     * Where a step's recomputed field comes from. The executor records a new
     * provenance edge for each input (from the source's version at execution
     * time), so recovery writes keep the graph truthful for later incidents.
     */
    public record Input(EntityKey source, String sourceField, String targetField, Edge.Relation relation) {
    }

    /**
     * @param expectedVersion the record must still be at this version when the step runs
     * @param dependsOn       step numbers that must complete first
     * @param amount          money involved for compensation steps, else null
     */
    public record Step(int number, Action action, EntityKey key, long expectedVersion, List<FieldChange> changes,
                       List<Integer> dependsOn, Risk risk, String reason, BigDecimal amount, List<Input> inputs) {

        public Step {
            changes = List.copyOf(changes);
            dependsOn = List.copyOf(dependsOn);
            inputs = List.copyOf(inputs);
        }

        public boolean automatic() {
            return action == Action.RESTORE_FIELDS || action == Action.UPDATE_FIELDS || action == Action.ISSUE_CREDIT_NOTE;
        }
    }

    /**
     * A condition the verifier checks after execution.
     *
     * @param field    the field checked (FIELD_EQUALS), else null
     * @param expected the value it must have: a BigDecimal for numbers, else the proposed value
     */
    public record Invariant(String kind, EntityKey key, String field, String description, Object expected) {
    }

    public RecoveryPlan {
        steps = List.copyOf(steps);
        invariants = List.copyOf(invariants);
    }

    public long manualSteps() {
        return steps.stream().filter(s -> s.action() == Action.MANUAL_REVIEW || s.action() == Action.SUPPLEMENTARY_CHARGE).count();
    }
}
