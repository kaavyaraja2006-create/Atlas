package com.atlas.recovery.execution;

import com.atlas.recovery.engine.EntityKey;
import com.atlas.recovery.engine.FaultyChange;
import com.atlas.recovery.engine.ImpactAnalyzer;
import com.atlas.recovery.engine.ImpactReport;
import com.atlas.recovery.engine.PlanGenerator;
import com.atlas.recovery.engine.RecoveryPlan;
import com.atlas.recovery.engine.SimulatedCommerce;
import com.atlas.recovery.engine.Values;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * SYNTHETIC in-memory stand-in for the recovery tables and the commerce
 * records, built on {@link SimulatedCommerce}. Transactions are real in the
 * sense that matters here: anything thrown inside one (including an
 * {@link Error} used to simulate a crash) undoes all of its writes, to both
 * the ledger and the records. Faults can be injected:
 * <ul>
 *   <li>{@link #crashAfterCommits}: the process "dies" right after the n-th commit;</li>
 *   <li>{@link #crashInsideWrite}: it dies in the middle of the n-th record write, before commit;</li>
 *   <li>{@link #rejectWriteOf}: writes to one record are refused deterministically;</li>
 *   <li>{@link #failWriteOnce}: the next write throws a transient error.</li>
 * </ul>
 */
final class InMemoryRecovery implements Transactions, RecoveryLedger, RecordStore, Fingerprints, Replanner {

    /** Simulates the process dying. An Error, so the workflow cannot catch it as a business failure. */
    static final class SimulatedCrash extends Error {
        private static final long serialVersionUID = 1L;

        SimulatedCrash(String message) {
            super(message);
        }
    }

    final SimulatedCommerce sim;
    final FaultyChange fault;
    final UUID incidentId = UUID.randomUUID();

    // ledger state
    private Map<UUID, PlanRecord> plans = new LinkedHashMap<>();
    private Map<UUID, List<Decision>> decisions = new HashMap<>();
    private Map<UUID, Map<Integer, StepRecord>> steps = new HashMap<>();
    private Map<UUID, List<Verification>> verifications = new HashMap<>();
    private Map<UUID, Set<EntityKey>> written = new HashMap<>();
    private Map<UUID, BigDecimal> creditNotes = new HashMap<>();

    // fault injection
    private int commits;
    private int crashAfterCommit = -1;
    private int writes;
    private int crashInsideWrite = -1;
    private EntityKey rejected;
    private boolean transientFailure;
    private boolean inTx;

    int recordWrites;   // successful field writes and credit notes, committed or not

    InMemoryRecovery(SimulatedCommerce sim, FaultyChange fault) {
        this.sim = sim;
        this.fault = fault;
    }

    // ------------------------------------------------------------------ setup

    RecoveryPlan freshPlan() {
        ImpactReport report = new ImpactAnalyzer().analyze(fault, sim.graph());
        return new PlanGenerator().plan(report, sim.graph(), compensated());
    }

    PlanRecord store(RecoveryPlan plan, UUID author) {
        PlanRecord p = new PlanRecord(UUID.randomUUID(), incidentId, author, PlanStatus.PROPOSED,
                EntityKey.of(fault.ref()), plan);
        plans.put(p.id(), p);
        return p;
    }

    void crashAfterCommits(int n) {
        crashAfterCommit = commits + n;
    }

    void crashInsideWrite(int n) {
        crashInsideWrite = writes + n;
    }

    void rejectWriteOf(EntityKey key) {
        rejected = key;
    }

    void failWriteOnce() {
        transientFailure = true;
    }

    PlanStatus status(UUID planId) {
        return plans.get(planId).status();
    }

    List<Verification> verifications(UUID planId) {
        return verifications.getOrDefault(planId, List.of());
    }

    List<Decision> decisions(UUID planId) {
        return decisions.getOrDefault(planId, List.of());
    }

    // ------------------------------------------------------------------ Transactions

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        if (inTx) {
            throw new IllegalStateException("nested transaction");
        }
        SimulatedCommerce.Mark mark = sim.mark();
        Snapshot before = snapshot();
        inTx = true;
        T result;
        try {
            result = work.get();
        } catch (RuntimeException | Error e) {
            sim.rollback(mark);
            restore(before);
            throw e;
        } finally {
            inTx = false;
        }
        commits++;
        if (commits == crashAfterCommit) {
            throw new SimulatedCrash("crash after commit " + commits);
        }
        return result;
    }

    private record Snapshot(Map<UUID, PlanRecord> plans, Map<UUID, List<Decision>> decisions,
                            Map<UUID, Map<Integer, StepRecord>> steps, Map<UUID, List<Verification>> verifications,
                            Map<UUID, Set<EntityKey>> written, Map<UUID, BigDecimal> creditNotes) {
    }

    private Snapshot snapshot() {
        Map<UUID, List<Decision>> d = new HashMap<>();
        decisions.forEach((k, v) -> d.put(k, new ArrayList<>(v)));
        Map<UUID, Map<Integer, StepRecord>> s = new HashMap<>();
        steps.forEach((k, v) -> s.put(k, new TreeMap<>(v)));
        Map<UUID, List<Verification>> ver = new HashMap<>();
        verifications.forEach((k, v) -> ver.put(k, new ArrayList<>(v)));
        Map<UUID, Set<EntityKey>> w = new HashMap<>();
        written.forEach((k, v) -> w.put(k, new TreeSet<>(v)));
        return new Snapshot(new LinkedHashMap<>(plans), d, s, ver, w, new HashMap<>(creditNotes));
    }

    private void restore(Snapshot s) {
        plans = s.plans();
        decisions = s.decisions();
        steps = s.steps();
        verifications = s.verifications();
        written = s.written();
        creditNotes = s.creditNotes();
    }

    private void requireTx() {
        if (!inTx) {
            throw new IllegalStateException("called outside a transaction");
        }
    }

    // ------------------------------------------------------------------ RecoveryLedger

    @Override
    public Optional<PlanRecord> lockPlan(UUID planId) {
        requireTx();
        return Optional.ofNullable(plans.get(planId));
    }

    @Override
    public void setPlanStatus(UUID planId, PlanStatus from, PlanStatus to) {
        requireTx();
        PlanRecord p = plans.get(planId);
        if (p.status() != from) {
            throw new IllegalStateException("plan is " + p.status() + ", not " + from);
        }
        plans.put(planId, new PlanRecord(p.id(), p.incidentId(), p.createdBy(), to, p.origin(), p.plan()));
    }

    @Override
    public void recordDecision(Decision decision) {
        requireTx();
        decisions.computeIfAbsent(decision.planId(), k -> new ArrayList<>()).add(decision);
    }

    @Override
    public Optional<Decision> approval(UUID planId) {
        requireTx();
        return decisions(planId).stream().filter(Decision::approved).findFirst();
    }

    @Override
    public Map<Integer, StepRecord> steps(UUID planId) {
        requireTx();
        return new TreeMap<>(steps.getOrDefault(planId, Map.of()));
    }

    @Override
    public void recordStep(UUID planId, StepRecord step) {
        requireTx();
        Map<Integer, StepRecord> m = steps.computeIfAbsent(planId, k -> new TreeMap<>());
        if (m.containsKey(step.stepNo())) {
            throw new IllegalStateException("duplicate key (plan_id, step_no)");   // the unique constraint
        }
        m.put(step.stepNo(), step);
    }

    @Override
    public void acknowledgeStep(UUID planId, StepRecord step) {
        requireTx();
        StepRecord old = steps.get(planId).get(step.stepNo());
        if (old.status() != StepStatus.AWAITING_ACKNOWLEDGEMENT) {
            throw new IllegalStateException("not awaiting");
        }
        steps.get(planId).put(step.stepNo(), step);
    }

    @Override
    public void recordVerification(UUID planId, Verification verification) {
        requireTx();
        verifications.computeIfAbsent(planId, k -> new ArrayList<>()).add(verification);
    }

    // ------------------------------------------------------------------ RecordStore

    @Override
    public Optional<Long> lockVersion(EntityKey key) {
        requireTx();
        return sim.current(key) == null ? Optional.empty() : Optional.of(sim.version(key));
    }

    private void beforeWrite(EntityKey key) {
        writes++;
        if (writes == crashInsideWrite) {
            sim.recoveryWrite(key, Map.of("half_written", true), List.of());   // partial work, then death
            throw new SimulatedCrash("crash inside write " + writes);
        }
        if (transientFailure) {
            transientFailure = false;
            throw new IllegalStateException("connection reset (simulated transient failure)");
        }
        if (key.equals(rejected)) {
            throw new StepRejected("check constraint violated (simulated)");
        }
    }

    @Override
    public long applyFields(EntityKey key, long expectedVersion, Map<String, Object> fields,
                            List<RecoveryPlan.Input> inputs, StepContext context) {
        requireTx();
        if (sim.version(key) != expectedVersion) {
            throw new IllegalStateException("applyFields called with a stale version");
        }
        beforeWrite(key);
        long v = sim.recoveryWrite(key, fields, inputs);
        written.computeIfAbsent(context.planId(), k -> new TreeSet<>()).add(key);
        recordWrites++;
        return v;
    }

    @Override
    public UUID issueCreditNote(EntityKey invoice, BigDecimal amount, StepContext context) {
        requireTx();
        beforeWrite(invoice);
        UUID id = sim.recoveryCreditNote(invoice.entityId(), amount);
        creditNotes.put(id, amount);
        written.computeIfAbsent(context.planId(), k -> new TreeSet<>()).add(new EntityKey("credit_note", id));
        recordWrites++;
        return id;
    }

    @Override
    public Optional<Object> field(EntityKey key, String field) {
        requireTx();
        Map<String, Object> s = sim.current(key);
        return s == null ? Optional.empty() : Optional.ofNullable(s.get(field));
    }

    @Override
    public BigDecimal sumOfLineTotals(EntityKey key) {
        requireTx();
        List<UUID> lines = key.entityType().equals("order") ? sim.orderLines.get(key.entityId())
                : sim.invoiceLines.get(key.entityId());
        String lineType = key.entityType().equals("order") ? "order_line" : "invoice_line";
        BigDecimal sum = BigDecimal.ZERO;
        for (UUID id : lines) {
            sum = sum.add(Values.decimal(sim.current(new EntityKey(lineType, id)).get("line_total")));
        }
        return sum;
    }

    @Override
    public Optional<BigDecimal> creditNoteAmount(UUID creditNoteId) {
        requireTx();
        return Optional.ofNullable(creditNotes.get(creditNoteId));
    }

    @Override
    public Set<EntityKey> writtenByPlan(UUID planId) {
        requireTx();
        return Set.copyOf(written.getOrDefault(planId, Set.of()));
    }

    /** Test hook: a write outside the plan's steps, attributed to the plan (a bug the SCOPE check must catch). */
    void attributeStrayWrite(UUID planId, EntityKey key) {
        written.computeIfAbsent(planId, k -> new TreeSet<>()).add(key);
    }

    // ------------------------------------------------------------------ Fingerprints, Replanner

    @Override
    public String current(PlanRecord plan) {
        return new ImpactAnalyzer().analyze(fault, sim.graph()).fingerprint();
    }

    @Override
    public RecoveryPlan replan(PlanRecord plan) {
        return freshPlan();
    }

    /** Credits issued by this incident's executions, per invoice. */
    private Map<EntityKey, BigDecimal> compensated() {
        Map<EntityKey, BigDecimal> out = new HashMap<>();
        for (PlanRecord p : plans.values()) {
            for (StepRecord r : steps.getOrDefault(p.id(), Map.of()).values()) {
                if (r.status() == StepStatus.DONE && r.resultId() != null) {
                    EntityKey invoice = p.plan().steps().get(r.stepNo() - 1).key();
                    out.merge(invoice, creditNotes.get(r.resultId()), BigDecimal::add);
                }
            }
        }
        return out;
    }
}
