package com.atlas.recovery.engine;

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

/**
 * Counterfactual recovery simulation (ATLAS X, Milestone 4): applies each candidate
 * strategy to an in-memory copy of a consistent snapshot and measures the outcome.
 *
 * <p>Isolation by construction: this class has no database access at all. It receives
 * immutable maps captured in one read-only transaction and writes only to its own copies,
 * so a simulation cannot modify production data. Deterministic: iteration is over sorted
 * keys and nothing is random, so the same input gives the same results (timing aside).
 *
 * <p>What it is not: it does not execute SQL, so it measures what a strategy would change,
 * not how long the database would take to change it.
 */
public final class CounterfactualSimulator {

    /** Upper bound on recorded versions in one simulation (memory and time limit). */
    public static final int MAX_VERSIONS = 250_000;

    /** Default cap on stored record details per strategy and per comparison (counts stay exact). */
    public static final int DETAIL_LIMIT = 5_000;

    /**
     * @param records     record-level outcome per strategy (exact counts; details capped)
     * @param comparisons every pair of applicable strategies, computed from the complete record sets
     */
    public record Report(List<StrategyResult> results, int records, int versions, String fingerprint,
                         ImpactReport impact, RecoveryPlan plan, List<FieldConflict> conflicts,
                         Map<RecoveryStrategy, StrategyRecords> recordSets, List<StrategyComparison> comparisons) {
    }

    private record Measured(StrategyResult result, StrategyRecords records, List<StrategyRecords.RecordDetail> complete) {
    }

    private final ImpactAnalyzer analyzer = new ImpactAnalyzer();
    private final PlanGenerator planner = new PlanGenerator();
    private final ConflictAnalyzer conflictAnalyzer = new ConflictAnalyzer();
    private final int maxVersions;
    private final int detailLimit;

    public CounterfactualSimulator() {
        this(MAX_VERSIONS);
    }

    public CounterfactualSimulator(int maxVersions) {
        this(maxVersions, DETAIL_LIMIT);
    }

    public CounterfactualSimulator(int maxVersions, int detailLimit) {
        if (detailLimit < 1) {
            throw new IllegalArgumentException("detailLimit must be positive");
        }
        this.maxVersions = maxVersions;
        this.detailLimit = detailLimit;
    }

    public Report simulate(SimulationInput in) {
        int versions = in.graph().entities().stream().mapToInt(k -> in.graph().history(k).size()).sum();
        if (versions > maxVersions) {
            throw new IllegalArgumentException("Too large to simulate: " + versions + " recorded versions (limit "
                    + maxVersions + "). Narrow the incident or run the experiment harness on a copy.");
        }
        ImpactReport report = analyzer.analyze(in.faulty(), in.graph());
        RecoveryPlan plan = planner.plan(report, in.graph(), in.credited());
        List<FieldConflict> conflicts = conflictAnalyzer.analyze(in.faulty(), report, in.graph());
        Context c = new Context(in, report, plan, conflicts, analyzer.taintSpans(in.faulty(), in.graph()));
        List<StrategyResult> results = new ArrayList<>();
        Map<RecoveryStrategy, StrategyRecords> sets = new LinkedHashMap<>();
        Map<RecoveryStrategy, List<StrategyRecords.RecordDetail>> complete = new LinkedHashMap<>();
        for (RecoveryStrategy s : RecoveryStrategy.values()) {
            long t0 = System.nanoTime();
            Measured m = run(s, c, t0);
            results.add(m.result());
            sets.put(s, m.records());
            if (m.result().applicable()) {
                complete.put(s, m.complete());
            }
        }
        List<StrategyComparison> comparisons = new ArrayList<>();
        List<RecoveryStrategy> applicable = new ArrayList<>(complete.keySet());
        Map<RecoveryStrategy, Map<EntityKey, StrategyRecords.RecordDetail>> acting = new LinkedHashMap<>();
        complete.forEach((s, list) -> acting.put(s, StrategyComparison.acting(list)));
        for (int i = 0; i < applicable.size(); i++) {
            for (int j = i + 1; j < applicable.size(); j++) {
                RecoveryStrategy a = applicable.get(i);
                RecoveryStrategy b = applicable.get(j);
                comparisons.add(StrategyComparison.of(sets.get(a), acting.get(a), sets.get(b), acting.get(b), detailLimit));
            }
        }
        return new Report(results, in.graph().entities().size(), versions, report.fingerprint(), report, plan, conflicts,
                sets, comparisons);
    }

