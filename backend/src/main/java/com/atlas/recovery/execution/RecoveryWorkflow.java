package com.atlas.recovery.execution;

import com.atlas.recovery.engine.EntityKey;
import com.atlas.recovery.engine.RecoveryPlan;
import com.atlas.recovery.engine.Values;
import com.atlas.recovery.execution.RecoveryException.Kind;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Approval, execution and verification of a stored recovery plan.
 *
 * <p>Rules:
 * <ul>
 *   <li><b>Separation of duties.</b> The plan's author cannot approve it. (The
 *       caller checks the recovery.approve / recovery.execute permissions.)</li>
 *   <li><b>Bound approval.</b> An approval names the plan id and the fingerprint
 *       the approver saw. Approving and starting an execution both recompute
 *       the fingerprint from current data; a mismatch means the plan is stale.</li>
 *   <li><b>One transaction per step.</b> Each step locks the plan row, checks it
 *       has no outcome yet, locks its record, checks the record is still at the
 *       expected version, writes, and records the outcome, all in one
 *       transaction. A crash at any point leaves each step either fully done
 *       and recorded, or not done at all, so resuming never applies a step twice.</li>
 *   <li><b>Blocking.</b> A step whose prerequisite did not complete is not
 *       attempted. A manual step on the origin does not block: its consumers'
 *       corrected values do not depend on what a person does to the origin.</li>
 *   <li><b>Verification never reports success it did not check.</b> PASSED needs
 *       every automatic step done, every invariant true on the current data, the
 *       writes confined to the plan's records, and a fresh plan with nothing
 *       automatic left to do.</li>
 * </ul>
 */
public final class RecoveryWorkflow {

    private final Transactions tx;
    private final RecoveryLedger ledger;
    private final RecordStore store;
    private final Fingerprints fingerprints;
    private final Replanner replanner;

    public RecoveryWorkflow(Transactions tx, RecoveryLedger ledger, RecordStore store, Fingerprints fingerprints,
                            Replanner replanner) {
        this.tx = Objects.requireNonNull(tx);
        this.ledger = Objects.requireNonNull(ledger);
        this.store = Objects.requireNonNull(store);
        this.fingerprints = Objects.requireNonNull(fingerprints);
        this.replanner = Objects.requireNonNull(replanner);
    }

    // ------------------------------------------------------------------ approval

    /**
     * @param seenFingerprint the fingerprint shown to the approver; must match the plan's
     */
    public Decision approve(UUID planId, UUID approverId, String seenFingerprint, String comment) {
        return tx.inTransaction(() -> {
            PlanRecord p = lock(planId);
            requireStatus(p, PlanStatus.PROPOSED, "approved");
            if (approverId.equals(p.createdBy())) {
                throw new RecoveryException(Kind.SELF_APPROVAL, "The author of a plan cannot approve it");
            }
            if (!p.plan().fingerprint().equals(seenFingerprint)) {
                throw new RecoveryException(Kind.STALE_PLAN, "The approval names a different version of this plan");
            }
            requireFresh(p);
            Decision d = new Decision(planId, approverId, true, p.plan().fingerprint(), clean(comment));
            ledger.recordDecision(d);
            ledger.setPlanStatus(planId, PlanStatus.PROPOSED, PlanStatus.APPROVED);
            return d;
        });
    }

    public Decision reject(UUID planId, UUID approverId, String comment) {
        String why = clean(comment);
        if (why == null) {
            throw new RecoveryException(Kind.INVALID, "Say why the plan is rejected");
        }
        return tx.inTransaction(() -> {
            PlanRecord p = lock(planId);
            if (p.status() != PlanStatus.PROPOSED && p.status() != PlanStatus.APPROVED) {
                throw illegal(p, "rejected");
            }
            Decision d = new Decision(planId, approverId, false, p.plan().fingerprint(), why);
            ledger.recordDecision(d);
            ledger.setPlanStatus(planId, p.status(), PlanStatus.REJECTED);
            return d;
        });
    }

    // ------------------------------------------------------------------ execution

    /**
     * Executes the plan, or resumes an execution that stopped part-way. Calling
     * it again after the execution finished returns the same report.
     */
    public ExecutionReport execute(UUID planId, UUID executorId) {
        Start begun = tx.inTransaction(() -> {
            PlanRecord p = start(planId);
            return new Start(p, p.status().finishedExecuting() ? report(planId, p.status()) : null);
        });
        if (begun.finished() != null) {
            return begun.finished();
        }
        PlanRecord plan = begun.plan();
        for (RecoveryPlan.Step step : plan.plan().steps()) {
            runStep(plan, step, executorId);
        }
        return tx.inTransaction(() -> finish(planId));
    }

