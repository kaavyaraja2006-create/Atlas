package com.atlas.recovery.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** ATLAS X Milestone 3: field-level temporal conflict classification, on simulated histories. */
class ConflictAnalyzerTest {

    private final ImpactAnalyzer analyzer = new ImpactAnalyzer();
    private final ConflictAnalyzer conflicts = new ConflictAnalyzer();
    private final PlanGenerator planner = new PlanGenerator();

    /** Widget priced 5000, mistyped as 500 (v1); one order then consumes 500. */
    private static final class Story {
        final SimulatedCommerce sim = new SimulatedCommerce(7);
        final UUID widget = sim.createProduct("P100", "5000");
        final UUID tee = sim.createProduct("T-100", "900");
        final UUID order;
        final UUID line;

        Story() {
            sim.updateProduct(widget, "unit_price", "500", "USER");
            order = sim.createOrder();
            line = sim.addLine(order, widget, 3);
            sim.addLine(order, tee, 1);
        }

        EntityKey lineKey() {
            return new EntityKey("order_line", line);
        }

        FaultyChange fault() {
            return sim.faultyChange(widget, 1, "unit_price");
        }
    }

    private List<FieldConflict> run(Story s) {
        IncidentGraph g = s.sim.graph();
        return conflicts.analyze(s.fault(), analyzer.analyze(s.fault(), g), g);
    }

    private static FieldConflict find(List<FieldConflict> all, EntityKey key, String field) {
        return all.stream().filter(c -> c.key().equals(key) && c.field().equals(field)).findFirst()
                .orElseThrow(() -> new AssertionError("no conflict entry for " + key + "." + field + " in " + all));
    }

    @Test
    void untouchedAfterTheFaultIsNoConflictAndCorrectable() {
        Story s = new Story();
        List<FieldConflict> all = run(s);
        FieldConflict price = find(all, s.lineKey(), "unit_price");
        assertEquals(ConflictKind.NO_CONFLICT, price.kind());
        assertEquals(FieldConflict.Recommendation.CORRECT_AUTOMATICALLY, price.recommendation());
        assertEquals(ConflictKind.NO_CONFLICT, find(all, SimulatedCommerce.order(s.order), "total_amount").kind());
        assertTrue(all.stream().noneMatch(FieldConflict::needsReview));
    }

    @Test
    void aLaterEditToAnotherFieldIsPreservedNotRestored() {
        Story s = new Story();
        // 11:00: someone legitimately changes the delivery note of the affected order.
        s.sim.edit(SimulatedCommerce.order(s.order), "delivery_note", "Leave at gate 2");
        List<FieldConflict> all = run(s);
        FieldConflict total = find(all, SimulatedCommerce.order(s.order), "total_amount");
        assertEquals(ConflictKind.DIFFERENT_FIELD_UPDATE, total.kind());
        assertEquals(java.util.Set.of("delivery_note"), total.laterFields());
        assertEquals(FieldConflict.Recommendation.CORRECT_AUTOMATICALLY, total.recommendation());

        // And the plan only writes the total: the delivery note survives the correction.
        IncidentGraph g = s.sim.graph();
        RecoveryPlan plan = planner.plan(analyzer.analyze(s.fault(), g), g);
        RecoveryPlan.Step orderStep = plan.steps().stream()
                .filter(st -> st.key().equals(SimulatedCommerce.order(s.order))).findFirst().orElseThrow();
        assertEquals(List.of("total_amount"), orderStep.changes().stream().map(RecoveryPlan.FieldChange::field).toList());
        assertEquals(s.sim.version(SimulatedCommerce.order(s.order)), orderStep.expectedVersion(),
                "planned against the version that already holds the delivery note");
    }

    @Test
    void aPersonChangingTheSameFieldIsAConflictForReview() {
        Story s = new Story();
        s.sim.edit(s.lineKey(), "unit_price", "450");   // someone gave a discount on the faulty price
        FieldConflict price = find(run(s), s.lineKey(), "unit_price");
        assertEquals(ConflictKind.SAME_FIELD_CONFLICT, price.kind());
        assertEquals(FieldConflict.Recommendation.HUMAN_REVIEW, price.recommendation());
        assertEquals("USER", price.laterActorKind());
        assertEquals(Long.valueOf(1L), price.laterVersion());
    }