    // ------------------------------------------------------------------ shared, read-only context

    private static final class Context {
        final SimulationInput in;
        final ImpactReport report;
        final RecoveryPlan plan;
        final List<FieldConflict> conflicts;
        final Map<EntityKey, Map<String, ImpactAnalyzer.TaintSpan>> spans;
        final TreeMap<EntityKey, Map<String, Object>> state0 = new TreeMap<>();
        final Map<EntityKey, Map<String, Object>> target = new TreeMap<>();
        final Set<EntityKey> faultyNow = new TreeSet<>();
        final Set<EntityKey> held = new TreeSet<>();
        final Map<EntityKey, Classification> classes = new HashMap<>();
        final Map<EntityKey, Set<String>> legit = new HashMap<>();
        final Long faultSeq;
        final int needsReview;
        /** Kind of the first open conflict per record (needs a person, not yet reviewed). */
        final Map<EntityKey, String> openConflict = new TreeMap<>();
        final Map<EntityKey, List<String>> paths = new HashMap<>();

        Context(SimulationInput in, ImpactReport report, RecoveryPlan plan, List<FieldConflict> conflicts,
                Map<EntityKey, Map<String, ImpactAnalyzer.TaintSpan>> spans) {
            this.in = in;
            this.report = report;
            this.plan = plan;
            this.conflicts = conflicts;
            this.spans = spans;
            for (EntityKey k : in.graph().entities()) {
                if (!in.graph().history(k).isEmpty()) {
                    state0.put(k, in.graph().current(k));
                }
            }
            for (RecoveryPlan.Step st : plan.steps()) {
                if (st.action() == RecoveryPlan.Action.RESTORE_FIELDS || st.action() == RecoveryPlan.Action.UPDATE_FIELDS) {
                    Map<String, Object> t = target.computeIfAbsent(st.key(), k -> new LinkedHashMap<>());
                    st.changes().forEach(ch -> t.put(ch.field(), ch.proposed()));
                }
            }
            for (AffectedRecord r : report.records()) {
                classes.put(r.key(), r.classification());
                switch (r.classification()) {
                    case ORIGIN, DIRECTLY_AFFECTED, INDIRECTLY_AFFECTED, NON_REVERSIBLE -> {
                        if (!r.taintedFields().isEmpty()) {
                            faultyNow.add(r.key());
                        }
                    }
                    case CONFLICTED, REQUIRES_MANUAL_REVIEW -> held.add(r.key());
                    default -> {
                    }
                }
            }
            faultSeq = in.sequence().get(in.faulty().ref());
            needsReview = (int) conflicts.stream()
                    .filter(f -> f.needsReview() && !in.reviewedConflicts().contains(f.key() + "." + f.field())).count();
            conflicts.stream()
                    .filter(f -> f.needsReview() && !in.reviewedConflicts().contains(f.key() + "." + f.field()))
                    .forEach(f -> openConflict.putIfAbsent(f.key(), f.kind().name()));
            report.records().forEach(r -> paths.put(r.key(), r.path()));
            if (faultSeq != null) {
                for (EntityKey k : state0.keySet()) {
                    legit.put(k, legitimateLaterFields(k));
                }
            }
        }

