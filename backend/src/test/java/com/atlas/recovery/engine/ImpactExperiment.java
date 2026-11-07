package com.atlas.recovery.engine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Phase 5 experiment (SYNTHETIC data, seeded, reproducible).
 *
 * <p>Generates a commerce history in which one popular product's price is
 * mistyped (÷10) part-way through, then compares the records PASCAR selects
 * with a ground-truth ORACLE that never reads provenance edges: it uses only
 * the simulator's domain facts (which product version priced each line, which
 * lines went into which invoice).
 *
 * <p>Baselines: NAIVE_FK follows foreign keys (every line of the product,
 * their orders and invoices); FULL_RESTORE rewinds every record written after
 * the fault. EDGE_LOSS drops a fraction of provenance edges to show how
 * PASCAR depends on complete instrumentation.
 *
 * <p>Run: java ... com.atlas.recovery.engine.ImpactExperiment orders seed [scenario]
 */
public final class ImpactExperiment {

    public enum Scenario {
        /** The fault is still live when the incident is opened. */
        LIVE,
        /** Someone later set a new (legitimate) price on the same product: an origin conflict. */
        OVERWRITTEN,
        /** Like LIVE, but the price was typed at ten times its value: issued invoices are over-billed (credit notes). */
        OVERBILLED
    }

    public record Metrics(String method, int selected, int truth, int truePositives, double precision, double recall,
                          int collateral) {
    }

    public record Result(int orders, long seed, Scenario scenario, int versions, int edges, int truthSize,
                         List<Metrics> metrics, int ordersChecked, int orderTotalMismatches, int compensationsChecked,
                         int compensationMismatches, long analysisMillis, long planMillis, int planSteps,
                         Map<String, Long> classificationCounts) {
    }

    public record Truth(Set<EntityKey> affected, Map<EntityKey, BigDecimal> correctTotals, Set<EntityKey> naive,
                         Set<EntityKey> fullRestore) {
    }

    /** One generated history: the simulator after the run, the fault, and the oracle's ground truth. */
    public record World(SimulatedCommerce sim, UUID faultyProduct, long faultyVersion, FaultyChange fault, Truth truth) {
    }

    public static Result run(int orderCount, long seed, Scenario scenario, double[] edgeLoss) {
        World w = generate(orderCount, seed, scenario);
        return measure(w, orderCount, seed, scenario, edgeLoss);
    }

    public static World generate(int orderCount, long seed, Scenario scenario) {
        Random r = new Random(seed);
        SimulatedCommerce sim = new SimulatedCommerce(seed);
        int productCount = Math.max(20, orderCount / 50);
        List<UUID> products = new ArrayList<>();
        Map<UUID, BigDecimal> correctPrice = new LinkedHashMap<>();
        for (int i = 0; i < productCount; i++) {
            BigDecimal price = BigDecimal.valueOf(100 + 10L * r.nextInt(990));
            UUID p = sim.createProduct("SKU-" + i, price.toPlainString());
            products.add(p);
            correctPrice.put(p, price);
        }
        UUID faultyProduct = products.get(0);
        BigDecimal intended = correctPrice.get(faultyProduct);
        long faultyVersion = -1;
        long faultSeq = -1;
        Set<Long> taintedVersions = new TreeSet<>();
        boolean faultLive = false;

        List<UUID> orders = new ArrayList<>();
        for (int i = 0; i < orderCount; i++) {
            if (i == orderCount * 3 / 10) {
                faultyVersion = sim.updateProduct(faultyProduct, "unit_price",
                        (scenario == Scenario.OVERBILLED ? intended.multiply(BigDecimal.TEN)
                                : intended.divide(BigDecimal.TEN)).toPlainString(), "USER");
                faultSeq = sim.seq();
                taintedVersions.add(faultyVersion);
                faultLive = true;
            }
            if (i == orderCount / 2) {    // a rename: the price stays faulty, taint must carry forward
                long v = sim.updateProduct(faultyProduct, "name", "Item SKU-0 (renamed)", "USER");
                if (faultLive) {
                    taintedVersions.add(v);
                }
            }
            if (scenario == Scenario.OVERWRITTEN && i == orderCount * 7 / 10) {
                BigDecimal newPrice = intended.multiply(new BigDecimal("1.10"));
                sim.updateProduct(faultyProduct, "unit_price", newPrice.toPlainString(), "USER");
                correctPrice.put(faultyProduct, newPrice);
                faultLive = false;
            }
            if (r.nextInt(100) < 5) {     // unrelated, legitimate price changes elsewhere
                UUID other = products.get(1 + r.nextInt(productCount - 1));
                BigDecimal np = BigDecimal.valueOf(100 + 10L * r.nextInt(990));
                sim.updateProduct(other, "unit_price", np.toPlainString(), "USER");
                correctPrice.put(other, np);
            }
            UUID order = sim.createOrder();
            orders.add(order);
            int lineCount = 1 + r.nextInt(4);
            for (int l = 0; l < lineCount; l++) {
                UUID p = r.nextInt(100) < 15 ? faultyProduct : products.get(1 + r.nextInt(productCount - 1));
                sim.addLine(order, p, 1 + r.nextInt(5));
            }
            if (r.nextInt(100) < 70) {
                sim.confirm(order);
                if (r.nextInt(100) < 60) {
                    UUID inv = sim.invoice(order);
                    int roll = r.nextInt(100);
                    if (roll < 50) {
                        sim.setInvoiceStatus(inv, "ISSUED");
                    } else if (roll < 60) {
                        sim.setInvoiceStatus(inv, "VOID");
                    }
                }
            }
        }

        Truth truth = oracle(sim, faultyProduct, intended, taintedVersions, faultSeq);
        return new World(sim, faultyProduct, faultyVersion, sim.faultyChange(faultyProduct, faultyVersion, "unit_price"), truth);
    }

