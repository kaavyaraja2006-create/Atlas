package com.atlas.recovery.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atlas.recovery.engine.ImpactAnalyzer;
import com.atlas.recovery.engine.PlanGenerator;
import com.atlas.recovery.engine.RecoveryPlan;
import com.atlas.recovery.engine.SimulatedCommerce;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The executor runs the plan exactly as stored and approved, so the stored
 * JSON must read back to an identical plan. Uses a plain ObjectMapper
 * (stricter than Spring Boot's: unknown properties fail) configured like
 * {@code Json}: decimals as BigDecimal.
 */
class PlanJsonRoundTripTest {

    @Test
    void aStoredPlanReadsBackIdentically() throws Exception {
        SimulatedCommerce sim = new SimulatedCommerce(11);
        UUID widget = sim.createProduct("P100", "5000");
        UUID tee = sim.createProduct("T-100", "900");
        sim.updateProduct(widget, "unit_price", "50000", "USER");
        UUID a = sim.createOrder();
        sim.addLine(a, widget, 3);
        sim.addLine(a, tee, 2);
        sim.confirm(a);
        UUID inv = sim.invoice(a);
        sim.setInvoiceStatus(inv, "ISSUED");
        UUID b = sim.createOrder();
        sim.addLine(b, widget, 1);
        sim.updateProduct(widget, "unit_price", "5200", "USER");   // origin conflicted: a manual step too
        RecoveryPlan plan = new PlanGenerator().plan(
                new ImpactAnalyzer().analyze(sim.faultyChange(widget, 1, "unit_price"), sim.graph()), sim.graph());
        assertTrue(plan.steps().stream().anyMatch(s -> s.action() == RecoveryPlan.Action.ISSUE_CREDIT_NOTE));
        assertTrue(plan.steps().stream().anyMatch(s -> s.action() == RecoveryPlan.Action.MANUAL_REVIEW));
        assertTrue(plan.steps().stream().anyMatch(s -> !s.inputs().isEmpty()));

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(plan);
        RecoveryPlan back = mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .forType(RecoveryPlan.class).readValue(json);
        assertEquals(plan, back);
        assertEquals(json, mapper.writeValueAsString(back));
    }
}
