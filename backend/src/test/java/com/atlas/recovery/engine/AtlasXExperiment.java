package com.atlas.recovery.engine;

import com.atlas.provenance.VersionRef;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * ATLAS X Milestone 8 experiment (SYNTHETIC data, seeded, reproducible).
 *
 * <p>Starts from the Phase 5 generator ({@link ImpactExperiment#generate}): one product's
 * price is mistyped part-way through a commerce history. Then, AFTER the fault, it injects
 * two kinds of later work whose identity is known independently of provenance:
 * <ul>
 *   <li><b>legitimate later updates</b>: people set a delivery note on a random share of
 *       orders (affected and unaffected alike). A correct recovery keeps every one;</li>
 *   <li><b>same-field conflicts</b>: people re-price a random share of the order lines
 *       that carry the faulty price. A correct recovery must not overwrite these silently;
 *       it should bring each one to a person.</li>
 * </ul>
 * Every strategy A–F is then simulated on the same snapshot. The strategies double as
 * the ablations: B/D drop provenance (foreign keys instead), C drops field granularity
 * (whole-row restore), E drops conflict handling; A is the usual point-in-time restore.
 *
 * <p>The simulator measures each strategy using recorded provenance. The ORACLE columns
 * re-derive the expected counts from the generator's own facts (which lines were priced
 * from a faulty version, which records were edited later) without reading any edge, so a
 * disagreement between the two would show a bug in the simulator, not be hidden by it.
 *
 * <p>Run: java ... com.atlas.recovery.engine.AtlasXExperiment orders seed scenario noteRate conflictRate
 */
public final class AtlasXExperiment {

    /** Counts known from the generator, never from provenance. */
    public record Oracle(int faultyRecords, int legitimateUpdates, int legitimateOnFaultyRecords, int conflicts) {
    }

    public record Row(String strategy, String letter, boolean applicable, int modified, int deleted, int faulty, int repaired,
                      int collateral, int legitimateOverwritten, int conflictsSurfaced, int conflictsIgnored,
                      int invariantViolations, int fieldWrites, int compensations, int manualSteps, boolean feasible,
                      long micros, List<String> sampleViolations, int detailsTotal, int detailsStored,
                      boolean detailsTruncated) {
    }

    public record Result(int orders, long seed, ImpactExperiment.Scenario scenario, double noteRate, double conflictRate,
                         int records, int versions, int edges, Oracle oracle, List<Row> rows, String recommended,
                         String risk) {
    }

    public static Result run(int orders, long seed, ImpactExperiment.Scenario scenario, double noteRate, double conflictRate) {
        ImpactExperiment.World w = ImpactExperiment.generate(orders, seed, scenario);
        SimulatedCommerce sim = w.sim();
        Random r = new Random(seed * 7919 + scenario.ordinal());
        Set<EntityKey> affected = w.truth().affected();

        int notes = 0;
        int notesOnFaulty = 0;
        for (UUID order : sim.orderLines.keySet()) {
            if (r.nextDouble() < noteRate) {
                EntityKey k = SimulatedCommerce.order(order);
                sim.edit(k, "delivery_note", "Deliver after 5pm #" + notes);
                notes++;
                if (affected.contains(k)) {
                    notesOnFaulty++;
                }
            }
        }
        int conflicts = 0;
        List<EntityKey> faultyLines = new ArrayList<>();
        for (EntityKey k : affected) {
            if (k.entityType().equals("order_line")) {
                faultyLines.add(k);
            }
        }
        faultyLines.sort((a, b) -> a.entityId().compareTo(b.entityId()));
        for (EntityKey line : faultyLines) {
            if (r.nextDouble() < conflictRate) {
                Object price = sim.current(line).get("unit_price");
                sim.edit(line, "unit_price", Values.decimal(price).add(java.math.BigDecimal.ONE).toPlainString());
                conflicts++;
            }
        }
        Oracle oracle = new Oracle(affected.size(), notes, notesOnFaulty, conflicts);

        CounterfactualSimulator.Report report = new CounterfactualSimulator().simulate(input(sim, w.fault(), sim.graph()));
        RecoveryOptimizer.Assessment a = new RecoveryOptimizer().assess(report);
        List<Row> rows = new ArrayList<>();
        for (StrategyResult s : report.results()) {
            boolean feasible = a.candidates().stream().filter(c -> c.strategy() == s.strategy()).findFirst()
                    .map(RecoveryOptimizer.Candidate::feasible).orElse(false);
            rows.add(new Row(s.strategy().name(), s.strategy().letter(), s.applicable(), s.recordsModified(),
                    s.recordsDeleted(), s.faultyRecords(), s.repaired(), s.collateralModified(), s.legitimateOverwritten(),
                    s.conflictsSurfaced(), s.conflictsIgnored(), s.invariantViolations(), s.fieldWrites(), s.compensations(),
                    s.manualSteps(), feasible, s.simulationMicros(),
                    s.violations().subList(0, Math.min(3, s.violations().size())),
                    report.recordSets().get(s.strategy()).total(), report.recordSets().get(s.strategy()).stored().size(),
                    report.recordSets().get(s.strategy()).truncated()));
        }
        return new Result(orders, seed, scenario, noteRate, conflictRate, report.records(), report.versions(),
                sim.edgeCount(), oracle, rows, a.recommended().map(Enum::name).orElse(null), a.risk().name());
    }

    static SimulationInput input(SimulatedCommerce sim, FaultyChange fault, IncidentGraph graph) {
        Map<VersionRef, Long> seq = new HashMap<>();
        sim.writeSeq.forEach((k, list) -> {
            for (int v = 0; v < list.size(); v++) {
                seq.put(k.at(v), list.get(v));
            }
        });
        return new SimulationInput(fault, graph, seq, Map.of(), Set.of());
    }

    public static String json(Result x) {
        StringBuilder rows = new StringBuilder();
        for (Row r : x.rows()) {
            rows.append(rows.length() == 0 ? "" : ",").append(String.format(Locale.ROOT,
                    "{\"strategy\":\"%s\",\"letter\":\"%s\",\"applicable\":%b,\"modified\":%d,\"deleted\":%d,\"faulty\":%d,"
                            + "\"repaired\":%d,\"collateral\":%d,\"legitimateOverwritten\":%d,\"conflictsSurfaced\":%d,"
                            + "\"conflictsIgnored\":%d,\"invariantViolations\":%d,\"fieldWrites\":%d,\"compensations\":%d,"
                            + "\"manualSteps\":%d,\"feasible\":%b,\"micros\":%d,\"sampleViolations\":%s,"
                            + "\"detailsTotal\":%d,\"detailsStored\":%d,\"detailsTruncated\":%b}",
                    r.strategy(), r.letter(), r.applicable(), r.modified(), r.deleted(), r.faulty(), r.repaired(),
                    r.collateral(), r.legitimateOverwritten(), r.conflictsSurfaced(), r.conflictsIgnored(),
                    r.invariantViolations(), r.fieldWrites(), r.compensations(), r.manualSteps(), r.feasible(), r.micros(),
                    strings(r.sampleViolations()), r.detailsTotal(), r.detailsStored(), r.detailsTruncated()));
        }
        Oracle o = x.oracle();
        return String.format(Locale.ROOT,
                "{\"dataset\":\"synthetic\",\"orders\":%d,\"seed\":%d,\"scenario\":\"%s\",\"noteRate\":%.2f,\"conflictRate\":%.2f,"
                        + "\"records\":%d,\"versions\":%d,\"edges\":%d,\"oracle\":{\"faultyRecords\":%d,\"legitimateUpdates\":%d,"
                        + "\"legitimateOnFaultyRecords\":%d,\"conflicts\":%d},\"recommended\":%s,\"risk\":\"%s\","
                        + "\"strategies\":[%s]}",
                x.orders(), x.seed(), x.scenario(), x.noteRate(), x.conflictRate(), x.records(), x.versions(), x.edges(),
                o.faultyRecords(), o.legitimateUpdates(), o.legitimateOnFaultyRecords(), o.conflicts(),
                x.recommended() == null ? "null" : "\"" + x.recommended() + "\"", x.risk(), rows);
    }

    private static String strings(List<String> xs) {
        StringBuilder b = new StringBuilder("[");
        for (String x : xs) {
            b.append(b.length() == 1 ? "" : ",").append('"').append(x.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return b.append(']').toString();
    }

    /**
     * {@code orders seed scenario noteRate conflictRate} prints one result;
     * {@code sweep orders1,orders2 seedFrom seedTo noteRate conflictRate} prints one JSON line per run.
     */
    public static void main(String[] args) {
        if (args[0].equals("sweep")) {
            double notes = Double.parseDouble(args[4]);
            double conflicts = Double.parseDouble(args[5]);
            for (String n : args[1].split(",")) {
                for (long seed = Long.parseLong(args[2]); seed <= Long.parseLong(args[3]); seed++) {
                    for (ImpactExperiment.Scenario sc : ImpactExperiment.Scenario.values()) {
                        System.out.println(json(run(Integer.parseInt(n), seed, sc, notes, conflicts)));
                    }
                }
            }
            return;
        }
        System.out.println(json(run(Integer.parseInt(args[0]), Long.parseLong(args[1]),
                ImpactExperiment.Scenario.valueOf(args[2]), Double.parseDouble(args[3]), Double.parseDouble(args[4]))));
    }
}
