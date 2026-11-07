package com.atlas.recovery.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Small version of the ATLAS X Milestone 8 experiment so CI guards its headline results (synthetic data). */
class AtlasXExperimentTest {

    private static Map<String, AtlasXExperiment.Row> byLetter(AtlasXExperiment.Result r) {
        return r.rows().stream().collect(Collectors.toMap(AtlasXExperiment.Row::letter, Function.identity()));
    }

    @Test
    void conflictAwareProvenanceIsTheOnlyStrategyThatMeetsEveryConstraint() {
        for (long seed = 1; seed <= 2; seed++) {
            for (ImpactExperiment.Scenario scenario : ImpactExperiment.Scenario.values()) {
                AtlasXExperiment.Result r = AtlasXExperiment.run(300, seed, scenario, 0.2, 0.1);
                Map<String, AtlasXExperiment.Row> m = byLetter(r);
                AtlasXExperiment.Row f = m.get("F");
                String label = scenario + " seed " + seed + ": " + f;
                assertTrue(r.oracle().conflicts() > 0 && r.oracle().legitimateOnFaultyRecords() > 0, label + " (setup)");
                assertEquals("PROVENANCE_CONFLICT_AWARE", r.recommended(), label);
                assertTrue(f.feasible(), label);
                assertEquals(0, f.collateral(), label);
                assertEquals(0, f.legitimateOverwritten(), label);
                assertEquals(0, f.invariantViolations(), label);
                assertEquals(0, f.deleted(), label);
                assertEquals(0, f.conflictsIgnored(), label);
                // Every injected conflict reaches a person (OVERWRITTEN adds the origin's own conflict).
                assertTrue(f.conflictsSurfaced() >= r.oracle().conflicts(), label);
                for (String other : new String[] {"A", "B", "C", "D", "E"}) {
                    assertFalse(m.get(other).feasible(), label + " / " + other);
                }
                // Cross-check against the oracle (no provenance read): the simulator's repair target is the
                // oracle's faulty set minus records whose faulty value a person has since replaced.
                if (scenario != ImpactExperiment.Scenario.OVERWRITTEN) {
                    assertEquals(r.oracle().faultyRecords() - r.oracle().conflicts(), f.faulty(), label);
                }
                // Field-level repair without conflict handling overwrites at least every injected re-price.
                assertTrue(m.get("D").legitimateOverwritten() >= r.oracle().conflicts(), label);
                assertTrue(m.get("E").invariantViolations() > 0, label + ": ignoring conflicts breaks totals");
            }
        }
    }

    @Test
    void sameSeedSameResult() {
        String a = AtlasXExperiment.json(AtlasXExperiment.run(200, 4, ImpactExperiment.Scenario.LIVE, 0.2, 0.1))
                .replaceAll("\"micros\":\\d+", "");
        String b = AtlasXExperiment.json(AtlasXExperiment.run(200, 4, ImpactExperiment.Scenario.LIVE, 0.2, 0.1))
                .replaceAll("\"micros\":\\d+", "");
        assertEquals(a, b);
    }
}
