package com.atlas.recovery.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atlas.provenance.VersionRef;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** PASCAR impact analysis and planning on small, fully understood histories (synthetic data). */
class PascarTest {

    private final ImpactAnalyzer analyzer = new ImpactAnalyzer();
    private final PlanGenerator planner = new PlanGenerator();

    private static EntityKey line(UUID id) {
        return new EntityKey("order_line", id);
    }

    private static Classification cls(ImpactReport r, EntityKey key) {
        return r.find(key).map(AffectedRecord::classification).orElse(null);
    }

    private static boolean eq(String expected, Object actual) {
        return Values.same(new BigDecimal(expected), actual);
    }

    /** The canonical story: P100 mistyped 5000 → 500 (v1), then an order is priced from it. */
    private static final class Story {
        final SimulatedCommerce sim = new SimulatedCommerce(42);
        final UUID widget = sim.createProduct("P100", "5000");
        final UUID tee = sim.createProduct("T-100", "900");
        final UUID earlyOrder;
        final UUID earlyLine;
        final UUID order;
        final UUID faultyLine;
        final UUID teeLine;

        Story() {
            earlyOrder = sim.createOrder();
            earlyLine = sim.addLine(earlyOrder, widget, 1);          // priced at 5000, before the fault
            sim.updateProduct(widget, "unit_price", "500", "USER");   // v1: the faulty change
            order = sim.createOrder();
            faultyLine = sim.addLine(order, widget, 3);              // 1500 instead of 15000
            teeLine = sim.addLine(order, tee, 2);                    // 1800, correct
        }

        FaultyChange fault() {
            return sim.faultyChange(widget, 1, "unit_price");
        }
    }

    @Test
    void findsExactlyTheRecordsThatConsumedTheFaultyVersion() {
        Story s = new Story();
        ImpactReport r = analyzer.analyze(s.fault(), s.sim.graph());

        assertEquals(Classification.ORIGIN, cls(r, SimulatedCommerce.product(s.widget)));
        assertEquals(Classification.DIRECTLY_AFFECTED, cls(r, line(s.faultyLine)));
        assertEquals(Classification.INDIRECTLY_AFFECTED, cls(r, SimulatedCommerce.order(s.order)));
        // Correctly priced sibling: not reached, and its own price is recorded as coming from the tee
        // product, so within this scope impact is ruled out. The earlier order (priced from v0) is not
        // in the report at all.
        assertEquals(Classification.NOT_REACHED_PROVENANCE_COMPLETE, cls(r, line(s.teeLine)));
        assertEquals(null, cls(r, line(s.earlyLine)));
        assertEquals(null, cls(r, SimulatedCommerce.order(s.earlyOrder)));
        assertEquals(null, cls(r, SimulatedCommerce.product(s.tee)));
        assertEquals(3, r.impacted().size());
        assertEquals(List.of("product:" + s.widget + "@1", "order_line:" + s.faultyLine + "@0"),
                r.find(line(s.faultyLine)).orElseThrow().path());
    }

    @Test
    void planRestoresPriceAndRecomputesLineAndTotalInDependencyOrder() {
        Story s = new Story();
        ImpactReport r = analyzer.analyze(s.fault(), s.sim.graph());
        RecoveryPlan plan = planner.plan(r, s.sim.graph());

        assertEquals(3, plan.steps().size());
        RecoveryPlan.Step restore = plan.steps().get(0);
        RecoveryPlan.Step reprice = plan.steps().get(1);
        RecoveryPlan.Step total = plan.steps().get(2);
        assertEquals(RecoveryPlan.Action.RESTORE_FIELDS, restore.action());
        assertTrue(eq("5000", restore.changes().get(0).proposed()));
        assertEquals(1L, restore.expectedVersion());

        assertEquals(line(s.faultyLine), reprice.key());
        assertEquals(List.of(1), reprice.dependsOn());
        Map<String, Object> lineChanges = changes(reprice);
        assertTrue(eq("5000", lineChanges.get("unit_price")));
        assertTrue(eq("15000", lineChanges.get("line_total")));

        assertEquals(SimulatedCommerce.order(s.order), total.key());
        assertEquals(List.of(2), total.dependsOn());
        // Inputs let the executor record fresh provenance for the rewritten records.
        assertEquals(List.of(new RecoveryPlan.Input(SimulatedCommerce.product(s.widget), "unit_price", "unit_price",
                Edge.Relation.DERIVED_VALUE)), reprice.inputs());
        assertEquals(2, total.inputs().size(), "the order total sums both lines");
        assertTrue(restore.inputs().isEmpty());
        assertTrue(eq("16800", changes(total).get("total_amount")), "15000 + the untouched 1800");
        assertEquals(RecoveryPlan.Risk.LOW, plan.risk());
        assertFalse(plan.requiresApproval());
        assertTrue(plan.invariants().stream().anyMatch(i -> i.kind().equals("TOTAL_EQUALS_SUM_OF_LINES")
                && Values.same(i.expected(), new BigDecimal("16800"))));
    }

