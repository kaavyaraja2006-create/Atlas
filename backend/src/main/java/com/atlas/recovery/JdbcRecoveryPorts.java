package com.atlas.recovery;

import com.atlas.audit.ChangeRecorder;
import com.atlas.audit.TransactionIds;
import com.atlas.billing.CreditNote;
import com.atlas.common.db.DocumentKind;
import com.atlas.common.db.DocumentNumbers;
import com.atlas.common.json.Json;
import com.atlas.common.tenancy.Actor;
import com.atlas.provenance.ProvenanceRecorder;
import com.atlas.recovery.engine.EntityKey;
import com.atlas.recovery.engine.RecoveryPlan;
import com.atlas.recovery.engine.Values;
import com.atlas.recovery.execution.Decision;
import com.atlas.recovery.execution.Fingerprints;
import com.atlas.recovery.execution.PlanRecord;
import com.atlas.recovery.execution.PlanStatus;
import com.atlas.recovery.execution.RecordStore;
import com.atlas.recovery.execution.RecoveryLedger;
import com.atlas.recovery.execution.Replanner;
import com.atlas.recovery.execution.StepRecord;
import com.atlas.recovery.execution.StepRejected;
import com.atlas.recovery.execution.StepStatus;
import com.atlas.recovery.execution.Verification;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The workflow's ports on PostgreSQL, for one organization and one requesting
 * user. Created per request; every method runs inside the workflow's
 * transaction (the recorders require one).
 */
final class JdbcRecoveryPorts implements RecoveryLedger, RecordStore, Fingerprints, Replanner {

    /** Columns each type's recovery step may change. Anything else is refused. */
    private static final Map<String, Set<String>> WRITABLE = Map.of(
            "product", Set.of("name", "unit_price", "unit_cost", "active"),
            "order_line", Set.of("unit_price", "line_total"),
            "order", Set.of("total_amount"),
            "invoice_line", Set.of("unit_price", "line_total"),
            "invoice", Set.of("total_amount"));

    private final UUID orgId;
    private final UUID userId;
    private final ExecutionRepository repo;
    private final IncidentAnalysis analysis;
    private final ChangeRecorder changes;
    private final ProvenanceRecorder provenance;
    private final DocumentNumbers numbers;
    private final Json json;
    /** What lockPlan read, for the inserts and the analysis that follow in the same transaction. */
    private final Map<UUID, ExecutionRepository.PlanForExecution> locked = new HashMap<>();
    private final Map<UUID, RecoveryPlan> plans = new HashMap<>();

    JdbcRecoveryPorts(UUID orgId, UUID userId, ExecutionRepository repo, IncidentAnalysis analysis,
                      ChangeRecorder changes, ProvenanceRecorder provenance, DocumentNumbers numbers, Json json) {
        this.orgId = orgId;
        this.userId = userId;
        this.repo = repo;
        this.analysis = analysis;
        this.changes = changes;
        this.provenance = provenance;
        this.numbers = numbers;
        this.json = json;
    }

    // ------------------------------------------------------------------ RecoveryLedger

    @Override
    public Optional<PlanRecord> lockPlan(UUID planId) {
        ExecutionRepository.PlanForExecution known = locked.get(planId);
        if (known != null) {
            // Content is immutable; only the status can have moved. Lock the row without re-reading the plan.
            return repo.lockPlanStatus(orgId, planId).map(status -> new PlanRecord(known.id(), known.incidentId(),
                    known.createdBy(), PlanStatus.valueOf(status), new EntityKey(known.originType(), known.originId()),
                    plans.get(planId)));
        }
        return repo.lockPlan(orgId, planId).map(row -> {
            locked.put(planId, row);
            PlanRecord p = new PlanRecord(row.id(), row.incidentId(), row.createdBy(), PlanStatus.valueOf(row.status()),
                    new EntityKey(row.originType(), row.originId()),
                    plans.computeIfAbsent(planId, k -> json.read(row.planJson(), RecoveryPlan.class)));
            return p;
        });
    }

    @Override
    public void setPlanStatus(UUID planId, PlanStatus from, PlanStatus to) {
        if (!repo.setPlanStatus(orgId, planId, from.name(), to.name())) {
            throw new IllegalStateException("Plan " + planId + " is no longer " + from);
        }
    }

    @Override
    public void recordDecision(Decision d) {
        repo.insertDecision(orgId, d.planId(), d.approved() ? "APPROVED" : "REJECTED", d.fingerprint(), d.comment(),
                d.userId());
    }

