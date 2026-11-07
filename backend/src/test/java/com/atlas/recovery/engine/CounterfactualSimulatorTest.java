package com.atlas.recovery.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atlas.provenance.VersionRef;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** ATLAS X Milestone 4: counterfactual simulation of six strategies on simulated histories. */
class CounterfactualSimulatorTest {

    private final CounterfactualSimulator simulator = new CounterfactualSimulator();

    /**
     * Widget 5000. An early order is priced before the fault; the price is mistyped as 500;
     * two orders then consume 500 (one is confirmed and invoiced, and the invoice issued).
     */
    private static final class World {
        final SimulatedCommerce sim = new SimulatedCommerce(11);
        final UUID widget = sim.createProduct("P100", "5000");
        final UUID tee = sim.createProduct("T-100", "900");
        final UUID early;
        final UUID earlyLine;
        final UUID draft;
        final UUID draftLine;
        final UUID billed;
        final UUID invoice;

        World() {
            early = sim.createOrder();
            earlyLine = sim.addLine(early, widget, 1);
            sim.addLine(early, tee, 1);
            sim.updateProduct(widget, "unit_price", "500", "USER");      // the fault
            draft = sim.createOrder();
            draftLine = sim.addLine(draft, widget, 2);
            billed = sim.createOrder();
            sim.addLine(billed, widget, 1);
            sim.confirm(billed);
            invoice = sim.invoice(billed);
            sim.setInvoiceStatus(invoice, "ISSUED");
        }

        SimulationInput input() {
            Map<VersionRef, Long> seq = new HashMap<>();
            sim.writeSeq.forEach((k, list) -> {
                for (int v = 0; v < list.size(); v++) {
                    seq.put(k.at(v), list.get(v));
                }
            });
            return new SimulationInput(sim.faultyChange(widget, 1, "unit_price"), sim.graph(), seq, Map.of(), Set.of());
        }
    }

    private static StrategyResult of(CounterfactualSimulator.Report r, RecoveryStrategy s) {
        return r.results().stream().filter(x -> x.strategy() == s).findFirst().orElseThrow();
    }

    @Test
    void pascarRepairsEverythingWithoutCollateralOrBrokenRules() {
        World w = new World();
        StrategyResult f = of(simulator.simulate(w.input()), RecoveryStrategy.PROVENANCE_CONFLICT_AWARE);
        assertTrue(f.applicable());
        assertTrue(f.faultyRecords() >= 5, "product, 2 lines, 2 orders, issued invoice + line: " + f);
        assertEquals(f.faultyRecords(), f.repaired(), f.toString());
        assertEquals(0, f.collateralModified());
        assertEquals(0, f.legitimateOverwritten());
        assertEquals(0, f.invariantViolations(), f.violations().toString());
        assertEquals(1, f.compensations(), "the undercharged issued invoice gets a supplementary charge, not an edit");
        assertEquals(0, f.recordsDeleted());
    }

    @Test
    void foreignKeyRepairAlsoRewritesTheOrderPricedBeforeTheFault() {
        World w = new World();
        CounterfactualSimulator.Report r = simulator.simulate(w.input());
        StrategyResult d = of(r, RecoveryStrategy.FIELD_LEVEL_FK);
        assertTrue(d.applicable());
        // The early line (priced 5000, from v0) is "fixed" to 5000 again: no change, but its order is recomputed;
        // the issued invoice is edited in place: a legal-finality violation.
        assertTrue(d.violations().stream().anyMatch(v -> v.contains("legally final")), d.violations().toString());
        assertTrue(d.recordsInspected() > of(r, RecoveryStrategy.PROVENANCE_CONFLICT_AWARE).recordsModified());
        assertTrue(d.repaired() < d.faultyRecords(), "edited final records do not count as repaired");
    }

    @Test
    void restoresDestroyWorkDoneAfterTheFault() {
        World w = new World();
        w.sim.edit(SimulatedCommerce.order(w.draft), "delivery_note", "Leave at gate 2");   // legitimate later edit
        CounterfactualSimulator.Report r = simulator.simulate(w.input());
        StrategyResult a = of(r, RecoveryStrategy.FULL_RESTORE);
        StrategyResult c = of(r, RecoveryStrategy.VERSION_ROW_RESTORE);
        StrategyResult f = of(r, RecoveryStrategy.PROVENANCE_CONFLICT_AWARE);
        assertTrue(a.recordsDeleted() > 0, "orders created after the fault disappear: " + a);
        assertTrue(a.legitimateOverwritten() > 0);
        assertTrue(c.recordsDeleted() > 0, "lines created with the faulty price have no earlier version: " + c);
        assertTrue(c.invariantViolations() > 0, c.violations().toString());
        assertEquals(0, f.legitimateOverwritten(), "field-level repair keeps the delivery note");
        assertEquals(f.faultyRecords(), f.repaired());
    }