        /** Fields whose current value a person set after the fault, with no provenance into them. */
        private Set<String> legitimateLaterFields(EntityKey k) {
            Set<String> out = new TreeSet<>();
            Map<String, VersionInfo> lastSetter = new HashMap<>();
            for (VersionInfo v : in.graph().history(k)) {
                for (String f : v.changedFields()) {
                    lastSetter.put(f, v);
                }
            }
            for (var e : lastSetter.entrySet()) {
                VersionInfo v = e.getValue();
                Long seq = in.sequence().get(k.at(v.version()));
                if (seq == null || seq <= faultSeq || v.byRecovery() || !"USER".equals(v.actorKind())) {
                    continue;
                }
                Set<String> derived = new TreeSet<>();
                for (Edge edge : in.graph().incoming(k.at(v.version()))) {
                    derived.add(edge.targetField());
                    derived.addAll(RepairRules.derivedFields(k.entityType(), edge.targetField()));
                }
                if (!derived.contains(e.getKey())) {
                    out.add(e.getKey());
                }
            }
            return out;
        }

        Optional<Map<String, Object>> stateAtOrBefore(EntityKey k, long seq) {
            Map<String, Object> found = null;
            for (VersionInfo v : in.graph().history(k)) {
                Long s = in.sequence().get(k.at(v.version()));
                if (s != null && s <= seq) {
                    found = v.after();
                }
            }
            return Optional.ofNullable(found);
        }

        boolean writtenAfter(EntityKey k, long seq) {
            return in.graph().history(k).stream().anyMatch(v -> {
                Long s = in.sequence().get(k.at(v.version()));
                return s != null && s > seq;
            });
        }

        boolean finalRecord(EntityKey k) {
            Map<String, Object> st = state0.get(k);
            return st != null && RepairRules.finality(k.entityType(), st, key -> state0.getOrDefault(key, Map.of()))
                    != RepairRules.Finality.EDITABLE;
        }
    }

    /** What a strategy proposes, before measuring. */
    private static final class Candidate {
        final Map<EntityKey, Map<String, Object>> rows = new TreeMap<>();
        final Map<EntityKey, Map<String, Object>> fields = new TreeMap<>();
        final Set<EntityKey> deletes = new TreeSet<>();
        final Map<EntityKey, BigDecimal> credits = new TreeMap<>();
        final List<String> notes = new ArrayList<>();
        /** Records examined, proposed actions that may not apply (reviews, held steps) and their fields. */
        final Set<EntityKey> inspectedKeys = new TreeSet<>();
        final Map<EntityKey, String> planned = new TreeMap<>();
        final Map<EntityKey, Set<String>> plannedFields = new TreeMap<>();
        int blocked;
        int inspected;
        int manual;
        int surfaced;
        int ignored;

        void field(EntityKey k, String f, Object v) {
            fields.computeIfAbsent(k, x -> new LinkedHashMap<>()).put(f, v);
        }

        void plan(EntityKey k, String action, java.util.Collection<String> fs) {
            planned.merge(k, action, (a, b) -> a);
            plannedFields.computeIfAbsent(k, x -> new TreeSet<>()).addAll(fs);
        }
    }

    // ------------------------------------------------------------------ strategies

    private static List<String> rowDiff(Map<String, Object> a, Map<String, Object> b) {
        Set<String> all = new TreeSet<>(a.keySet());
        all.addAll(b.keySet());
        return all.stream().filter(f -> !Values.same(a.get(f), b.get(f)) && !(a.get(f) == null && b.get(f) == null)).toList();
    }