    @Test
    void anEditToAnotherFieldCarriesTheFaultForwardButAPriceEditStopsIt() {
        Story s = new Story();
        s.sim.updateProduct(s.widget, "name", "Widget (renamed)", "USER");    // v2: price still 500
        UUID order2 = s.sim.createOrder();
        UUID lineFromV2 = s.sim.addLine(order2, s.widget, 1);
        s.sim.updateProduct(s.widget, "unit_price", "600", "USER");          // v3: someone set a new price
        UUID order3 = s.sim.createOrder();
        UUID lineFromV3 = s.sim.addLine(order3, s.widget, 1);

        ImpactReport r = analyzer.analyze(s.fault(), s.sim.graph());
        assertEquals(Classification.DIRECTLY_AFFECTED, cls(r, line(lineFromV2)), "rename does not cure the price");
        assertEquals(null, cls(r, line(lineFromV3)), "priced after the price was changed again");
        // The origin's price was edited after the fault: restoring 5000 would overwrite 600.
        assertEquals(Classification.CONFLICTED, cls(r, SimulatedCommerce.product(s.widget)));

        RecoveryPlan plan = planner.plan(r, s.sim.graph());
        assertEquals(RecoveryPlan.Action.MANUAL_REVIEW, plan.steps().get(0).action());
        assertEquals(RecoveryPlan.Risk.HIGH, plan.risk());
        assertTrue(plan.requiresApproval());
        // Lines priced at the faulty 500 are still corrected to what the price was meant to be at the time: 5000.
        RecoveryPlan.Step v2Line = plan.steps().stream().filter(st -> st.key().equals(line(lineFromV2))).findFirst().orElseThrow();
        assertTrue(eq("5000", changes(v2Line).get("unit_price")));
    }

    @Test
    void issuedInvoicesAreCompensatedDraftsUpdatedVoidsLeftAlone() {
        Story s = new Story();
        s.sim.confirm(s.order);
        UUID issued = s.sim.invoice(s.order);
        s.sim.setInvoiceStatus(issued, "ISSUED");

        UUID draftOrder = s.sim.createOrder();
        s.sim.addLine(draftOrder, s.widget, 1);
        s.sim.confirm(draftOrder);
        UUID draft = s.sim.invoice(draftOrder);

        UUID voidOrder = s.sim.createOrder();
        s.sim.addLine(voidOrder, s.widget, 1);
        s.sim.confirm(voidOrder);
        UUID voided = s.sim.invoice(voidOrder);
        s.sim.setInvoiceStatus(voided, "VOID");

        ImpactReport r = analyzer.analyze(s.fault(), s.sim.graph());
        assertEquals(Classification.NON_REVERSIBLE, cls(r, new EntityKey("invoice", issued)));
        assertEquals(Classification.INDIRECTLY_AFFECTED, cls(r, new EntityKey("invoice", draft)));
        assertEquals(Classification.NON_REVERSIBLE, cls(r, new EntityKey("invoice", voided)));

        RecoveryPlan plan = planner.plan(r, s.sim.graph());
        RecoveryPlan.Step comp = step(plan, new EntityKey("invoice", issued));
        assertEquals(RecoveryPlan.Action.SUPPLEMENTARY_CHARGE, comp.action(), "billed 3300, should be 16800");
        assertTrue(eq("13500", comp.amount()));
        assertEquals(RecoveryPlan.Action.UPDATE_FIELDS, step(plan, new EntityKey("invoice", draft)).action());
        assertTrue(eq("5000", changes(step(plan, new EntityKey("invoice", draft))).get("total_amount")));
        assertEquals(RecoveryPlan.Action.NO_ACTION, step(plan, new EntityKey("invoice", voided)).action());
        // Lines of the issued invoice get no edit step of their own.
        for (UUID il : s.sim.invoiceLines.get(issued)) {
            assertTrue(plan.steps().stream().noneMatch(st -> st.key().equals(new EntityKey("invoice_line", il))));
        }
        assertEquals(RecoveryPlan.Risk.HIGH, plan.risk());
    }