    private static Result measure(World w, int orderCount, long seed, Scenario scenario, double[] edgeLoss) {
        SimulatedCommerce sim = w.sim();
        Truth truth = w.truth();
        FaultyChange fault = w.fault();
        IncidentGraph graph = sim.graph();

        long t0 = System.nanoTime();
        ImpactReport report = new ImpactAnalyzer().analyze(fault, graph);
        long t1 = System.nanoTime();
        RecoveryPlan plan = new PlanGenerator().plan(report, graph);
        long t2 = System.nanoTime();

        List<Metrics> metrics = new ArrayList<>();
        metrics.add(score("PASCAR", keys(report), truth.affected));
        metrics.add(score("NAIVE_FK", truth.naive, truth.affected));
        metrics.add(score("FULL_RESTORE", truth.fullRestore, truth.affected));
        for (double loss : edgeLoss) {
            IncidentGraph lossy = sim.graphWithEdgeLoss(loss, seed * 31 + Math.round(loss * 1000));
            metrics.add(score(String.format(Locale.ROOT, "PASCAR_EDGE_LOSS_%.0f%%", loss * 100),
                    keys(new ImpactAnalyzer().analyze(fault, lossy)), truth.affected));
        }

        // Value accuracy: planned totals and compensation amounts versus the oracle's own arithmetic.
        int ordersChecked = 0;
        int orderMismatch = 0;
        int compChecked = 0;
        int compMismatch = 0;
        for (RecoveryPlan.Step step : plan.steps()) {
            EntityKey k = step.key();
            BigDecimal expected = truth.correctTotals.get(k);
            if (expected == null) {
                continue;
            }
            Map<String, Object> current = graph.current(k);
            if (step.action() == RecoveryPlan.Action.UPDATE_FIELDS || step.action() == RecoveryPlan.Action.MANUAL_REVIEW) {
                Object proposed = step.changes().stream().filter(c -> c.field().equals("total_amount"))
                        .map(RecoveryPlan.FieldChange::proposed).findFirst().orElse(current.get("total_amount"));
                ordersChecked++;
                if (!Values.same(proposed, expected)) {
                    orderMismatch++;
                }
            } else if (step.action() == RecoveryPlan.Action.ISSUE_CREDIT_NOTE
                    || step.action() == RecoveryPlan.Action.SUPPLEMENTARY_CHARGE) {
                compChecked++;
                BigDecimal owed = expected.subtract(Values.decimal(current.get("total_amount"))).abs();
                if (!Values.same(owed, step.amount())) {
                    compMismatch++;
                }
            }
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        report.counts().forEach((c, n) -> counts.put(c.name(), n));
        return new Result(orderCount, seed, scenario, sim.versionCount(), sim.edgeCount(), truth.affected.size(), metrics,
                ordersChecked, orderMismatch, compChecked, compMismatch, (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000,
                plan.steps().size(), counts);
    }

    private static Set<EntityKey> keys(ImpactReport report) {
        Set<EntityKey> out = new HashSet<>();
        report.impacted().forEach(a -> out.add(a.key()));
        return out;
    }

    /** Ground truth from domain facts only. No provenance edge is read here. */
    private static Truth oracle(SimulatedCommerce sim, UUID faultyProduct, BigDecimal intended, Set<Long> tainted,
                                long faultSeq) {
        Set<EntityKey> affected = new HashSet<>();
        Set<EntityKey> naive = new HashSet<>();
        Map<EntityKey, BigDecimal> totals = new LinkedHashMap<>();
        affected.add(SimulatedCommerce.product(faultyProduct));
        naive.add(SimulatedCommerce.product(faultyProduct));
        Set<UUID> affectedLines = new HashSet<>();
        Set<UUID> productLines = new HashSet<>();
        for (SimulatedCommerce.LineFact f : sim.lines.values()) {
            if (f.productId().equals(faultyProduct)) {
                productLines.add(f.lineId());
                if (tainted.contains(f.pricedFromVersion())) {
                    affectedLines.add(f.lineId());
                }
            }
        }
        for (var e : sim.orderLines.entrySet()) {
            UUID order = e.getKey();
            boolean hit = false;
            boolean fk = false;
            BigDecimal correct = BigDecimal.ZERO;
            for (UUID line : e.getValue()) {
                EntityKey lk = new EntityKey("order_line", line);
                Map<String, Object> ls = sim.current(lk);
                BigDecimal price = affectedLines.contains(line) ? intended : Values.decimal(ls.get("unit_price"));
                correct = correct.add(price.multiply(Values.decimal(ls.get("quantity"))));
                if (affectedLines.contains(line)) {
                    hit = true;
                    affected.add(lk);
                }
                if (productLines.contains(line)) {
                    fk = true;
                    naive.add(lk);
                }
            }
            if (hit) {
                affected.add(SimulatedCommerce.order(order));
                totals.put(SimulatedCommerce.order(order), correct);
            }
            if (fk) {
                naive.add(SimulatedCommerce.order(order));
            }
            UUID invoice = sim.invoiceOfOrder.get(order);
            if (invoice != null) {
                EntityKey ik = new EntityKey("invoice", invoice);
                if (hit) {
                    affected.add(ik);
                    totals.put(ik, correct);
                }
                if (fk) {
                    naive.add(ik);
                }
                for (UUID il : sim.invoiceLines.get(invoice)) {
                    UUID source = sim.invoiceLineSource.get(il);
                    if (affectedLines.contains(source)) {
                        affected.add(new EntityKey("invoice_line", il));
                    }
                    if (productLines.contains(source)) {
                        naive.add(new EntityKey("invoice_line", il));
                    }
                }
            }
        }
        Set<EntityKey> rewound = new HashSet<>();
        sim.writeSeq.forEach((key, seqs) -> {
            if (seqs.get(seqs.size() - 1) >= faultSeq) {
                rewound.add(key);
            }
        });
        return new Truth(affected, totals, naive, rewound);
    }

    private static Metrics score(String method, Set<EntityKey> selected, Set<EntityKey> truth) {
        int tp = 0;
        for (EntityKey k : selected) {
            if (truth.contains(k)) {
                tp++;
            }
        }
        double precision = selected.isEmpty() ? 1.0 : (double) tp / selected.size();
        double recall = truth.isEmpty() ? 1.0 : (double) tp / truth.size();
        return new Metrics(method, selected.size(), truth.size(), tp, precision, recall, selected.size() - tp);
    }

    public static String json(Result r) {
        StringBuilder m = new StringBuilder();
        for (Metrics x : r.metrics()) {
            if (m.length() > 0) {
                m.append(',');
            }
            m.append(String.format(Locale.ROOT,
                    "{\"method\":\"%s\",\"selected\":%d,\"truth\":%d,\"truePositives\":%d,\"precision\":%.4f,\"recall\":%.4f,\"collateral\":%d}",
                    x.method(), x.selected(), x.truth(), x.truePositives(), x.precision(), x.recall(), x.collateral()));
        }
        StringBuilder c = new StringBuilder();
        r.classificationCounts().forEach((k, v) -> c.append(c.length() == 0 ? "" : ",").append('"').append(k).append("\":").append(v));
        return String.format(Locale.ROOT,
                "{\"orders\":%d,\"seed\":%d,\"scenario\":\"%s\",\"versions\":%d,\"edges\":%d,\"truthSize\":%d,"
                        + "\"metrics\":[%s],\"ordersChecked\":%d,\"orderTotalMismatches\":%d,\"compensationsChecked\":%d,"
                        + "\"compensationMismatches\":%d,\"analysisMillis\":%d,\"planMillis\":%d,\"planSteps\":%d,"
                        + "\"classifications\":{%s}}",
                r.orders(), r.seed(), r.scenario(), r.versions(), r.edges(), r.truthSize(), m, r.ordersChecked(),
                r.orderTotalMismatches(), r.compensationsChecked(), r.compensationMismatches(), r.analysisMillis(),
                r.planMillis(), r.planSteps(), c);
    }

    public static void main(String[] args) {
        int orders = Integer.parseInt(args[0]);
        long seed = Long.parseLong(args[1]);
        Scenario scenario = args.length > 2 ? Scenario.valueOf(args[2]) : Scenario.LIVE;
        System.out.println(json(run(orders, seed, scenario, new double[] {0.01, 0.05, 0.10})));
    }
}
