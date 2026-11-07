package com.atlas.recovery.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.atlas.recovery.engine.EntityKey;
import com.atlas.recovery.engine.RecoveryPlan;
import com.atlas.recovery.engine.SimulatedCommerce;
import com.atlas.recovery.engine.Values;
import com.atlas.recovery.engine.VersionInfo;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Approval, execution, crash recovery and verification against the in-memory
 * stand-in (synthetic data).
 */
class RecoveryWorkflowTest {

    private static final UUID AUTHOR = UUID.randomUUID();
    private static final UUID APPROVER = UUID.randomUUID();
    private static final UUID EXECUTOR = UUID.randomUUID();

    private static EntityKey line(UUID id) {
        return new EntityKey("order_line", id);
    }

    private static EntityKey invoice(UUID id) {
        return new EntityKey("invoice", id);
    }

    private static boolean eq(String expected, Object actual) {
        return Values.same(new BigDecimal(expected), actual);
    }

    /**
     * P100 should cost 5000 but was typed as 50000 (v1). Then:
     * order A (3 × P100 + 2 × T-100) confirmed and its invoice ISSUED → over-billed, credit note;
     * order B (1 × P100) confirmed with a DRAFT invoice → lines and total updated;
     * order C (2 × P100) still a draft → line and total updated.
     */
    private static final class World {
        final SimulatedCommerce sim = new SimulatedCommerce(7);
        final UUID widget = sim.createProduct("P100", "5000");
        final UUID tee = sim.createProduct("T-100", "900");
        final UUID orderA;
        final UUID lineA;
        final UUID issued;
        final UUID orderB;
        final UUID draftInvoice;
        final UUID orderC;
        final UUID lineC;
        final InMemoryRecovery db;
        final RecoveryWorkflow flow;

        World() {
            sim.updateProduct(widget, "unit_price", "50000", "USER");   // the faulty change, v1
            orderA = sim.createOrder();
            lineA = sim.addLine(orderA, widget, 3);
            sim.addLine(orderA, tee, 2);
            sim.confirm(orderA);
            issued = sim.invoice(orderA);
            sim.setInvoiceStatus(issued, "ISSUED");
            orderB = sim.createOrder();
            sim.addLine(orderB, widget, 1);
            sim.confirm(orderB);
            draftInvoice = sim.invoice(orderB);
            orderC = sim.createOrder();
            lineC = sim.addLine(orderC, widget, 2);
            db = new InMemoryRecovery(sim, sim.faultyChange(widget, 1, "unit_price"));
            flow = new RecoveryWorkflow(db, db, db, db, db);
        }

        PlanRecord proposed() {
            return db.store(db.freshPlan(), AUTHOR);
        }

        PlanRecord approved() {
            PlanRecord p = proposed();
            flow.approve(p.id(), APPROVER, p.plan().fingerprint(), "checked the totals");
            return p;
        }

        Map<String, Object> now(EntityKey key) {
            return sim.current(key);
        }

        long recoveryVersions(EntityKey key) {
            return sim.histories().get(key).stream().filter(VersionInfo::byRecovery).count();
        }
    }

    private static RecoveryException.Kind kindOf(Runnable r) {
        return assertThrows(RecoveryException.class, r::run).kind();
    }

    // ------------------------------------------------------------------ happy path