    @Override
    public Optional<Decision> approval(UUID planId) {
        return repo.decisions(orgId, planId).stream().filter(d -> d.decision().equals("APPROVED")).findFirst()
                .map(d -> new Decision(planId, d.decidedBy(), true, d.fingerprint(), d.comment()));
    }

    @Override
    public Map<Integer, StepRecord> steps(UUID planId) {
        Map<Integer, StepRecord> out = new TreeMap<>();
        for (ExecutionRepository.ActionRow a : repo.actions(orgId, planId)) {
            StepStatus status = StepStatus.valueOf(a.status());
            out.put(a.stepNo(), status == StepStatus.ACKNOWLEDGED
                    ? new StepRecord(a.stepNo(), status, a.acknowledgement(), null, null, a.acknowledgedBy())
                    : new StepRecord(a.stepNo(), status, a.detail(), a.resultVersion(), a.resultEntityId(), a.executedBy()));
        }
        return out;
    }

    @Override
    public void recordStep(UUID planId, StepRecord step) {
        RecoveryPlan.Step s = plan(planId).steps().get(step.stepNo() - 1);
        repo.insertAction(orgId, planId, step.stepNo(), s.action().name(), s.key(), s.expectedVersion(),
                step.status().name(), truncate(step.detail()), step.resultVersion(), step.resultId(),
                TransactionIds.current(), step.actorId());
    }

    @Override
    public void acknowledgeStep(UUID planId, StepRecord step) {
        if (!repo.acknowledge(orgId, planId, step.stepNo(), step.actorId(), truncate(step.detail()))) {
            throw new IllegalStateException("Step " + step.stepNo() + " is not awaiting acknowledgement");
        }
    }

    @Override
    public void recordVerification(UUID planId, Verification v) {
        repo.insertVerification(orgId, planId, v.outcome().name(), v.checks().size(), (int) v.failures(),
                v.pendingManualSteps(), json.write(v.checks()), userId);
    }

    // ------------------------------------------------------------------ RecordStore

    @Override
    public Optional<Long> lockVersion(EntityKey key) {
        if (!repo.lockRecord(orgId, key)) {
            return Optional.empty();
        }
        return repo.latestState(orgId, key).map(ExecutionRepository.LatestState::version);
    }

    @Override
    public long applyFields(EntityKey key, long expectedVersion, Map<String, Object> fields,
                            List<RecoveryPlan.Input> inputs, StepContext ctx) {
        Set<String> writable = WRITABLE.get(key.entityType());
        if (writable == null || !writable.containsAll(fields.keySet())) {
            throw new StepRejected("recovery may not change " + fields.keySet() + " of a " + key.entityType());
        }
        ExecutionRepository.LatestState state = repo.latestState(orgId, key)
                .orElseThrow(() -> new StepRejected("the record has no history"));
        if (state.version() != expectedVersion) {
            throw new IllegalStateException("applyFields called at version " + state.version() + ", plan expects "
                    + expectedVersion);
        }
        Map<String, Object> before = state.after();
        Map<String, Object> after = new LinkedHashMap<>(before);
        after.putAll(fields);
        long next = expectedVersion + 1;
        try {
            switch (key.entityType()) {
                case "product" -> requireVersion(repo.recoverProduct(orgId, key.entityId(), after), next, key);
                case "order_line" -> {
                    ExecutionRepository.LineResult r = repo.recoverOrderLine(orgId, key.entityId(), after.get("unit_price"))
                            .orElseThrow(() -> new StepRejected("order line not found"));
                    requireVersion(Optional.of(r.version()), next, key);
                    after.put("line_total", checkedLineTotal(r.lineTotal(), fields));
                }
                case "order" -> requireVersion(repo.recoverOrderTotal(orgId, key.entityId(), after.get("total_amount"))
                        .or(() -> {
                            throw new StepRejected("the corrected total does not equal the sum of the order's lines");
                        }), next, key);
                case "invoice_line" -> after.put("line_total", checkedLineTotal(
                        repo.recoverInvoiceLine(orgId, key.entityId(), after.get("unit_price"))
                                .orElseThrow(() -> new StepRejected("invoice line not found")), fields));
                case "invoice" -> requireVersion(repo.recoverInvoiceTotal(orgId, key.entityId(), after.get("total_amount"))
                        .or(() -> {
                            throw new StepRejected("the corrected total does not equal the sum of the invoice's lines");
                        }), next, key);
                default -> throw new StepRejected("no repair rule for " + key.entityType());
            }
        } catch (DataIntegrityViolationException e) {
            throw new StepRejected(rootMessage(e));
        }
        changes.recordUpdate(orgId, Actor.recovery(ctx.executedBy()), key.entityType(), key.entityId(), next, before,
                after, truncate(ctx.reason()));
        for (RecoveryPlan.Input in : inputs) {
            long sourceVersion = repo.latestState(orgId, in.source())
                    .orElseThrow(() -> new StepRejected("input " + in.source() + " has no history")).version();
            provenance.record(orgId, in.source().at(sourceVersion), in.sourceField(), key.at(next), in.targetField(),
                    in.relation().name(), ProvenanceRecorder.OP_RECOVERY_UPDATE);
        }
        return next;
    }