    private Measured run(RecoveryStrategy s, Context c, long t0) {
        Candidate cand = new Candidate();
        switch (s) {
            case PROVENANCE_CONFLICT_AWARE, PROVENANCE_CONFLICT_BLIND -> {
                boolean aware = s == RecoveryStrategy.PROVENANCE_CONFLICT_AWARE;
                cand.inspected = c.report.records().size();
                c.report.records().forEach(r -> cand.inspectedKeys.add(r.key()));
                // As in execution: a step waits while a step it depends on waits for a person. The
                // conflict-blind variant drops the review steps of conflicted records, so nothing waits.
                Set<Integer> waiting = PlanGenerator.waitingSteps(c.plan.steps(), c.in.faulty().key(),
                        aware ? Set.of() : c.held);
                int blocked = 0;
                for (RecoveryPlan.Step st : c.plan.steps()) {
                    List<String> stepFields = st.changes().stream().map(RecoveryPlan.FieldChange::field).toList();
                    if (st.automatic() && waiting.contains(st.number())) {
                        blocked++;
                        cand.plan(st.key(), "HELD", stepFields);
                        continue;
                    }
                    switch (st.action()) {
                        case RESTORE_FIELDS, UPDATE_FIELDS -> cand.plan(st.key(), "MODIFY", stepFields);
                        case ISSUE_CREDIT_NOTE, SUPPLEMENTARY_CHARGE -> cand.plan(st.key(), "COMPENSATE", List.of());
                        case NO_ACTION -> {
                        }
                        default -> {
                            if (aware || !c.held.contains(st.key())) {
                                cand.plan(st.key(), "REVIEW", stepFields);
                            }
                        }
                    }
                    switch (st.action()) {
                        case RESTORE_FIELDS, UPDATE_FIELDS -> st.changes().forEach(ch -> cand.field(st.key(), ch.field(), ch.proposed()));
                        case ISSUE_CREDIT_NOTE -> cand.credits.merge(st.key(), st.amount(), BigDecimal::add);
                        // An undercharged final invoice is compensated by a supplementary charge, which
                        // a person raises: compensation planned, and a manual step.
                        case SUPPLEMENTARY_CHARGE -> {
                            cand.credits.merge(st.key(), st.amount().negate(), BigDecimal::add);
                            cand.manual++;
                        }
                        case NO_ACTION -> {
                        }
                        default -> {
                            if (aware || !c.held.contains(st.key())) {
                                cand.manual++;
                            }
                        }
                    }
                }
                cand.blocked = blocked;
                if (blocked > 0) {
                    cand.notes.add(blocked + " automatic step(s) wait for a person's decision on a step they depend on.");
                }
                if (aware) {
                    cand.surfaced = c.needsReview;
                } else {
                    cand.ignored = c.needsReview;
                    if (c.needsReview > 0) {
                        cand.notes.add(c.needsReview + " conflict(s) left as they are without telling anyone.");
                    }
                }
            }
            case FULL_RESTORE, NAIVE_FK_ROW -> {
                if (c.faultSeq == null) {
                    return notApplicable(s, "The faulty change has no audit-chain position in the snapshot.");
                }
                Set<EntityKey> scope;
                if (s == RecoveryStrategy.FULL_RESTORE) {
                    scope = c.state0.keySet();
                    cand.notes.add("Models only the records loaded for this incident; a real point-in-time restore "
                            + "rewinds the whole organization, so its true footprint is larger.");
                } else {
                    Optional<Set<EntityKey>> fk = fkClosure(c);
                    if (fk.isEmpty()) {
                        return notApplicable(s, "Foreign-key dependencies are defined for product price "
                                + "changes only; this incident starts at a " + c.in.faulty().key().entityType() + ".");
                    }
                    scope = fk.get();
                }
                cand.inspected = scope.size();
                cand.inspectedKeys.addAll(scope);
                for (EntityKey k : scope) {
                    if (!c.writtenAfter(k, c.faultSeq)) {
                        continue;
                    }
                    Optional<Map<String, Object>> before = c.stateAtOrBefore(k, c.faultSeq);
                    if (before.isPresent()) {
                        cand.rows.put(k, before.get());
                        cand.plan(k, "MODIFY", rowDiff(c.state0.get(k), before.get()));
                    } else {
                        cand.deletes.add(k);
                        cand.plan(k, "DELETE", List.of());
                    }
                }
                cand.ignored = c.needsReview;
            }
            case VERSION_ROW_RESTORE -> {
                cand.inspected = c.spans.size();
                cand.inspectedKeys.addAll(c.spans.keySet());
                for (var e : c.spans.entrySet()) {
                    EntityKey k = e.getKey();
                    long first = e.getValue().values().stream().mapToLong(ImpactAnalyzer.TaintSpan::firstVersion).min()
                            .orElseThrow();
                    long restoreTo = first - 1;
                    if (restoreTo < 0) {
                        cand.deletes.add(k);
                        cand.plan(k, "DELETE", List.of());
                    } else {
                        c.in.graph().version(k, restoreTo).ifPresent(v -> {
                            cand.rows.put(k, v.after());
                            cand.plan(k, "MODIFY", rowDiff(c.state0.getOrDefault(k, Map.of()), v.after()));
                        });
                    }
                }
                cand.ignored = c.needsReview;
            }
            case FIELD_LEVEL_FK -> {
                Optional<Set<EntityKey>> fk = fkClosure(c);
                if (fk.isEmpty() || !c.in.faulty().fields().contains("unit_price")) {
                    return notApplicable(s, "Field-level foreign-key repair is defined for product price "
                            + "changes only.");
                }
                EntityKey origin = c.in.faulty().key();
                Object price = c.in.faulty().before().get("unit_price");
                c.in.faulty().fields().forEach(f -> cand.field(origin, f, c.in.faulty().before().get(f)));
                Map<EntityKey, Map<String, Object>> after = new TreeMap<>();
                c.state0.forEach((k, v) -> after.put(k, new LinkedHashMap<>(v)));
                for (EntityKey k : fk.get()) {
                    if (k.entityType().equals("order_line")) {
                        BigDecimal p = Values.decimal(price);
                        BigDecimal total = p.multiply(Values.decimal(after.get(k).get("quantity")));
                        cand.field(k, "unit_price", p);
                        cand.field(k, "line_total", total);
                        after.get(k).put("unit_price", p);
                        after.get(k).put("line_total", total);
                    }
                }
                for (EntityKey k : fk.get()) {
                    if (k.entityType().equals("invoice_line")) {
                        EntityKey src = new EntityKey("order_line", UUID.fromString(after.get(k).get("order_line_id").toString()));
                        if (after.containsKey(src)) {
                            cand.field(k, "unit_price", after.get(src).get("unit_price"));
                            cand.field(k, "line_total", after.get(src).get("line_total"));
                            after.get(k).put("unit_price", after.get(src).get("unit_price"));
                            after.get(k).put("line_total", after.get(src).get("line_total"));
                        }
                    }
                }
                Map<String, BigDecimal> orderSums = childTotals(after, "order_line", "order_id");
                Map<String, BigDecimal> invoiceSums = childTotals(after, "invoice_line", "invoice_id");
                for (EntityKey k : fk.get()) {
                    Map<String, BigDecimal> sums = k.entityType().equals("order") ? orderSums
                            : k.entityType().equals("invoice") ? invoiceSums : null;
                    if (sums != null) {
                        cand.field(k, "total_amount", sums.getOrDefault(k.entityId().toString(), BigDecimal.ZERO));
                    }
                }
                // fkClosure already contains the origin (it used to be counted twice here).
                cand.inspected = fk.get().size();
                cand.inspectedKeys.addAll(fk.get());
                cand.fields.forEach((k, f) -> cand.plan(k, "MODIFY", f.keySet()));
                cand.ignored = c.needsReview;
            }
            default -> throw new IllegalStateException(s.name());
        }
        return measure(s, c, cand, t0, detailLimit);
    }