    private record Start(PlanRecord plan, ExecutionReport finished) {
    }

    private PlanRecord start(UUID planId) {
        PlanRecord p = lock(planId);
        switch (p.status()) {
            case EXECUTING, EXECUTED, FAILED -> {
                return p;   // resume, or replay the finished result
            }
            case PROPOSED -> {
                if (p.plan().requiresApproval()) {
                    throw new RecoveryException(Kind.NOT_APPROVED, "This plan must be approved before it runs");
                }
            }
            case APPROVED -> {
                Decision d = ledger.approval(planId).orElseThrow(() -> illegal(p, "executed without an approval"));
                if (!d.fingerprint().equals(p.plan().fingerprint())) {
                    throw new RecoveryException(Kind.STALE_PLAN, "The approval was for a different version of this plan");
                }
            }
            default -> throw illegal(p, "executed");
        }
        requireFresh(p);
        ledger.setPlanStatus(planId, p.status(), PlanStatus.EXECUTING);
        return new PlanRecord(p.id(), p.incidentId(), p.createdBy(), PlanStatus.EXECUTING, p.origin(), p.plan());
    }

    private void runStep(PlanRecord plan, RecoveryPlan.Step step, UUID executorId) {
        RecordStore.StepContext ctx = new RecordStore.StepContext(plan.id(), step.number(), executorId,
                "Recovery plan step " + step.number() + ": " + step.reason());
        try {
            tx.inTransaction(() -> {
                lock(plan.id());
                Map<Integer, StepRecord> done = ledger.steps(plan.id());
                if (!done.containsKey(step.number())) {
                    ledger.recordStep(plan.id(), attempt(plan, step, done, ctx));
                }
                return null;
            });
        } catch (StepRejected rejected) {
            // The write rolled back with its transaction; record why in a new one.
            tx.inTransaction(() -> {
                lock(plan.id());
                if (!ledger.steps(plan.id()).containsKey(step.number())) {
                    ledger.recordStep(plan.id(), outcome(step, StepStatus.FAILED,
                            "Rejected: " + rejected.getMessage(), null, null, executorId));
                }
                return null;
            });
        }
    }

    private StepRecord attempt(PlanRecord plan, RecoveryPlan.Step step, Map<Integer, StepRecord> done,
                               RecordStore.StepContext ctx) {
        UUID actor = ctx.executedBy();
        for (int dep : step.dependsOn()) {
            StepRecord d = done.get(dep);
            RecoveryPlan.Step depStep = plan.plan().steps().get(dep - 1);
            boolean originUnderReview = depStep.key().equals(plan.origin())
                    && depStep.action() == RecoveryPlan.Action.MANUAL_REVIEW;
            if (d == null || !(d.status().satisfies() || originUnderReview)) {
                String why = d != null ? "Step " + dep + " did not complete (" + d.status() + ")"
                        : dep > step.number() ? "Held until step " + dep + " (a person's decision) is complete"
                        : "Step " + dep + " did not complete (not run)";
                return outcome(step, StepStatus.BLOCKED, why, null, null, actor);
            }
        }
        return switch (step.action()) {
            case NO_ACTION -> outcome(step, StepStatus.SKIPPED, step.reason(), null, null, actor);
            case MANUAL_REVIEW, SUPPLEMENTARY_CHARGE ->
                    outcome(step, StepStatus.AWAITING_ACKNOWLEDGEMENT, step.reason(), null, null, actor);
            case RESTORE_FIELDS, UPDATE_FIELDS -> {
                Optional<String> conflict = versionConflict(step);
                if (conflict.isPresent()) {
                    yield outcome(step, StepStatus.FAILED, conflict.get(), null, null, actor);
                }
                Map<String, Object> fields = new LinkedHashMap<>();
                step.changes().forEach(c -> fields.put(c.field(), c.proposed()));
                long v = store.applyFields(step.key(), step.expectedVersion(), fields, step.inputs(), ctx);
                yield outcome(step, StepStatus.DONE, "Wrote version " + v, v, null, actor);
            }
            case ISSUE_CREDIT_NOTE -> {
                Optional<String> conflict = versionConflict(step);
                if (conflict.isPresent()) {
                    yield outcome(step, StepStatus.FAILED, conflict.get(), null, null, actor);
                }
                UUID note = store.issueCreditNote(step.key(), step.amount(), ctx);
                yield outcome(step, StepStatus.DONE, "Issued credit note for " + step.amount().toPlainString(),
                        null, note, actor);
            }
        };
    }