    @Test
    void reDerivationFromAnotherSourceVersionIsKept() {
        Story s = new Story();
        s.sim.updateProduct(s.widget, "unit_price", "5000", "USER");      // v2: price corrected by hand
        s.sim.rederive(s.lineKey(), "unit_price", "5000", SimulatedCommerce.product(s.widget), "unit_price",
                Edge.Relation.DERIVED_VALUE, "USER");                     // line re-priced from v2
        List<FieldConflict> all = run(s);
        FieldConflict price = find(all, s.lineKey(), "unit_price");
        assertEquals(ConflictKind.SOURCE_VERSION_MISMATCH, price.kind());
        assertEquals(FieldConflict.Recommendation.PRESERVE_LATER_CHANGE, price.recommendation());
        assertTrue(price.explanation().contains("product:" + s.widget + "@2"), price.explanation());
    }

    @Test
    void aNonPersonChangeWithoutProvenanceIsOfUnknownOrigin() {
        Story s = new Story();
        s.sim.systemEdit(s.lineKey(), "unit_price", "480");
        FieldConflict price = find(run(s), s.lineKey(), "unit_price");
        assertEquals(ConflictKind.UNKNOWN_CHANGE_ORIGIN, price.kind());
        assertEquals("SYSTEM", price.laterActorKind());
        assertEquals(FieldConflict.Recommendation.HUMAN_REVIEW, price.recommendation());
    }

    @Test
    void repairedByRecoveryNeedsNothing() {
        Story s = new Story();
        s.sim.recoveryWrite(s.lineKey(), Map.of("unit_price", "5000", "line_total", "15000"), List.of());
        FieldConflict price = find(run(s), s.lineKey(), "unit_price");
        assertEquals(ConflictKind.NO_CONFLICT, price.kind());
        assertEquals(FieldConflict.Recommendation.NONE, price.recommendation());
    }

    @Test
    void inferredOnlyProvenanceRequiresReview() {
        Story s = new Story();
        IncidentGraph lossy = s.sim.graphWithEdgeLoss(0.0, 1);   // same graph; now mark every edge as backfilled
        List<Edge> backfilled = lossy.edges().stream()
                .map(e -> new Edge(e.source(), e.sourceField(), e.target(), e.targetField(), e.relation(), false)).toList();
        IncidentGraph g = new IncidentGraph(backfilled, s.sim.histories());
        List<FieldConflict> all = conflicts.analyze(s.fault(), analyzer.analyze(s.fault(), g), g);
        FieldConflict price = find(all, s.lineKey(), "unit_price");
        assertEquals(ConflictKind.REQUIRES_MANUAL_REVIEW, price.kind());
        assertTrue(price.needsReview());
    }

    @Test
    void aRecordThatMovedOnAfterPlanningIsAConcurrentModification() {
        Story s = new Story();
        IncidentGraph g = s.sim.graph();
        RecoveryPlan plan = planner.plan(analyzer.analyze(s.fault(), g), g);
        long planned = s.sim.version(SimulatedCommerce.order(s.order));
        s.sim.edit(SimulatedCommerce.order(s.order), "delivery_note", "changed while the plan waited");

        Map<EntityKey, Long> observed = new java.util.HashMap<>();
        plan.steps().forEach(st -> observed.put(st.key(), s.sim.version(st.key())));
        List<FieldConflict> concurrent = conflicts.concurrent(plan, observed);
        assertEquals(1, concurrent.size(), concurrent.toString());
        FieldConflict c = concurrent.get(0);
        assertEquals(ConflictKind.CONCURRENT_MODIFICATION, c.kind());
        assertEquals(SimulatedCommerce.order(s.order), c.key());
        assertEquals(Long.valueOf(planned + 1), c.laterVersion());
        assertTrue(conflicts.concurrent(plan, Map.of()).isEmpty(), "unknown observations are not invented");
    }

    @Test
    void sameInputGivesTheSameAnswer() {
        Story s = new Story();
        s.sim.edit(SimulatedCommerce.order(s.order), "delivery_note", "x");
        assertEquals(run(s), run(s));
    }
}