    @Override
    public UUID issueCreditNote(EntityKey invoice, BigDecimal amount, StepContext ctx) {
        String number = numbers.next(orgId, DocumentKind.CREDIT_NOTE);
        String reason = truncate(ctx.reason());
        ExecutionRepository.CreditNoteRow row = repo.recoveryCreditNote(orgId, number, invoice.entityId(), amount, reason)
                .orElseThrow(() -> new StepRejected("the invoice is not ISSUED, or the credit exceeds what remains on it"));
        CreditNote note = new CreditNote(row.id(), row.invoiceId(), row.number(), row.amount(), row.reason(), row.status(),
                row.issuedAt(), row.createdAt(), row.version());
        changes.recordInsert(orgId, Actor.recovery(ctx.executedBy()), CreditNote.ENTITY_TYPE, row.id(), note.snapshot(),
                reason);
        return row.id();
    }

    @Override
    public Optional<Object> field(EntityKey key, String field) {
        return repo.currentRow(orgId, key).map(row -> row.get(field));
    }

    @Override
    public BigDecimal sumOfLineTotals(EntityKey key) {
        return repo.sumOfLines(orgId, key);
    }

    @Override
    public Optional<BigDecimal> creditNoteAmount(UUID creditNoteId) {
        return repo.creditNoteAmount(orgId, creditNoteId);
    }

    @Override
    public Set<EntityKey> writtenByPlan(UUID planId) {
        return new LinkedHashSet<>(repo.writtenByPlan(orgId, planId));
    }

    // ------------------------------------------------------------------ Fingerprints, Replanner

    @Override
    public String current(PlanRecord plan) {
        return analysis.analyse(orgId, lockedRow(plan.id()).faultyChangeEventId()).report().fingerprint();
    }

    @Override
    public RecoveryPlan replan(PlanRecord plan) {
        IncidentAnalysis.Result r = analysis.analyse(orgId, lockedRow(plan.id()).faultyChangeEventId());
        return analysis.plan(orgId, plan.incidentId(), r);
    }

    // ------------------------------------------------------------------ helpers

    private ExecutionRepository.PlanForExecution lockedRow(UUID planId) {
        ExecutionRepository.PlanForExecution row = locked.get(planId);
        if (row == null) {
            throw new IllegalStateException("plan " + planId + " was not locked in this transaction");
        }
        return row;
    }

    private RecoveryPlan plan(UUID planId) {
        RecoveryPlan p = plans.get(planId);
        if (p == null) {
            throw new IllegalStateException("plan " + planId + " was not locked in this transaction");
        }
        return p;
    }

    private static void requireVersion(Optional<Long> written, long expected, EntityKey key) {
        long v = written.orElseThrow(() -> new StepRejected(key.entityType() + " not found"));
        if (v != expected) {
            // The row's version column disagrees with its history: something wrote it without recording a change.
            throw new StepRejected("row version " + v + " is out of step with its history (expected " + expected + ")");
        }
    }

    private static BigDecimal checkedLineTotal(BigDecimal stored, Map<String, Object> fields) {
        Object planned = fields.get("line_total");
        if (planned != null && !Values.same(planned, stored)) {
            throw new StepRejected("line total would be " + stored.toPlainString() + ", the plan says " + planned);
        }
        return stored;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        String m = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        int nl = m.indexOf('\n');
        return nl > 0 ? m.substring(0, nl) : m;
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 1000 ? s : s.substring(0, 997) + "...";
    }
}