    @Test
    void overBillingBecomesACreditNote() {
        SimulatedCommerce sim = new SimulatedCommerce(7);
        UUID p = sim.createProduct("P1", "100");
        sim.updateProduct(p, "unit_price", "1000", "USER");     // faulty: ten times too high
        UUID o = sim.createOrder();
        sim.addLine(o, p, 2);
        sim.confirm(o);
        UUID inv = sim.invoice(o);
        sim.setInvoiceStatus(inv, "ISSUED");
        RecoveryPlan plan = planner.plan(analyzer.analyze(sim.faultyChange(p, 1, "unit_price"), sim.graph()), sim.graph());
        RecoveryPlan.Step comp = step(plan, new EntityKey("invoice", inv));
        assertEquals(RecoveryPlan.Action.ISSUE_CREDIT_NOTE, comp.action());
        assertTrue(eq("1800", comp.amount()));
    }

    @Test
    void aSiblingWithAnUnrecordedInputCannotBeRuledOut() {
        Story s = new Story();
        // A person re-priced the tee line by hand: a new version of unit_price with no recorded input.
        s.sim.edit(line(s.teeLine), "unit_price", "850");
        ImpactReport r = analyzer.analyze(s.fault(), s.sim.graph());
        AffectedRecord tee = r.find(line(s.teeLine)).orElseThrow();
        assertEquals(Classification.NOT_REACHED_PROVENANCE_INCOMPLETE, tee.classification());
        assertTrue(tee.reason().contains("unit_price@v1"), tee.reason());
        assertEquals(3, r.impacted().size(), "context records never become plan steps");
        assertTrue(new PlanGenerator().plan(r, s.sim.graph()).steps().stream()
                .noneMatch(st -> st.key().equals(line(s.teeLine))));
    }

    @Test
    void gapsSuppliedByTheLoaderDecideCompleteness() {
        Story s = new Story();
        IncidentGraph full = s.sim.graph();
        // The loader found a gap on the tee line the in-memory edges do not show.
        IncidentGraph withGap = full.withGaps(List.of(new ProvenanceRequirements.Gap(line(s.teeLine), 0, "unit_price",
                "priced from a product version (order.add_line)")));
        assertEquals(Classification.NOT_REACHED_PROVENANCE_INCOMPLETE, cls(analyzer.analyze(s.fault(), withGap), line(s.teeLine)));
        // And with no gaps supplied for it, the same record is complete.
        assertEquals(Classification.NOT_REACHED_PROVENANCE_COMPLETE,
                cls(analyzer.analyze(s.fault(), full.withGaps(List.of())), line(s.teeLine)));
    }

    @Test
    void aGraphMissingTheSiblingsInputsErrsTowardsIncomplete() {
        Story s = new Story();
        IncidentGraph full = s.sim.graph();
        // A loader that drops the edges INTO the sibling and supplies no gaps: cannot claim completeness.
        List<Edge> kept = full.edges().stream().filter(e -> !EntityKey.of(e.target()).equals(line(s.teeLine))).toList();
        Map<EntityKey, List<VersionInfo>> h = new java.util.HashMap<>();
        full.entities().forEach(k -> h.put(k, full.history(k)));
        assertEquals(Classification.NOT_REACHED_PROVENANCE_INCOMPLETE,
                cls(analyzer.analyze(s.fault(), new IncidentGraph(kept, h)), line(s.teeLine)));
    }

    @Test
    void zeroTotalsNeedNoInputs() {
        SimulatedCommerce sim = new SimulatedCommerce(5);
        UUID p = sim.createProduct("P", "100");
        UUID empty = sim.createOrder();                 // total 0, no lines: no input required
        EntityKey k = SimulatedCommerce.order(empty);
        assertTrue(ProvenanceRequirements.gaps(k, sim.graph()).isEmpty(), ProvenanceRequirements.gaps(k, sim.graph()).toString());
        sim.edit(k, "total_amount", "40");              // a non-zero total set by hand: a gap
        assertEquals(1, ProvenanceRequirements.gaps(k, sim.graph()).size());
        assertTrue(p != null);
    }