    private static Measured notApplicable(RecoveryStrategy s, String why) {
        return new Measured(StrategyResult.notApplicable(s, why), StrategyRecords.none(s), List.of());
    }

    /** The origin product and everything linked to it by foreign keys: lines, their orders, invoice lines, invoices. */
    private static Optional<Set<EntityKey>> fkClosure(Context c) {
        EntityKey origin = c.in.faulty().key();
        if (!origin.entityType().equals("product")) {
            return Optional.empty();
        }
        String id = origin.entityId().toString();
        Set<EntityKey> out = new TreeSet<>();
        out.add(origin);
        Set<String> lineIds = new TreeSet<>();
        Set<String> orderIds = new TreeSet<>();
        Set<String> invoiceIds = new TreeSet<>();
        c.state0.forEach((k, v) -> {
            if (k.entityType().equals("order_line") && id.equals(String.valueOf(v.get("product_id")))) {
                out.add(k);
                lineIds.add(k.entityId().toString());
                orderIds.add(String.valueOf(v.get("order_id")));
            }
        });
        c.state0.forEach((k, v) -> {
            if (k.entityType().equals("order") && orderIds.contains(k.entityId().toString())) {
                out.add(k);
            }
            if (k.entityType().equals("invoice_line") && lineIds.contains(String.valueOf(v.get("order_line_id")))) {
                out.add(k);
                invoiceIds.add(String.valueOf(v.get("invoice_id")));
            }
        });
        c.state0.forEach((k, v) -> {
            if (k.entityType().equals("invoice") && invoiceIds.contains(k.entityId().toString())) {
                out.add(k);
            }
        });
        return Optional.of(out);
    }