    @Test
    void conflictsAreSurfacedByPascarAndIgnoredWithoutConflictHandling() {
        World w = new World();
        w.sim.edit(new EntityKey("order_line", w.draftLine), "unit_price", "450");   // a person changed the faulty price
        CounterfactualSimulator.Report r = simulator.simulate(w.input());
        StrategyResult e = of(r, RecoveryStrategy.PROVENANCE_CONFLICT_BLIND);
        StrategyResult f = of(r, RecoveryStrategy.PROVENANCE_CONFLICT_AWARE);
        assertTrue(f.conflictsSurfaced() >= 1);
        assertEquals(0, f.conflictsIgnored());
        assertEquals(0, e.conflictsSurfaced());
        assertEquals(f.conflictsSurfaced(), e.conflictsIgnored());
        assertTrue(f.manualSteps() > e.manualSteps(), "PASCAR asks a person; the blind variant does not");
        // Ignoring the conflict is not harmless: the order total is recomputed around a line that was not.
        assertEquals(0, f.invariantViolations(), f.violations().toString());
        assertTrue(e.invariantViolations() > 0, "the blind variant breaks the order total: " + e);
    }

    @Test
    void aTotalWaitingForAPersonHoldsItsOtherLinesToo() {
        // Found by the Milestone 8 experiment: two faulty lines on one order, a person re-priced one.
        // Correcting only the other line would leave the order total disagreeing with its lines.
        World w = new World();
        UUID second = w.sim.addLine(w.draft, w.widget, 3);
        w.sim.edit(new EntityKey("order_line", w.draftLine), "unit_price", "450");
        SimulationInput in = w.input();
        CounterfactualSimulator.Report r = simulator.simulate(in);
        StrategyResult f = of(r, RecoveryStrategy.PROVENANCE_CONFLICT_AWARE);
        assertEquals(0, f.invariantViolations(), f.violations().toString());
        assertEquals(0, f.collateralModified());
        assertTrue(f.repaired() < f.faultyRecords(), "the held line and its order wait for the decision: " + f);

        RecoveryPlan plan = new PlanGenerator().plan(new ImpactAnalyzer().analyze(in.faulty(), in.graph()), in.graph());
        RecoveryPlan.Step review = plan.steps().stream()
                .filter(st -> st.key().entityId().equals(w.draftLine)).findFirst().orElseThrow();
        RecoveryPlan.Step sibling = plan.steps().stream()
                .filter(st -> st.key().entityId().equals(second)).findFirst().orElseThrow();
        assertEquals(RecoveryPlan.Action.MANUAL_REVIEW, review.action());
        assertTrue(sibling.dependsOn().contains(review.number()), "sibling waits for the review: " + sibling);
        assertTrue(PlanGenerator.waitingSteps(plan.steps(), in.faulty().key(), Set.of()).contains(sibling.number()));
    }

    @Test
    void reviewedConflictsAreNoLongerCountedAsOpen() {
        World w = new World();
        EntityKey line = new EntityKey("order_line", w.draftLine);
        w.sim.edit(line, "unit_price", "450");
        SimulationInput base = w.input();
        SimulationInput reviewed = new SimulationInput(base.faulty(), base.graph(), base.sequence(), base.credited(),
                Set.of(line + ".unit_price"));
        assertEquals(of(simulator.simulate(base), RecoveryStrategy.PROVENANCE_CONFLICT_AWARE).conflictsSurfaced() - 1,
                of(simulator.simulate(reviewed), RecoveryStrategy.PROVENANCE_CONFLICT_AWARE).conflictsSurfaced());
    }

    private static StrategyRecords records(CounterfactualSimulator.Report r, RecoveryStrategy s) {
        return r.recordSets().get(s);
    }

    private static StrategyRecords.RecordDetail detail(StrategyRecords rs, EntityKey k) {
        return rs.stored().stream().filter(d -> d.key().equals(k)).findFirst().orElseThrow(() -> new AssertionError(k + " not in " + rs));
    }

