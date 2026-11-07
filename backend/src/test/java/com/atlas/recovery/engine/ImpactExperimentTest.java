package com.atlas.recovery.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Small, fast version of the Phase 5 experiment so CI guards its headline claims (synthetic data). */
class ImpactExperimentTest {

    private static Map<String, ImpactExperiment.Metrics> byMethod(ImpactExperiment.Result r) {
        return r.metrics().stream().collect(Collectors.toMap(ImpactExperiment.Metrics::method, Function.identity()));
    }

    @Test
    void pascarMatchesTheOracleExactlyAndBeatsBothBaselines() {
        for (long seed = 1; seed <= 3; seed++) {
            for (ImpactExperiment.Scenario scenario : ImpactExperiment.Scenario.values()) {
                ImpactExperiment.Result r = ImpactExperiment.run(400, seed, scenario, new double[] {0.10});
                Map<String, ImpactExperiment.Metrics> m = byMethod(r);
                String label = scenario + " seed " + seed;
                assertEquals(1.0, m.get("PASCAR").precision(), 0.0, label);
                assertEquals(1.0, m.get("PASCAR").recall(), 0.0, label);
                assertTrue(m.get("NAIVE_FK").precision() < 1.0, label + ": naive FK should over-select");
                assertTrue(m.get("FULL_RESTORE").collateral() > m.get("NAIVE_FK").collateral(), label);
                assertEquals(0, r.orderTotalMismatches(), label);
                assertEquals(0, r.compensationMismatches(), label);
                assertTrue(r.ordersChecked() > 0 && r.compensationsChecked() > 0, label + ": value checks ran");
                // Missing edges cost recall, never precision.
                assertEquals(1.0, m.get("PASCAR_EDGE_LOSS_10%").precision(), 0.0, label);
                assertTrue(m.get("PASCAR_EDGE_LOSS_10%").recall() < 1.0, label);
            }
        }
    }

    @Test
    void sameSeedSameResult() {
        String a = ImpactExperiment.json(ImpactExperiment.run(200, 9, ImpactExperiment.Scenario.LIVE, new double[] {0.05}))
                .replaceAll("\"(analysis|plan)Millis\":\\d+", "");
        String b = ImpactExperiment.json(ImpactExperiment.run(200, 9, ImpactExperiment.Scenario.LIVE, new double[] {0.05}))
                .replaceAll("\"(analysis|plan)Millis\":\\d+", "");
        assertEquals(a, b);
    }
}