    /**
     * Sum of {@code line_total} over the children of each parent (keyed by the parent id as text),
     * in one pass. Checking each parent by scanning the whole state made simulation quadratic:
     * 2,000 orders took about 8 s for one strategy (found by the Milestone 8 experiment).
     */
    private static Map<String, BigDecimal> childTotals(Map<EntityKey, Map<String, Object>> state, String childType,
                                                       String parentField) {
        Map<String, BigDecimal> totals = new HashMap<>();
        for (var e : state.entrySet()) {
            if (e.getKey().entityType().equals(childType)) {
                Object parent = e.getValue().get(parentField);
                if (parent != null) {
                    totals.merge(String.valueOf(parent), Values.decimal(e.getValue().get("line_total")), BigDecimal::add);
                }
            }
        }
        return totals;
    }

    // ------------------------------------------------------------------ measurement

    private static Measured measure(RecoveryStrategy s, Context c, Candidate cand, long t0, int detailLimit) {
        TreeMap<EntityKey, Map<String, Object>> fin = new TreeMap<>();
        c.state0.forEach((k, v) -> fin.put(k, new LinkedHashMap<>(v)));
        cand.rows.forEach((k, row) -> fin.put(k, new LinkedHashMap<>(row)));
        cand.fields.forEach((k, f) -> fin.computeIfAbsent(k, x -> new LinkedHashMap<>()).putAll(f));
        cand.deletes.forEach(fin::remove);

        int modified = 0;
        int writes = 0;
        int collateral = 0;
        int legitLost = 0;
        int legitTotal = 0;
        Set<EntityKey> touched = new TreeSet<>(c.state0.keySet());
        touched.addAll(fin.keySet());
        Map<EntityKey, Set<String>> changed = new TreeMap<>();
        Set<EntityKey> preservedKeys = new TreeSet<>();
        for (EntityKey k : touched) {
            Map<String, Object> a = c.state0.getOrDefault(k, Map.of());
            Map<String, Object> b = fin.get(k);
            Set<String> diff = new TreeSet<>();
            if (b == null) {
                diff.addAll(a.keySet());
            } else {
                Set<String> all = new TreeSet<>(a.keySet());
                all.addAll(b.keySet());
                for (String f : all) {
                    if (!Values.same(a.get(f), b.get(f)) && !(a.get(f) == null && b.get(f) == null)) {
                        diff.add(f);
                    }
                }
            }
            Set<String> legit = c.legit.getOrDefault(k, Set.of());
            legitTotal += legit.size();
            int lost = (int) legit.stream().filter(diff::contains).count();
            legitLost += lost;
            if (!legit.isEmpty() && lost == 0) {
                preservedKeys.add(k);
            }
            if (diff.isEmpty()) {
                continue;
            }
            changed.put(k, diff);
            modified++;
            writes += diff.size();
            boolean affected = c.faultyNow.contains(k) || c.held.contains(k) || k.equals(c.in.faulty().key());
            if (!affected) {
                collateral++;
            }
        }

        Set<EntityKey> repairedKeys = new TreeSet<>();
        for (EntityKey k : c.faultyNow) {
            if (c.classes.get(k) == Classification.NON_REVERSIBLE) {
                if (compensated(k, cand, c) && !changed.containsKey(k)) {
                    repairedKeys.add(k);
                }
                continue;
            }
            Map<String, Object> t = c.target.get(k);
            Map<String, Object> now = fin.get(k);
            if (t != null && now != null && t.entrySet().stream().allMatch(e -> Values.same(now.get(e.getKey()), e.getValue()))) {
                repairedKeys.add(k);
            }
        }
        int repaired = repairedKeys.size();

        Set<String> before = violations(c.state0, c, Set.of());
        Set<String> after = violations(fin, c, changed.keySet());
        after.removeAll(before);
        List<String> listed = new ArrayList<>(after);

        // ---- record level: every record with a part in this strategy, exact counts, capped details
        Set<EntityKey> unresolvedKeys = new TreeSet<>(c.faultyNow);
        unresolvedKeys.addAll(c.held);
        unresolvedKeys.removeAll(repairedKeys);
        Set<EntityKey> universe = new TreeSet<>(cand.inspectedKeys);
        universe.addAll(cand.planned.keySet());
        universe.addAll(changed.keySet());
        universe.addAll(cand.credits.keySet());
        universe.addAll(preservedKeys);
        universe.addAll(c.openConflict.keySet());
        universe.addAll(unresolvedKeys);
        List<StrategyRecords.RecordDetail> complete = new ArrayList<>();
        int inspectedN = 0;
        int proposedN = 0;
        int modifiedN = 0;
        int preservedN = 0;
        int conflictingN = 0;
        int unresolvedN = 0;
        for (EntityKey k : universe) {
            boolean deleted = cand.deletes.contains(k);
            boolean isChanged = changed.containsKey(k);
            String action = deleted ? "DELETE" : isChanged ? "MODIFY" : cand.credits.containsKey(k) ? "COMPENSATE"
                    : cand.planned.getOrDefault(k, "NONE");
            if (action.equals("MODIFY") && !isChanged) {
                action = "NONE";   // planned, but the value is already what it would write
            }
            Set<String> fs = new TreeSet<>(isChanged && !deleted ? changed.get(k) : Set.of());
            if (!isChanged && (action.equals("HELD") || action.equals("REVIEW"))) {
                fs.addAll(cand.plannedFields.getOrDefault(k, Set.of()));
            }
            boolean ins = cand.inspectedKeys.contains(k);
            boolean prop = cand.planned.containsKey(k) || cand.credits.containsKey(k) || isChanged;
            boolean pres = preservedKeys.contains(k) && !deleted;
            boolean conf = c.openConflict.containsKey(k);
            boolean unres = unresolvedKeys.contains(k);
            inspectedN += ins ? 1 : 0;
            proposedN += prop ? 1 : 0;
            modifiedN += isChanged ? 1 : 0;
            preservedN += pres ? 1 : 0;
            conflictingN += conf ? 1 : 0;
            unresolvedN += unres ? 1 : 0;
            complete.add(new StrategyRecords.RecordDetail(k, action, ins, prop, isChanged, pres, conf, unres,
                    new ArrayList<>(fs), c.paths.getOrDefault(k, List.of()), c.openConflict.get(k)));
        }
        List<StrategyRecords.RecordDetail> ranked = new ArrayList<>(complete);
        ranked.sort(java.util.Comparator.comparingInt(StrategyRecords.RecordDetail::priority)
                .thenComparing(StrategyRecords.RecordDetail::key));
        List<StrategyRecords.RecordDetail> stored = ranked.size() > detailLimit ? ranked.subList(0, detailLimit) : ranked;
        StrategyRecords records = new StrategyRecords(s, complete.size(), inspectedN, proposedN, modifiedN, preservedN,
                conflictingN, unresolvedN, legitTotal - legitLost, cand.blocked, stored, complete.size() > detailLimit);

        long micros = (System.nanoTime() - t0) / 1_000;
        StrategyResult result = new StrategyResult(s, true, null, cand.inspected, modified, cand.deletes.size(),
                c.faultyNow.size(), repaired, collateral, legitLost, cand.surfaced, cand.ignored, after.size(), writes,
                cand.credits.size(), cand.manual, micros, listed.subList(0, Math.min(50, listed.size())), cand.notes);
        return new Measured(result, records, complete);
    }