    @Test
    void aRecordAlreadyRepairedByRecoveryIsNotReportedAgain() {
        Story s = new Story();
        // A previous recovery re-priced the line (actor RECOVERY): it no longer carries the fault.
        EntityKey l = line(s.faultyLine);
        Map<String, Object> repaired = new java.util.LinkedHashMap<>(s.sim.current(l));
        repaired.put("unit_price", new BigDecimal("5000.0000"));
        repaired.put("line_total", new BigDecimal("15000.0000"));
        java.util.List<VersionInfo> versions = new java.util.ArrayList<>(s.sim.histories().get(l));
        versions.add(new VersionInfo(1, "UPDATE", "RECOVERY", Set.of("unit_price", "line_total"), repaired));
        s.sim.histories().put(l, versions);
        ImpactReport r = analyzer.analyze(s.fault(), s.sim.graph());
        assertEquals(Classification.NO_LONGER_AFFECTED, cls(r, l), "reached, but no longer carrying the fault");
        assertEquals("Already repaired by an earlier recovery", r.find(l).orElseThrow().reason());
    }

    @Test
    void cyclesTerminateAndNeedAPerson() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        EntityKey ka = new EntityKey("product", a);
        EntityKey kb = new EntityKey("order_line", b);
        Map<EntityKey, List<VersionInfo>> h = Map.of(
                ka, List.of(new VersionInfo(0, "INSERT", "USER", Set.of("unit_price"), Map.of("unit_price", BigDecimal.ONE)),
                        new VersionInfo(1, "UPDATE", "USER", Set.of("unit_price"), Map.of("unit_price", BigDecimal.TEN))),
                kb, List.of(new VersionInfo(0, "INSERT", "USER", Set.of("unit_price"), Map.of("unit_price", BigDecimal.TEN))));
        List<Edge> edges = List.of(
                new Edge(ka.at(1), "unit_price", kb.at(0), "unit_price", Edge.Relation.DERIVED_VALUE, true),
                new Edge(kb.at(0), "unit_price", ka.at(1), "unit_price", Edge.Relation.DERIVED_VALUE, true));
        FaultyChange f = new FaultyChange(ka.at(1), Set.of("unit_price"), Map.of("unit_price", BigDecimal.ONE),
                Map.of("unit_price", BigDecimal.TEN));
        ImpactReport r = analyzer.analyze(f, new IncidentGraph(edges, h));   // must return
        assertEquals(Classification.REQUIRES_MANUAL_REVIEW, cls(r, kb));
        assertEquals(Set.of(ka, kb), ImpactAnalyzer.cycleMembers(Map.of(ka, Set.of(kb), kb, Set.of(ka))));
    }

    @Test
    void longChainsDoNotOverflowTheStack() {
        int n = 50_000;
        Map<EntityKey, List<VersionInfo>> h = new java.util.HashMap<>();
        List<Edge> edges = new java.util.ArrayList<>();
        EntityKey prev = null;
        EntityKey first = null;
        for (int i = 0; i < n; i++) {
            EntityKey k = new EntityKey(i == 0 ? "product" : "order_line", new UUID(0, i));
            h.put(k, i == 0
                    ? List.of(new VersionInfo(0, "INSERT", "USER", Set.of("unit_price"), Map.of("unit_price", BigDecimal.ONE)),
                    new VersionInfo(1, "UPDATE", "USER", Set.of("unit_price"), Map.of("unit_price", BigDecimal.TEN)))
                    : List.of(new VersionInfo(0, "INSERT", "USER", Set.of("unit_price"), Map.of("unit_price", BigDecimal.TEN))));
            if (prev != null) {
                edges.add(new Edge(prev.at(prev.entityType().equals("product") ? 1 : 0), "unit_price", k.at(0), "unit_price",
                        Edge.Relation.COPIED_INTO, true));
            } else {
                first = k;
            }
            prev = k;
        }
        edges.add(new Edge(prev.at(0), "unit_price", first.at(1), "unit_price", Edge.Relation.COPIED_INTO, true)); // closes a huge cycle
        FaultyChange f = new FaultyChange(first.at(1), Set.of("unit_price"), Map.of("unit_price", BigDecimal.ONE),
                Map.of("unit_price", BigDecimal.TEN));
        ImpactReport r = analyzer.analyze(f, new IncidentGraph(edges, h));
        assertEquals(n, r.records().size());
        assertEquals(Classification.REQUIRES_MANUAL_REVIEW, cls(r, prev));
        AffectedRecord last = r.find(prev).orElseThrow();
        assertEquals(n - 1, last.depth());
        assertEquals(ImpactAnalyzer.MAX_PATH + 1, last.path().size());
        assertEquals("…", last.path().get(0));
    }

    @Test
    void backfilledOnlyEvidenceIsNeverRepairedAutomatically() {
        Story s = new Story();
        UUID extra = UUID.randomUUID();
        EntityKey k = new EntityKey("order_line", extra);
        Map<EntityKey, List<VersionInfo>> h = new java.util.HashMap<>(s.sim.histories());
        h.put(k, List.of(new VersionInfo(0, "INSERT", "USER", Set.of("unit_price"),
                Map.of("unit_price", new BigDecimal("500"), "quantity", 1, "order_id", s.order.toString()))));
        List<Edge> edges = new java.util.ArrayList<>(s.sim.graph().edges());
        edges.add(new Edge(SimulatedCommerce.product(s.widget).at(1), "unit_price", k.at(0), "unit_price",
                Edge.Relation.DERIVED_VALUE, false));
        ImpactReport r = analyzer.analyze(s.fault(), new IncidentGraph(edges, h));
        assertEquals(Classification.REQUIRES_MANUAL_REVIEW, cls(r, k));
        assertEquals(Classification.DIRECTLY_AFFECTED, cls(r, line(s.faultyLine)));
    }

    @Test
    void edgesAreFollowedOnlyForTheFaultyField() {
        Story s = new Story();
        UUID other = UUID.randomUUID();
        EntityKey k = new EntityKey("order_line", other);
        Map<EntityKey, List<VersionInfo>> h = new java.util.HashMap<>(s.sim.histories());
        h.put(k, List.of(new VersionInfo(0, "INSERT", "USER", Set.of("unit_cost"), Map.of("unit_cost", BigDecimal.ONE))));
        List<Edge> edges = new java.util.ArrayList<>(s.sim.graph().edges());
        edges.add(new Edge(SimulatedCommerce.product(s.widget).at(1), "unit_cost", k.at(0), "unit_cost",
                Edge.Relation.DERIVED_VALUE, true));
        ImpactReport r = analyzer.analyze(s.fault(), new IncidentGraph(edges, h));
        assertEquals(null, cls(r, k), "a unit_cost edge does not carry a unit_price fault");
    }

    @Test
    void fingerprintChangesWhenAnyReadRecordChangesAndOnlyThen() {
        Story s = new Story();
        String before = analyzer.analyze(s.fault(), s.sim.graph()).fingerprint();
        assertEquals(before, analyzer.analyze(s.fault(), s.sim.graph()).fingerprint());
        s.sim.confirm(s.order);
        assertFalse(before.equals(analyzer.analyze(s.fault(), s.sim.graph()).fingerprint()));
    }

    @Test
    void rejectsAFaultThatIsNotInTheGraph() {
        Story s = new Story();
        FaultyChange missing = new FaultyChange(new VersionRef("product", UUID.randomUUID(), 1), Set.of("unit_price"),
                Map.of("unit_price", BigDecimal.ONE), Map.of("unit_price", BigDecimal.TEN));
        assertThrows(IllegalArgumentException.class, () -> analyzer.analyze(missing, s.sim.graph()));
        assertThrows(IllegalArgumentException.class, () -> new FaultyChange(missing.ref(), Set.of("unit_price"),
                Map.of(), Map.of()));
    }

    private static RecoveryPlan.Step step(RecoveryPlan plan, EntityKey key) {
        return plan.steps().stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow();
    }

    private static Map<String, Object> changes(RecoveryPlan.Step step) {
        Map<String, Object> m = new java.util.HashMap<>();
        step.changes().forEach(c -> m.put(c.field(), c.proposed()));
        return m;
    }
}