    @Test
    void approvedPlanExecutesAndVerifies() {
        World w = new World();
        PlanRecord p = w.approved();
        RecoveryPlan plan = p.plan();
        assertTrue(plan.requiresApproval(), "a credit note makes the plan HIGH risk");
        long automatic = plan.steps().stream().filter(RecoveryPlan.Step::automatic).count();

        ExecutionReport report = w.flow.execute(p.id(), EXECUTOR);

        assertEquals(PlanStatus.EXECUTED, report.status());
        assertEquals(Map.of(StepStatus.DONE, automatic), report.counts());
        assertTrue(eq("5000", w.now(SimulatedCommerce.product(w.widget)).get("unit_price")));
        assertTrue(eq("10000", w.now(line(w.lineC)).get("line_total")));
        assertTrue(eq("10000", w.now(SimulatedCommerce.order(w.orderC)).get("total_amount")));
        assertTrue(eq("5000", w.now(invoice(w.draftInvoice)).get("total_amount")));
        // The issued invoice is never edited: it is credited 3 × 45000.
        assertTrue(eq("151800", w.now(invoice(w.issued)).get("total_amount")));
        assertEquals("ISSUED", w.now(invoice(w.issued)).get("status"));
        assertEquals(0L, w.recoveryVersions(invoice(w.issued)));

        Verification v = w.flow.verify(p.id());
        assertEquals(Verification.Outcome.PASSED, v.outcome(), v.checks().toString());
        assertTrue(v.checks().stream().anyMatch(c -> c.kind().equals("CREDITED_AT_LEAST") && c.passed()));
        assertTrue(v.checks().stream().anyMatch(c -> c.kind().equals("LARGE_COMPENSATION_REVIEWED") && c.passed()));
        assertTrue(v.checks().stream().anyMatch(c -> c.kind().equals("NOTHING_LEFT_TO_REPAIR") && c.passed()));
        assertEquals(1, w.db.verifications(p.id()).size());
    }

    @Test
    void executingAgainReplaysTheResultWithoutWriting() {
        World w = new World();
        PlanRecord p = w.approved();
        ExecutionReport first = w.flow.execute(p.id(), EXECUTOR);
        int writes = w.db.recordWrites;
        ExecutionReport again = w.flow.execute(p.id(), EXECUTOR);
        assertEquals(first, again);
        assertEquals(writes, w.db.recordWrites);
    }

    @Test
    void recoveryWritesRecordProvenanceSoAFreshAnalysisFindsNothingToDo() {
        World w = new World();
        PlanRecord p = w.approved();
        w.flow.execute(p.id(), EXECUTOR);
        RecoveryPlan again = w.db.freshPlan();
        assertTrue(again.steps().isEmpty(), "left: " + again.steps());
    }

    @Test
    void lowRiskPlanRunsWithoutApproval() {
        // Only a draft order is affected in this smaller world.
        SimulatedCommerce sim = new SimulatedCommerce(3);
        UUID p100 = sim.createProduct("P100", "5000");
        sim.updateProduct(p100, "unit_price", "50000", "USER");
        UUID order = sim.createOrder();
        sim.addLine(order, p100, 2);
        InMemoryRecovery db = new InMemoryRecovery(sim, sim.faultyChange(p100, 1, "unit_price"));
        RecoveryWorkflow flow = new RecoveryWorkflow(db, db, db, db, db);
        PlanRecord plan = db.store(db.freshPlan(), AUTHOR);
        assertFalse(plan.plan().requiresApproval(), "draft order only: LOW risk, 3 steps");

        assertEquals(PlanStatus.EXECUTED, flow.execute(plan.id(), AUTHOR).status());
        assertEquals(Verification.Outcome.PASSED, flow.verify(plan.id()).outcome());
        assertTrue(db.decisions(plan.id()).isEmpty());
    }

    // ------------------------------------------------------------------ approval rules

    @Test
    void theAuthorCannotApproveTheirOwnPlan() {
        World w = new World();
        PlanRecord p = w.proposed();
        assertEquals(RecoveryException.Kind.SELF_APPROVAL,
                kindOf(() -> w.flow.approve(p.id(), AUTHOR, p.plan().fingerprint(), "mine")));
        assertEquals(PlanStatus.PROPOSED, w.db.status(p.id()));
    }

    @Test
    void approvalIsBoundToTheFingerprintTheApproverSaw() {
        World w = new World();
        PlanRecord p = w.proposed();
        assertEquals(RecoveryException.Kind.STALE_PLAN,
                kindOf(() -> w.flow.approve(p.id(), APPROVER, "sha256:" + "0".repeat(64), "ok")));
        assertTrue(w.db.decisions(p.id()).isEmpty());
    }

    @Test
    void aPlanNeedingApprovalCannotRunWithoutIt() {
        World w = new World();
        PlanRecord p = w.proposed();
        assertEquals(RecoveryException.Kind.NOT_APPROVED, kindOf(() -> w.flow.execute(p.id(), EXECUTOR)));
        assertEquals(0, w.db.recordWrites);
    }