    private Optional<String> versionConflict(RecoveryPlan.Step step) {
        Optional<Long> now = store.lockVersion(step.key());
        if (now.isEmpty()) {
            return Optional.of("The record no longer exists");
        }
        if (now.get() != step.expectedVersion()) {
            return Optional.of("Changed since planning: expected version " + step.expectedVersion()
                    + ", found " + now.get() + ". Not overwritten.");
        }
        return Optional.empty();
    }

    private ExecutionReport finish(UUID planId) {
        PlanRecord p = lock(planId);
        if (p.status().finishedExecuting()) {
            return report(planId, p.status());   // a concurrent executor finished first
        }
        Map<Integer, StepRecord> steps = ledger.steps(planId);
        for (RecoveryPlan.Step s : p.plan().steps()) {
            if (!steps.containsKey(s.number())) {
                throw new IllegalStateException("Step " + s.number() + " has no outcome");
            }
        }
        boolean failed = steps.values().stream()
                .anyMatch(s -> s.status() == StepStatus.FAILED || s.status() == StepStatus.BLOCKED);
        PlanStatus end = failed ? PlanStatus.FAILED : PlanStatus.EXECUTED;
        ledger.setPlanStatus(planId, PlanStatus.EXECUTING, end);
        return report(planId, end);
    }

    private ExecutionReport report(UUID planId, PlanStatus status) {
        return new ExecutionReport(status, List.copyOf(new java.util.TreeMap<>(ledger.steps(planId)).values()));
    }

    /** A person records what they did about a manual step. */
    public StepRecord acknowledge(UUID planId, int stepNo, UUID userId, String note) {
        String what = clean(note);
        if (what == null) {
            throw new RecoveryException(Kind.INVALID, "Say what was done about this step");
        }
        return tx.inTransaction(() -> {
            PlanRecord p = lock(planId);
            if (!p.status().finishedExecuting()) {
                throw illegal(p, "acknowledged before its execution finished");
            }
            StepRecord s = ledger.steps(planId).get(stepNo);
            if (s == null || s.status() != StepStatus.AWAITING_ACKNOWLEDGEMENT) {
                throw new RecoveryException(Kind.ILLEGAL_STATE, "Step " + stepNo + " is not awaiting acknowledgement");
            }
            StepRecord ack = new StepRecord(stepNo, StepStatus.ACKNOWLEDGED, what, null, null, userId);
            ledger.acknowledgeStep(planId, ack);
            return ack;
        });
    }

    // ------------------------------------------------------------------ verification

    public Verification verify(UUID planId) {
        return tx.inTransaction(() -> {
            PlanRecord p = lock(planId);
            if (!p.status().finishedExecuting()) {
                throw illegal(p, "verified before it ran");
            }
            Map<Integer, StepRecord> steps = ledger.steps(planId);
            List<Verification.Check> checks = new ArrayList<>();
            int pending = 0;
            Set<EntityKey> expectedWrites = new TreeSet<>();
            Map<EntityKey, StepRecord> byKey = new LinkedHashMap<>();

            for (RecoveryPlan.Step s : p.plan().steps()) {
                StepRecord r = steps.get(s.number());
                byKey.put(s.key(), r);
                if (r != null && r.status() == StepStatus.AWAITING_ACKNOWLEDGEMENT) {
                    pending++;
                }
                if (s.automatic()) {
                    boolean ok = r != null && r.status() == StepStatus.DONE;
                    checks.add(new Verification.Check("STEP_DONE", s.key(), "Step " + s.number() + " (" + s.action()
                            + ") completed", ok, r == null ? "no outcome" : r.status() + ": " + r.detail()));
                    if (ok) {
                        expectedWrites.add(r.resultId() != null ? new EntityKey("credit_note", r.resultId()) : s.key());
                    }
                }
            }
            for (RecoveryPlan.Invariant inv : p.plan().invariants()) {
                checks.add(check(p, inv, byKey, expectedWrites));
            }
            RecoveryPlan again = replanner.replan(p);
            List<String> left = again.steps().stream().filter(RecoveryPlan.Step::automatic)
                    .map(s -> s.action() + " " + s.key()).toList();
            checks.add(new Verification.Check("NOTHING_LEFT_TO_REPAIR", null,
                    "A fresh analysis of the current data finds no automatic repair left", left.isEmpty(),
                    left.isEmpty() ? "none" : left.size() + " left, e.g. " + left.get(0)));

            boolean allPassed = checks.stream().allMatch(Verification.Check::passed);
            Verification.Outcome outcome = !allPassed ? Verification.Outcome.FAILED
                    : pending > 0 ? Verification.Outcome.INCOMPLETE : Verification.Outcome.PASSED;
            Verification v = new Verification(outcome, checks, pending);
            ledger.recordVerification(planId, v);
            return v;
        });
    }

