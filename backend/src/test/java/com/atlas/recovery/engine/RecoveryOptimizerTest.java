package com.atlas.recovery.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atlas.provenance.VersionRef;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** ATLAS X Milestone 5: hard constraints, scoring and explainable risk. */
class RecoveryOptimizerTest {

    private final CounterfactualSimulator simulator = new CounterfactualSimulator();
    private final RecoveryOptimizer optimizer = new RecoveryOptimizer();

    private static SimulationInput input(SimulatedCommerce sim, UUID product) {
        Map<VersionRef, Long> seq = new HashMap<>();
        sim.writeSeq.forEach((k, list) -> {
            for (int v = 0; v < list.size(); v++) {
                seq.put(k.at(v), list.get(v));
            }
        });
        return new SimulationInput(sim.faultyChange(product, 1, "unit_price"), sim.graph(), seq, Map.of(), Set.of());
    }

    private static RecoveryOptimizer.Candidate cand(RecoveryOptimizer.Assessment a, RecoveryStrategy s) {
        return a.candidates().stream().filter(c -> c.strategy() == s).findFirst().orElseThrow();
    }

    private static boolean failed(RecoveryOptimizer.Candidate c, String code) {
        return c.constraints().stream().anyMatch(k -> k.code().equals(code) && !k.passed());
    }

    @Test
    void pascarIsRecommendedAndEveryBaselineBreaksAHardConstraintForAReason() {
        SimulatedCommerce sim = new SimulatedCommerce(3);
        UUID widget = sim.createProduct("P100", "5000");
        UUID early = sim.createOrder();
        sim.addLine(early, widget, 1);
        sim.updateProduct(widget, "unit_price", "500", "USER");
        UUID order = sim.createOrder();
        sim.addLine(order, widget, 2);
        sim.edit(SimulatedCommerce.order(order), "delivery_note", "Gate 2");

        RecoveryOptimizer.Assessment a = optimizer.assess(simulator.simulate(input(sim, widget)));
        assertEquals(RecoveryStrategy.PROVENANCE_CONFLICT_AWARE, a.recommended().orElseThrow());
        assertEquals(1, cand(a, RecoveryStrategy.PROVENANCE_CONFLICT_AWARE).rank());
        assertTrue(failed(cand(a, RecoveryStrategy.FULL_RESTORE), "NO_DELETIONS"));
        assertTrue(failed(cand(a, RecoveryStrategy.FULL_RESTORE), "PRESERVES_LATER_UPDATES"));
        assertTrue(failed(cand(a, RecoveryStrategy.VERSION_ROW_RESTORE), "VERSION_CHECKED_WRITES"));
        assertTrue(failed(cand(a, RecoveryStrategy.PROVENANCE_CONFLICT_BLIND), "AUTHORIZED_EXECUTION"));
        assertFalse(cand(a, RecoveryStrategy.FIELD_LEVEL_FK).feasible());
        assertTrue(a.summary().startsWith("Recommended: F"));
    }

    @Test
    void fewestWritesDoesNotWinWhenItBreaksAConstraint() {
        // A strategy that writes nothing at all is not a recovery: it leaves the fault in place.
        StrategyResult doNothing = new StrategyResult(RecoveryStrategy.PROVENANCE_CONFLICT_AWARE, true, null, 3, 0, 0, 3,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, List.of(), List.of());
        StrategyResult destructive = new StrategyResult(RecoveryStrategy.FULL_RESTORE, true, null, 3, 1, 1, 3, 3, 0, 4, 0,
                0, 0, 1, 0, 0, 0, List.of(), List.of());
        RecoveryOptimizer.Score idle = optimizer.score(doNothing);
        RecoveryOptimizer.Score wipe = optimizer.score(destructive);
        assertTrue(idle.unresolved() == 3 && idle.total() > 0, "unrepaired faults are scored: " + idle);
        assertFalse(RecoveryOptimizer.constraints(destructive).stream().allMatch(RecoveryOptimizer.Constraint::passed),
                "one write, but it deletes a record and overwrites later updates");
        assertTrue(wipe.collateral() == 5);
    }

    @Test
    void conflictsRaiseRiskAndAreExplained() {
        SimulatedCommerce sim = new SimulatedCommerce(5);
        UUID widget = sim.createProduct("P100", "5000");
        sim.updateProduct(widget, "unit_price", "500", "USER");
        UUID order = sim.createOrder();
        UUID line = sim.addLine(order, widget, 1);
        sim.edit(new EntityKey("order_line", line), "unit_price", "450");

        RecoveryOptimizer.Assessment a = optimizer.assess(simulator.simulate(input(sim, widget)));
        assertEquals(RecoveryOptimizer.RiskLevel.HIGH, a.risk());
        RecoveryOptimizer.RiskFactor conflicts = a.riskFactors().stream()
                .filter(f -> f.factor().equals("CONFLICTS")).findFirst().orElseThrow();
        assertEquals(RecoveryOptimizer.RiskLevel.HIGH, conflicts.level());
        assertTrue(conflicts.value().startsWith("1"), conflicts.toString());
        assertTrue(a.riskFactors().stream().allMatch(f -> !f.explanation().isBlank()));
    }

    @Test
    void largeMoneyIsCritical() {
        SimulatedCommerce sim = new SimulatedCommerce(6);
        UUID machine = sim.createProduct("M-1", "5000000");
        sim.updateProduct(machine, "unit_price", "500000", "USER");     // an extra zero missing on a big item
        UUID order = sim.createOrder();
        sim.addLine(order, machine, 1);
        RecoveryOptimizer.Assessment a = optimizer.assess(simulator.simulate(input(sim, machine)));
        assertEquals(RecoveryOptimizer.RiskLevel.CRITICAL, a.risk());
        assertEquals("4500000.00", a.riskFactors().get(0).value());
    }

    @Test
    void deterministicAndWeightsAreConfigurable() {
        SimulatedCommerce sim = new SimulatedCommerce(8);
        UUID widget = sim.createProduct("P100", "5000");
        sim.updateProduct(widget, "unit_price", "500", "USER");
        sim.addLine(sim.createOrder(), widget, 1);
        CounterfactualSimulator.Report r = simulator.simulate(input(sim, widget));
        assertEquals(optimizer.assess(r), optimizer.assess(r));
        RecoveryOptimizer costOnly = new RecoveryOptimizer(new RecoveryOptimizer.Weights(0, 0, 0, 1),
                RecoveryOptimizer.Thresholds.DEFAULT);
        RecoveryOptimizer.Candidate f = cand(costOnly.assess(r), RecoveryStrategy.PROVENANCE_CONFLICT_AWARE);
        assertTrue(Math.abs(f.score().cost() - f.score().total()) < 1e-9, f.score().toString());
        assertTrue(new BigDecimal("10000").compareTo(RecoveryOptimizer.Thresholds.DEFAULT.medium()) == 0);
    }
}