    @Test
    void dataChangedAfterPlanningMakesApprovalAndExecutionStale() {
        World w = new World();
        PlanRecord p = w.proposed();
        UUID late = w.sim.createOrder();
        w.sim.addLine(late, w.widget, 1);   // the wrong price is still live and was used again
        assertEquals(RecoveryException.Kind.STALE_PLAN,
                kindOf(() -> w.flow.approve(p.id(), APPROVER, p.plan().fingerprint(), "ok")));

        World w2 = new World();
        PlanRecord p2 = w2.approved();
        w2.sim.edit(line(w2.lineC), "quantity", 5);
        assertEquals(RecoveryException.Kind.STALE_PLAN, kindOf(() -> w2.flow.execute(p2.id(), EXECUTOR)));
        assertEquals(PlanStatus.APPROVED, w2.db.status(p2.id()));
        assertEquals(0, w2.db.recordWrites);
    }

    @Test
    void rejectionNeedsAReasonAndEndsThePlan() {
        World w = new World();
        PlanRecord p = w.proposed();
        assertEquals(RecoveryException.Kind.INVALID, kindOf(() -> w.flow.reject(p.id(), APPROVER, "  ")));
        w.flow.reject(p.id(), APPROVER, "credit amount needs finance sign-off");
        assertEquals(PlanStatus.REJECTED, w.db.status(p.id()));
        assertEquals(RecoveryException.Kind.ILLEGAL_STATE, kindOf(() -> w.flow.execute(p.id(), EXECUTOR)));
        assertEquals(RecoveryException.Kind.ILLEGAL_STATE,
                kindOf(() -> w.flow.approve(p.id(), APPROVER, p.plan().fingerprint(), "ok")));
    }

    @Test
    void unknownPlanIsNotFoundAndUnexecutedPlanCannotBeVerified() {
        World w = new World();
        assertEquals(RecoveryException.Kind.NOT_FOUND, kindOf(() -> w.flow.execute(UUID.randomUUID(), EXECUTOR)));
        PlanRecord p = w.approved();
        assertEquals(RecoveryException.Kind.ILLEGAL_STATE, kindOf(() -> w.flow.verify(p.id())));
    }

    // ------------------------------------------------------------------ crashes

    /** Commits in one uninterrupted run: start + one per step + finish. */
    private static int commitsOfFullRun() {
        World w = new World();
        PlanRecord p = w.approved();
        return 2 + p.plan().steps().size();
    }

    @Test
    void aCrashAfterAnyCommitResumesWithoutApplyingAnyStepTwice() {
        int commits = commitsOfFullRun();
        for (int k = 1; k <= commits; k++) {
            World w = new World();
            PlanRecord p = w.approved();
            long automatic = p.plan().steps().stream().filter(RecoveryPlan.Step::automatic).count();
            w.db.crashAfterCommits(k);
            int crashAt = k;
            assertThrows(InMemoryRecovery.SimulatedCrash.class, () -> w.flow.execute(p.id(), EXECUTOR));

            ExecutionReport resumed = w.flow.execute(p.id(), EXECUTOR);
            assertEquals(PlanStatus.EXECUTED, resumed.status(), "crash after commit " + crashAt);
            assertEquals(automatic, (long) w.db.recordWrites, "each write exactly once, crash after commit " + crashAt);
            for (RecoveryPlan.Step s : p.plan().steps()) {
                if (s.action() == RecoveryPlan.Action.RESTORE_FIELDS || s.action() == RecoveryPlan.Action.UPDATE_FIELDS) {
                    assertEquals(1L, w.recoveryVersions(s.key()), "one recovery version of " + s.key());
                }
            }
            assertEquals(Verification.Outcome.PASSED, w.flow.verify(p.id()).outcome(), "crash after commit " + crashAt);
        }
    }