    private Verification.Check check(PlanRecord p, RecoveryPlan.Invariant inv, Map<EntityKey, StepRecord> byKey,
                                     Set<EntityKey> expectedWrites) {
        return switch (inv.kind()) {
            case "FIELD_EQUALS" -> {
                Optional<Object> actual = store.field(inv.key(), inv.field());
                boolean ok = actual.isPresent() && Values.same(actual.get(), inv.expected());
                yield result(inv, ok, actual.map(RecoveryWorkflow::show).orElse("record missing"));
            }
            case "TOTAL_EQUALS_SUM_OF_LINES" -> {
                Optional<Object> total = store.field(inv.key(), "total_amount");
                BigDecimal sum = store.sumOfLineTotals(inv.key());
                boolean ok = total.isPresent() && Values.same(total.get(), sum);
                yield result(inv, ok, "total " + total.map(RecoveryWorkflow::show).orElse("missing")
                        + ", lines " + sum.toPlainString());
            }
            case "CREDITED_AT_LEAST" -> {
                StepRecord r = byKey.get(inv.key());
                Optional<BigDecimal> amount = r == null || r.resultId() == null ? Optional.empty()
                        : store.creditNoteAmount(r.resultId());
                boolean ok = amount.isPresent() && amount.get().compareTo(Values.decimal(inv.expected())) >= 0;
                yield result(inv, ok, amount.map(a -> "credit note " + a.toPlainString()).orElse("no credit note"));
            }
            case "LARGE_COMPENSATION_REVIEWED" -> {
                Optional<Decision> d = ledger.approval(p.id());
                boolean ok = d.isPresent() && !d.get().userId().equals(p.createdBy());
                yield result(inv, ok, d.isPresent() ? "approved" : "not approved");
            }
            case "SCOPE" -> {
                Set<EntityKey> written = new TreeSet<>(store.writtenByPlan(p.id()));
                Set<EntityKey> extra = new HashSet<>(written);
                extra.removeAll(expectedWrites);
                Set<EntityKey> missing = new HashSet<>(expectedWrites);
                missing.removeAll(written);
                boolean ok = extra.isEmpty() && missing.isEmpty();
                yield result(inv, ok, written.size() + " written" + (extra.isEmpty() ? "" : ", unexpected " + extra)
                        + (missing.isEmpty() ? "" : ", missing " + missing));
            }
            default -> result(inv, false, "unknown invariant kind; cannot verify");
        };
    }

    private static Verification.Check result(RecoveryPlan.Invariant inv, boolean ok, String actual) {
        return new Verification.Check(inv.kind(), inv.key(), inv.description(), ok, actual);
    }

    private static String show(Object v) {
        return v instanceof BigDecimal d ? d.stripTrailingZeros().toPlainString() : String.valueOf(v);
    }

    // ------------------------------------------------------------------ helpers

    private PlanRecord lock(UUID planId) {
        return ledger.lockPlan(planId).orElseThrow(() -> new RecoveryException(Kind.NOT_FOUND, "Plan not found"));
    }

    private void requireFresh(PlanRecord p) {
        if (!fingerprints.current(p).equals(p.plan().fingerprint())) {
            throw new RecoveryException(Kind.STALE_PLAN,
                    "Records this plan read have changed since it was generated. Generate a new plan.");
        }
    }

    private static void requireStatus(PlanRecord p, PlanStatus status, String verb) {
        if (p.status() != status) {
            throw illegal(p, verb);
        }
    }

    private static RecoveryException illegal(PlanRecord p, String verb) {
        return new RecoveryException(Kind.ILLEGAL_STATE, "A " + p.status() + " plan cannot be " + verb);
    }

    private static StepRecord outcome(RecoveryPlan.Step step, StepStatus status, String detail, Long version,
                                      UUID resultId, UUID actor) {
        return new StepRecord(step.number(), status, detail, version, resultId, actor);
    }

    private static String clean(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