    @Test
    void recordLevelResultsAgreeWithTheAggregateMetrics() {
        World w = new World();
        EntityKey draft = SimulatedCommerce.order(w.draft);
        EntityKey line = new EntityKey("order_line", w.draftLine);
        w.sim.edit(draft, "delivery_note", "Gate 2");          // a legitimate later change
        w.sim.edit(line, "unit_price", "450");                // a same-field conflict
        CounterfactualSimulator.Report r = simulator.simulate(w.input());
        for (RecoveryStrategy s : RecoveryStrategy.values()) {
            StrategyResult res = of(r, s);
            StrategyRecords rs = records(r, s);
            assertFalse(rs.truncated(), s + " fits in the default limit");
            assertEquals(rs.total(), rs.stored().size());
            assertEquals(res.recordsModified(), rs.modified(), s + " modified");
            assertEquals(res.recordsInspected(), rs.inspected(), s + " inspected");
            assertEquals(rs.modified(), rs.stored().stream().filter(StrategyRecords.RecordDetail::modified).count());
            assertEquals(rs.unresolved(), rs.stored().stream().filter(StrategyRecords.RecordDetail::unresolved).count());
            // Unresolved = faulty records left unrepaired, plus records held for a person's review.
            assertTrue(rs.unresolved() >= res.faultyRecords() - res.repaired(), s + " unresolved " + rs);
        }
        StrategyRecords f = records(r, RecoveryStrategy.PROVENANCE_CONFLICT_AWARE);
        assertEquals("REVIEW", detail(f, line).action());
        assertEquals("SAME_FIELD_CONFLICT", detail(f, line).conflictKind());
        assertTrue(detail(f, line).conflicting() && detail(f, line).unresolved());
        assertTrue(detail(f, draft).preserved(), "F keeps the delivery note: " + detail(f, draft));
        assertEquals("HELD", detail(f, draft).action(), "the order total waits for the review of its line");
        assertTrue(f.unresolvedDependencies() > 0);
        assertFalse(detail(f, line).provenancePath().isEmpty(), "reached records carry their recorded path");
        StrategyRecords a = records(r, RecoveryStrategy.FULL_RESTORE);
        assertEquals("DELETE", detail(a, draft).action(), "the draft order was created after the fault");
        assertFalse(detail(a, draft).preserved());
        assertTrue(a.preservedLaterChanges() < f.preservedLaterChanges());
    }

    @Test
    void detailsAreCappedButCountsStayExact() {
        World w = new World();
        CounterfactualSimulator.Report full = simulator.simulate(w.input());
        CounterfactualSimulator.Report capped = new CounterfactualSimulator(CounterfactualSimulator.MAX_VERSIONS, 3)
                .simulate(w.input());
        for (RecoveryStrategy s : RecoveryStrategy.values()) {
            StrategyRecords x = records(full, s);
            StrategyRecords y = records(capped, s);
            assertEquals(x.total(), y.total(), s.name());
            assertEquals(x.modified(), y.modified());
            assertEquals(x.inspected(), y.inspected());
            assertEquals(x.preserved(), y.preserved());
            assertEquals(x.unresolved(), y.unresolved());
            if (x.total() > 3) {
                assertTrue(y.truncated(), s + " must say its details are partial");
                assertEquals(3, y.stored().size());
                assertEquals(x.total() - 3, y.omitted());
                // Acting records come first.
                long acting = x.stored().stream().filter(d -> !d.action().equals("NONE")).count();
                assertTrue(y.stored().stream().limit(Math.min(3, acting)).allMatch(d -> !d.action().equals("NONE")), y.toString());
            } else {
                assertFalse(y.truncated());
            }
        }
    }

    @Test
    void everyPairOfApplicableStrategiesIsComparedExactlyAndReproducibly() {
        World w = new World();
        w.sim.edit(SimulatedCommerce.order(w.draft), "delivery_note", "Gate 2");
        CounterfactualSimulator.Report r1 = simulator.simulate(w.input());
        CounterfactualSimulator.Report r2 = new CounterfactualSimulator(CounterfactualSimulator.MAX_VERSIONS, 2)
                .simulate(w.input());
        assertEquals(15, r1.comparisons().size(), "6 applicable strategies, 15 pairs");
        for (int i = 0; i < r1.comparisons().size(); i++) {
            StrategyComparison x = r1.comparisons().get(i);
            StrategyComparison y = r2.comparisons().get(i);
            assertEquals(x.sha256(), y.sha256(), "the hash covers every difference, not only the stored ones");
            assertEquals(x.totalDifferences(), y.totalDifferences());
            assertEquals(x.onlyA() + x.onlyB() + x.both() - x.sameAction(), x.totalDifferences());
            assertEquals(y.totalDifferences() > 2, y.truncated());
        }
        StrategyComparison af = r1.comparisons().stream().filter(c -> c.a() == RecoveryStrategy.FULL_RESTORE
                && c.b() == RecoveryStrategy.PROVENANCE_CONFLICT_AWARE).findFirst().orElseThrow();
        assertTrue(af.onlyA() > 0, "the restore deletes records F leaves alone: " + af);
        assertEquals(simulator.simulate(w.input()).comparisons().get(0).sha256(), r1.comparisons().get(0).sha256());
    }