    @Test
    void aCrashInTheMiddleOfAWriteLeavesNoPartialWrite() {
        World w0 = new World();
        long automatic = w0.approved().plan().steps().stream().filter(RecoveryPlan.Step::automatic).count();
        for (int k = 1; k <= automatic; k++) {
            World w = new World();
            PlanRecord p = w.approved();
            w.db.crashInsideWrite(k);
            assertThrows(InMemoryRecovery.SimulatedCrash.class, () -> w.flow.execute(p.id(), EXECUTOR));
            assertEquals(PlanStatus.EXECUTING, w.db.status(p.id()));
            for (EntityKey key : w.sim.histories().keySet()) {
                assertFalse(w.now(key).containsKey("half_written"), "rolled back: " + key);
            }
            assertEquals(PlanStatus.EXECUTED, w.flow.execute(p.id(), EXECUTOR).status());
            assertEquals(Verification.Outcome.PASSED, w.flow.verify(p.id()).outcome());
        }
    }

    @Test
    void aTransientFailureLeavesTheStepUnrecordedSoResumeRetriesIt() {
        World w = new World();
        PlanRecord p = w.approved();
        w.db.failWriteOnce();
        assertThrows(IllegalStateException.class, () -> w.flow.execute(p.id(), EXECUTOR));
        assertEquals(PlanStatus.EXECUTING, w.db.status(p.id()));
        assertEquals(PlanStatus.EXECUTED, w.flow.execute(p.id(), EXECUTOR).status());
        assertEquals(Verification.Outcome.PASSED, w.flow.verify(p.id()).outcome());
    }

    // ------------------------------------------------------------------ failures and blocking

    @Test
    void aRecordChangedDuringExecutionIsNotOverwrittenAndItsDependentsAreBlocked() {
        World w = new World();
        PlanRecord p = w.approved();
        w.db.crashAfterCommits(1);   // the start committed: status EXECUTING, no step yet
        assertThrows(InMemoryRecovery.SimulatedCrash.class, () -> w.flow.execute(p.id(), EXECUTOR));
        w.sim.edit(line(w.lineC), "unit_price", "4800");   // a person fixes order C's line by hand meanwhile

        ExecutionReport report = w.flow.execute(p.id(), EXECUTOR);

        assertEquals(PlanStatus.FAILED, report.status());
        RecoveryPlan.Step lineStep = step(p, line(w.lineC));
        RecoveryPlan.Step orderStep = step(p, SimulatedCommerce.order(w.orderC));
        StepRecord lineOutcome = report.steps().get(lineStep.number() - 1);
        assertEquals(StepStatus.FAILED, lineOutcome.status());
        assertTrue(lineOutcome.detail().startsWith("Changed since planning"), lineOutcome.detail());
        assertEquals(StepStatus.BLOCKED, report.steps().get(orderStep.number() - 1).status());
        assertTrue(eq("4800", w.now(line(w.lineC)).get("unit_price")), "the person's edit stands");
        // Independent steps still ran.
        assertTrue(eq("5000", w.now(invoice(w.draftInvoice)).get("total_amount")));

        Verification v = w.flow.verify(p.id());
        assertEquals(Verification.Outcome.FAILED, v.outcome());
        assertTrue(v.checks().stream().anyMatch(c -> c.kind().equals("STEP_DONE") && !c.passed()));
        assertTrue(v.checks().stream().anyMatch(c -> c.kind().equals("NOTHING_LEFT_TO_REPAIR") && !c.passed()));
    }

    @Test
    void aRejectedWriteFailsOnlyItsBranchAndAReplanFinishesTheRestWithoutCreditingTwice() {
        World w = new World();
        PlanRecord first = w.approved();
        w.db.rejectWriteOf(line(w.lineC));
        ExecutionReport r1 = w.flow.execute(first.id(), EXECUTOR);
        assertEquals(PlanStatus.FAILED, r1.status());
        assertEquals(StepStatus.FAILED, r1.steps().get(step(first, line(w.lineC)).number() - 1).status());
        assertTrue(r1.steps().get(step(first, line(w.lineC)).number() - 1).detail().startsWith("Rejected:"));
        assertEquals(Verification.Outcome.FAILED, w.flow.verify(first.id()).outcome());

        w.db.rejectWriteOf(null);   // the cause is fixed
        RecoveryPlan rest = w.db.freshPlan();
        assertEquals(List.of(line(w.lineC), SimulatedCommerce.order(w.orderC)),
                rest.steps().stream().map(RecoveryPlan.Step::key).toList(), "only order C is left");
        assertTrue(rest.steps().stream().noneMatch(s -> s.action() == RecoveryPlan.Action.ISSUE_CREDIT_NOTE),
                "the credit note was already issued");
        PlanRecord second = w.db.store(rest, AUTHOR);
        assertEquals(PlanStatus.EXECUTED, w.flow.execute(second.id(), EXECUTOR).status());
        assertEquals(Verification.Outcome.PASSED, w.flow.verify(second.id()).outcome());
    }