    private static boolean compensated(EntityKey k, Candidate cand, Context c) {
        if (cand.credits.containsKey(k)) {
            return true;
        }
        if (k.entityType().equals("invoice_line")) {
            Object inv = c.state0.get(k).get("invoice_id");
            return inv != null && cand.credits.containsKey(new EntityKey("invoice", UUID.fromString(inv.toString())));
        }
        return false;
    }

    /** Business rules over a state; edits to records that were final count when {@code changed} holds them. */
    private static Set<String> violations(Map<EntityKey, Map<String, Object>> state, Context c, Set<EntityKey> changed) {
        Set<String> out = new TreeSet<>();
        Map<String, Map<String, BigDecimal>> sumsNow = Map.of(
                "order", childTotals(state, "order_line", "order_id"),
                "invoice", childTotals(state, "invoice_line", "invoice_id"));
        Map<String, Map<String, BigDecimal>> sumsBefore = Map.of(
                "order", childTotals(c.state0, "order_line", "order_id"),
                "invoice", childTotals(c.state0, "invoice_line", "invoice_id"));
        for (var e : state.entrySet()) {
            EntityKey k = e.getKey();
            Map<String, Object> v = e.getValue();
            switch (k.entityType()) {
                case "order_line", "invoice_line" -> {
                    Object q = v.get("quantity");
                    Object p = v.get("unit_price");
                    Object t = v.get("line_total");
                    if (q != null && p != null && t != null
                            && Values.decimal(p).multiply(Values.decimal(q)).compareTo(Values.decimal(t)) != 0) {
                        out.add(k + ": line_total is not quantity × unit_price");
                    }
                    String parentType = k.entityType().equals("order_line") ? "order" : "invoice";
                    Object parent = v.get(k.entityType().equals("order_line") ? "order_id" : "invoice_id");
                    if (parent != null && c.state0.containsKey(new EntityKey(parentType, UUID.fromString(parent.toString())))
                            && !state.containsKey(new EntityKey(parentType, UUID.fromString(parent.toString())))) {
                        out.add(k + ": its " + parentType + " no longer exists");
                    }
                }
                case "order", "invoice" -> {
                    String id = k.entityId().toString();
                    BigDecimal now = sumsNow.get(k.entityType()).get(id);
                    boolean hadChildren = sumsBefore.get(k.entityType()).containsKey(id);
                    if ((now != null || hadChildren) && v.get("total_amount") != null
                            && Values.decimal(v.get("total_amount")).compareTo(now == null ? BigDecimal.ZERO : now) != 0) {
                        out.add(k + ": total_amount is not the sum of its lines");
                    }
                }
                default -> {
                }
            }
        }
        for (EntityKey k : changed) {
            if (c.finalRecord(k)) {
                out.add(k + ": a legally final record was " + (state.containsKey(k) ? "edited" : "deleted")
                        + " instead of compensated");
            }
        }
        return out;
    }
}