    @Test
    void notApplicableStrategiesHaveNoRecordsAndNoComparisons() {
        World w = new World();
        w.sim.edit(SimulatedCommerce.order(w.draft), "delivery_note", "Gate 2");
        long v = w.sim.edit(SimulatedCommerce.order(w.draft), "delivery_note", "wrong note");
        SimulationInput base = w.input();
        EntityKey order = SimulatedCommerce.order(w.draft);
        SimulationInput in = new SimulationInput(new FaultyChange(order.at(v), Set.of("delivery_note"),
                Map.of("delivery_note", "Gate 2"), Map.of("delivery_note", "wrong note")),
                base.graph(), base.sequence(), Map.of(), Set.of());
        CounterfactualSimulator.Report r = simulator.simulate(in);
        assertEquals(0, records(r, RecoveryStrategy.NAIVE_FK_ROW).total());
        assertTrue(r.comparisons().stream().noneMatch(c -> c.a() == RecoveryStrategy.NAIVE_FK_ROW
                || c.b() == RecoveryStrategy.NAIVE_FK_ROW || c.a() == RecoveryStrategy.FIELD_LEVEL_FK
                || c.b() == RecoveryStrategy.FIELD_LEVEL_FK));
        assertEquals(6, r.comparisons().size(), "4 applicable strategies, 6 pairs");
    }

    @Test
    void simulationNeverChangesItsInput() {
        World w = new World();
        SimulationInput in = w.input();
        Map<EntityKey, List<VersionInfo>> before = new HashMap<>();
        in.graph().entities().forEach(k -> before.put(k, List.copyOf(in.graph().history(k))));
        Map<EntityKey, Map<String, Object>> current = new HashMap<>();
        in.graph().entities().forEach(k -> current.put(k, new HashMap<>(in.graph().current(k))));
        simulator.simulate(in);
        in.graph().entities().forEach(k -> {
            assertEquals(before.get(k), in.graph().history(k), "history of " + k);
            assertEquals(current.get(k), in.graph().current(k), "state of " + k);
        });
    }

    @Test
    void sameSnapshotGivesTheSameResults() {
        World w = new World();
        List<StrategyResult> one = simulator.simulate(w.input()).results().stream().map(StrategyResult::withoutTiming).toList();
        List<StrategyResult> two = simulator.simulate(w.input()).results().stream().map(StrategyResult::withoutTiming).toList();
        assertEquals(one, two);
        assertEquals(RecoveryStrategy.values().length, one.size());
    }

    @Test
    void foreignKeyStrategiesDoNotApplyToOtherOrigins() {
        World w = new World();
        w.sim.edit(SimulatedCommerce.order(w.draft), "delivery_note", "Gate 2");
        long v = w.sim.edit(SimulatedCommerce.order(w.draft), "delivery_note", "wrong note");
        SimulationInput base = w.input();
        EntityKey order = SimulatedCommerce.order(w.draft);
        SimulationInput in = new SimulationInput(new FaultyChange(order.at(v), Set.of("delivery_note"),
                Map.of("delivery_note", "Gate 2"), Map.of("delivery_note", "wrong note")),
                base.graph(), base.sequence(), Map.of(), Set.of());
        CounterfactualSimulator.Report r = simulator.simulate(in);
        assertFalse(of(r, RecoveryStrategy.NAIVE_FK_ROW).applicable());
        assertFalse(of(r, RecoveryStrategy.FIELD_LEVEL_FK).applicable());
        assertTrue(of(r, RecoveryStrategy.NAIVE_FK_ROW).notApplicableReason().contains("product"));
        assertTrue(of(r, RecoveryStrategy.PROVENANCE_CONFLICT_AWARE).applicable());
    }

    @Test
    void oversizedSnapshotsAreRefused() {
        World w = new World();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new CounterfactualSimulator(5).simulate(w.input()));
        assertTrue(e.getMessage().contains("Too large"));
    }
}