    // ------------------------------------------------------------------ manual steps

    @Test
    void aConflictedOriginWaitsForAPersonButItsConsumersAreStillCorrected() {
        World w = new World();
        w.sim.updateProduct(w.widget, "unit_price", "5200", "USER");   // someone set a new price since
        PlanRecord p = w.approved();
        RecoveryPlan.Step origin = p.plan().steps().get(0);
        assertEquals(RecoveryPlan.Action.MANUAL_REVIEW, origin.action());

        ExecutionReport report = w.flow.execute(p.id(), EXECUTOR);
        assertEquals(PlanStatus.EXECUTED, report.status());
        assertEquals(StepStatus.AWAITING_ACKNOWLEDGEMENT, report.steps().get(0).status());
        assertTrue(eq("5200", w.now(SimulatedCommerce.product(w.widget)).get("unit_price")), "not overwritten");
        assertTrue(eq("10000", w.now(line(w.lineC)).get("line_total")), "consumers corrected to 5000 each");

        Verification v1 = w.flow.verify(p.id());
        assertEquals(Verification.Outcome.INCOMPLETE, v1.outcome());
        assertEquals(1, v1.pendingManualSteps());

        assertEquals(RecoveryException.Kind.INVALID, kindOf(() -> w.flow.acknowledge(p.id(), 1, APPROVER, "")));
        assertEquals(RecoveryException.Kind.ILLEGAL_STATE,
                kindOf(() -> w.flow.acknowledge(p.id(), 2, APPROVER, "not a manual step")));
        w.flow.acknowledge(p.id(), 1, APPROVER, "5200 is the new list price; keep it");
        assertEquals(RecoveryException.Kind.ILLEGAL_STATE,
                kindOf(() -> w.flow.acknowledge(p.id(), 1, APPROVER, "twice")));

        Verification v2 = w.flow.verify(p.id());
        assertEquals(Verification.Outcome.PASSED, v2.outcome(), v2.checks().toString());
        assertEquals(2, w.db.verifications(p.id()).size(), "verifications are appended, not replaced");
    }

    // ------------------------------------------------------------------ the verifier checks the data

    @Test
    void verificationFailsIfARepairedValueIsChangedAfterwards() {
        World w = new World();
        PlanRecord p = w.approved();
        w.flow.execute(p.id(), EXECUTOR);
        w.sim.edit(SimulatedCommerce.order(w.orderC), "total_amount", "1");
        Verification v = w.flow.verify(p.id());
        assertEquals(Verification.Outcome.FAILED, v.outcome());
        assertTrue(v.checks().stream().anyMatch(c -> c.kind().equals("TOTAL_EQUALS_SUM_OF_LINES") && !c.passed()));
        assertTrue(v.checks().stream().anyMatch(c -> c.kind().equals("FIELD_EQUALS") && !c.passed()));
    }

    @Test
    void verificationFailsIfTheExecutionWroteOutsideThePlan() {
        World w = new World();
        PlanRecord p = w.approved();
        w.flow.execute(p.id(), EXECUTOR);
        w.db.attributeStrayWrite(p.id(), SimulatedCommerce.product(w.tee));
        Verification v = w.flow.verify(p.id());
        assertEquals(Verification.Outcome.FAILED, v.outcome());
        Verification.Check scope = v.checks().stream().filter(c -> c.kind().equals("SCOPE")).findFirst().orElseThrow();
        assertFalse(scope.passed(), scope.actual());
        assertTrue(scope.actual().contains("unexpected"), scope.actual());
    }

    private static RecoveryPlan.Step step(PlanRecord p, EntityKey key) {
        return p.plan().steps().stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow();
    }
}
