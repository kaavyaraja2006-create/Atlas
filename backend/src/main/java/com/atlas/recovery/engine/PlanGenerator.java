package com.atlas.recovery.engine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Turns an impact report into an ordered recovery plan.
 *
 * <p>Corrected values are computed in dependency order (Kahn's algorithm over
 * the records in the report), so a product's restored price is known before
 * the lines priced from it, and those before the totals summing them:
 * <ul>
 *   <li>DERIVED_VALUE / COPIED_INTO: the target field takes the source's corrected value;</li>
 *   <li>AGGREGATED_INTO: the target field is the sum over <em>all</em> sources of the
 *       version that defined it, corrected where affected, current otherwise;</li>
 *   <li>then intra-record formulas (line_total) are re-applied.</li>
 * </ul>
 * Values of the origin's faulty fields are corrected to their before-values:
 * what the record should have said when each consumer read it.
 */
public final class PlanGenerator {

    private static final BigDecimal LARGE_COMPENSATION = new BigDecimal("100000");

    public RecoveryPlan plan(ImpactReport report, IncidentGraph graph) {
        return plan(report, graph, Map.of());
    }

    /**
     * @param compensated amounts already credited by earlier recovery executions of this
     *                    same incident, per invoice; a re-plan after a partial run
     *                    credits only what is still owed
     */
    public RecoveryPlan plan(ImpactReport report, IncidentGraph graph, Map<EntityKey, BigDecimal> compensated) {
        FaultyChange faulty = report.faulty();
        Map<EntityKey, AffectedRecord> inPlan = new TreeMap<>();
        report.impacted().forEach(r -> inPlan.put(r.key(), r));

        Map<EntityKey, Map<String, Object>> corrected = new HashMap<>();
        Map<EntityKey, Integer> stepOf = new HashMap<>();
        List<RecoveryPlan.Step> steps = new ArrayList<>();
        List<RecoveryPlan.Invariant> invariants = new ArrayList<>();

        for (EntityKey key : topologicalOrder(inPlan.keySet(), graph)) {
            AffectedRecord rec = inPlan.get(key);
            Map<String, Object> current = graph.current(key);
            List<RecoveryPlan.Input> inputs = new ArrayList<>();
            Map<String, Object> proposed = key.equals(faulty.key())
                    ? restoreOrigin(current, faulty)
                    : recompute(key, rec, current, graph, faulty, corrected, inputs);
            corrected.put(key, proposed);

            List<RecoveryPlan.FieldChange> changes = diff(current, proposed);
            List<Integer> deps = dependencies(key, rec, graph, stepOf);
            RecoveryPlan.Step step = stepFor(steps.size() + 1, rec, current, proposed, changes, deps, graph, inputs,
                    compensated.getOrDefault(key, BigDecimal.ZERO));
            if (step != null) {
                steps.add(step);
                stepOf.put(key, step.number());
                invariants.addAll(invariantsFor(step, proposed));
            }
        }
        List<RecoveryPlan.Step> grouped = holdConsistencyGroups(steps, faulty.key());
        steps.clear();
        steps.addAll(grouped);
        RecoveryPlan.Risk risk = steps.stream().map(RecoveryPlan.Step::risk)
                .max(Comparator.naturalOrder()).orElse(RecoveryPlan.Risk.LOW);
        boolean approval = risk != RecoveryPlan.Risk.LOW || steps.size() > 25;
        if (!steps.isEmpty()) {
            invariants.add(new RecoveryPlan.Invariant("SCOPE", null, null,
                    "Only the " + steps.size() + " records named in this plan change, each from its expected version", null));
        }
        return new RecoveryPlan(report.fingerprint(), steps, risk, approval, invariants);
    }

    /**
     * Keeps a total and its lines in step (ATLAS X, found by the Milestone 8 experiment).
     * When a total waits for a person (one of its lines is under review), the automatic
     * corrections of its OTHER lines wait too: applying them alone would leave the stored
     * total disagreeing with the sum of its lines until someone acted. Each such step gains
     * a dependency on the review step that holds the total; the executor then reports it
     * as blocked, and nothing in the group is written. Repeats until stable, since a held
     * line can in turn hold a further total.
     */
    static List<RecoveryPlan.Step> holdConsistencyGroups(List<RecoveryPlan.Step> in, EntityKey origin) {
        List<RecoveryPlan.Step> steps = new ArrayList<>(in);
        Map<EntityKey, Integer> index = new HashMap<>();
        for (int i = 0; i < steps.size(); i++) {
            index.put(steps.get(i).key(), i);
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            Set<Integer> waiting = waitingSteps(steps, origin, Set.of());
            for (RecoveryPlan.Step total : steps) {
                if (!waiting.contains(total.number()) || !total.automatic()) {
                    continue;
                }
                List<Integer> gates = total.dependsOn().stream().filter(waiting::contains).sorted().toList();
                if (gates.isEmpty()) {
                    continue;
                }
                int gate = gates.get(0);
                for (RecoveryPlan.Input input : total.inputs()) {
                    if (input.relation() != Edge.Relation.AGGREGATED_INTO) {
                        continue;
                    }
                    Integer at = index.get(input.source());
                    if (at == null) {
                        continue;
                    }
                    RecoveryPlan.Step member = steps.get(at);
                    if (!member.automatic() || waiting.contains(member.number()) || member.dependsOn().contains(gate)) {
                        continue;
                    }
                    List<Integer> deps = new ArrayList<>(member.dependsOn());
                    deps.add(gate);
                    steps.set(at, new RecoveryPlan.Step(member.number(), member.action(), member.key(),
                            member.expectedVersion(), member.changes(), deps, member.risk(),
                            member.reason() + " (held with its total until step " + gate + " is decided)",
                            member.amount(), member.inputs()));
                    changed = true;
                }
            }
        }
        return steps;
    }

    /**
     * Steps the executor cannot complete unattended, exactly as {@code RecoveryWorkflow} decides:
     * a review or a supplementary charge awaits a person's acknowledgement, and every step that
     * depends on one is blocked (transitively, in any order). A review of the ORIGIN does not
     * block: its dependants are recomputed from the restored value regardless.
     *
     * @param ignoring records whose review steps are dropped (the conflict-blind simulation)
     */
    static Set<Integer> waitingSteps(List<RecoveryPlan.Step> steps, EntityKey origin, Set<EntityKey> ignoring) {
        Set<Integer> waiting = new HashSet<>();
        boolean grew = true;
        while (grew) {
            grew = false;
            for (RecoveryPlan.Step st : steps) {
                if (waiting.contains(st.number())) {
                    continue;
                }
                boolean awaits = (st.action() == RecoveryPlan.Action.MANUAL_REVIEW && !st.key().equals(origin)
                        && !ignoring.contains(st.key())) || st.action() == RecoveryPlan.Action.SUPPLEMENTARY_CHARGE;
                if (awaits || st.dependsOn().stream().anyMatch(waiting::contains)) {
                    waiting.add(st.number());
                    grew = true;
                }
            }
        }
        return waiting;
    }

    private static Map<String, Object> restoreOrigin(Map<String, Object> current, FaultyChange faulty) {
        Map<String, Object> proposed = new LinkedHashMap<>(current);
        faulty.fields().forEach(f -> proposed.put(f, faulty.before().get(f)));
        return proposed;
    }

    /** Corrected value of {@code field} as {@code source} should have provided it. */
    private static Object correctedValue(EntityKey source, String field, IncidentGraph graph, FaultyChange faulty,
                                         Map<EntityKey, Map<String, Object>> corrected) {
        if (source.equals(faulty.key()) && faulty.fields().contains(field)) {
            return faulty.before().get(field);
        }
        Map<String, Object> repaired = corrected.get(source);
        if (repaired != null && repaired.containsKey(field)) {
            return repaired.get(field);
        }
        return graph.current(source).get(field);
    }

    private static Map<String, Object> recompute(EntityKey key, AffectedRecord rec, Map<String, Object> current,
                                                 IncidentGraph graph, FaultyChange faulty,
                                                 Map<EntityKey, Map<String, Object>> corrected,
                                                 List<RecoveryPlan.Input> inputsOut) {
        Map<String, Object> proposed = new LinkedHashMap<>(current);
        for (String field : rec.taintedFields()) {
            List<Edge> inputs = definingInputs(key, field, rec.currentVersion(), graph);
            if (inputs.isEmpty()) {
                continue;   // computed by a formula below, or carried forward without new inputs
            }
            for (Edge e : inputs) {
                inputsOut.add(new RecoveryPlan.Input(EntityKey.of(e.source()), e.sourceField(), field, e.relation()));
            }
            boolean aggregate = inputs.stream().anyMatch(e -> e.relation() == Edge.Relation.AGGREGATED_INTO);
            if (aggregate) {
                BigDecimal sum = BigDecimal.ZERO;
                for (Edge e : inputs) {
                    sum = sum.add(Values.decimal(correctedValue(EntityKey.of(e.source()), e.sourceField(), graph, faulty, corrected)));
                }
                proposed.put(field, sum);
            } else {
                Edge e = inputs.get(0);
                proposed.put(field, correctedValue(EntityKey.of(e.source()), e.sourceField(), graph, faulty, corrected));
            }
        }
        return RepairRules.applyFormulas(key.entityType(), proposed);
    }

    /** Edges into the latest version (≤ current) whose incoming edges define {@code field}. */
    private static List<Edge> definingInputs(EntityKey key, String field, long currentVersion, IncidentGraph graph) {
        List<VersionInfo> versions = graph.history(key);
        for (int i = versions.size() - 1; i >= 0; i--) {
            VersionInfo v = versions.get(i);
            if (v.version() > currentVersion) {
                continue;
            }
            List<Edge> in = graph.incoming(key.at(v.version())).stream()
                    .filter(e -> field.equals(e.targetFieldFor(e.sourceField())))
                    .toList();
            if (!in.isEmpty()) {
                return in;
            }
            if (v.changedFields().contains(field)) {
                return List.of();   // set directly at this version, not derived
            }
        }
        return List.of();
    }

    private static List<RecoveryPlan.FieldChange> diff(Map<String, Object> current, Map<String, Object> proposed) {
        List<RecoveryPlan.FieldChange> out = new ArrayList<>();
        for (String field : new TreeSet<>(proposed.keySet())) {
            if (!Values.same(current.get(field), proposed.get(field))) {
                out.add(new RecoveryPlan.FieldChange(field, current.get(field), proposed.get(field)));
            }
        }
        return out;
    }

    private static List<Integer> dependencies(EntityKey key, AffectedRecord rec, IncidentGraph graph,
                                              Map<EntityKey, Integer> stepOf) {
        Set<Integer> deps = new TreeSet<>();
        for (VersionInfo v : graph.history(key)) {
            for (Edge e : graph.incoming(key.at(v.version()))) {
                Integer s = stepOf.get(EntityKey.of(e.source()));
                if (s != null) {
                    deps.add(s);
                }
            }
        }
        return List.copyOf(deps);
    }

    private static RecoveryPlan.Step stepFor(int number, AffectedRecord rec, Map<String, Object> current,
                                             Map<String, Object> proposed, List<RecoveryPlan.FieldChange> changes,
                                             List<Integer> deps, IncidentGraph graph, List<RecoveryPlan.Input> inputs,
                                             BigDecimal alreadyCredited) {
        EntityKey key = rec.key();
        long expected = rec.currentVersion();
        return switch (rec.classification()) {
            case ORIGIN -> changes.isEmpty() ? null : new RecoveryPlan.Step(number, RecoveryPlan.Action.RESTORE_FIELDS, key, expected, changes, deps,
                    RecoveryPlan.Risk.LOW, "Restore the value(s) the faulty change replaced", null, List.of());
            case DIRECTLY_AFFECTED, INDIRECTLY_AFFECTED -> changes.isEmpty() ? null
                    : new RecoveryPlan.Step(number, RecoveryPlan.Action.UPDATE_FIELDS, key, expected, changes, deps,
                    updateRisk(key, current, graph), "Recompute from corrected inputs", null, inputs);
            case NON_REVERSIBLE -> compensation(number, key, expected, current, proposed, changes, deps, graph,
                    alreadyCredited);
            case CONFLICTED, REQUIRES_MANUAL_REVIEW -> new RecoveryPlan.Step(number, RecoveryPlan.Action.MANUAL_REVIEW, key,
                    expected, changes, deps, RecoveryPlan.Risk.HIGH, rec.reason(), null, inputs);
            case NO_LONGER_AFFECTED, NOT_REACHED_PROVENANCE_COMPLETE, NOT_REACHED_PROVENANCE_INCOMPLETE -> null;
        };
    }

    private static RecoveryPlan.Step compensation(int number, EntityKey key, long expected, Map<String, Object> current,
                                                  Map<String, Object> proposed, List<RecoveryPlan.FieldChange> changes,
                                                  List<Integer> deps, IncidentGraph graph, BigDecimal alreadyCredited) {
        RepairRules.Finality finality = RepairRules.finality(key.entityType(), current, graph::current);
        if (finality == RepairRules.Finality.NO_ACTION) {
            return new RecoveryPlan.Step(number, RecoveryPlan.Action.NO_ACTION, key, expected, List.of(), deps,
                    RecoveryPlan.Risk.LOW, "Void: not payable, nothing to correct", null, List.of());
        }
        if (!key.entityType().equals("invoice")) {
            return null;   // lines of an issued invoice are compensated through the invoice
        }
        BigDecimal billed = Values.decimal(current.get("total_amount"));
        BigDecimal correct = Values.decimal(proposed.get("total_amount"));
        int cmp = billed.compareTo(correct);
        if (cmp == 0) {
            return null;
        }
        BigDecimal amount = billed.subtract(correct).abs();
        if (cmp > 0) {
            amount = amount.subtract(alreadyCredited);
            if (amount.signum() <= 0) {
                return null;   // an earlier execution of this incident already credited it
            }
        }
        RecoveryPlan.Action action = cmp > 0 ? RecoveryPlan.Action.ISSUE_CREDIT_NOTE : RecoveryPlan.Action.SUPPLEMENTARY_CHARGE;
        String reason = cmp > 0
                ? "Issued invoice over-billed by " + amount.toPlainString() + ": credit the difference"
                : "Issued invoice under-billed by " + amount.toPlainString() + ": a supplementary charge is owed";
        return new RecoveryPlan.Step(number, action, key, expected, changes, deps, RecoveryPlan.Risk.HIGH, reason, amount,
                List.of());
    }

    private static RecoveryPlan.Risk updateRisk(EntityKey key, Map<String, Object> current, IncidentGraph graph) {
        Object status = current.get("status");
        if (key.entityType().equals("order_line") && current.get("order_id") != null) {
            status = graph.current(new EntityKey("order", UUID.fromString(current.get("order_id").toString()))).get("status");
        }
        if (key.entityType().equals("invoice_line") || key.entityType().equals("invoice")) {
            return RecoveryPlan.Risk.MEDIUM;   // a draft the customer may already have seen
        }
        return "CONFIRMED".equals(status) ? RecoveryPlan.Risk.MEDIUM : RecoveryPlan.Risk.LOW;
    }

    private static List<RecoveryPlan.Invariant> invariantsFor(RecoveryPlan.Step step, Map<String, Object> proposed) {
        List<RecoveryPlan.Invariant> out = new ArrayList<>();
        switch (step.action()) {
            case RESTORE_FIELDS, UPDATE_FIELDS -> {
                for (RecoveryPlan.FieldChange c : step.changes()) {
                    BigDecimal expected = c.proposed() instanceof Number ? Values.decimal(c.proposed()) : null;
                    out.add(new RecoveryPlan.Invariant("FIELD_EQUALS", step.key(), c.field(),
                            step.key() + " " + c.field() + " = " + c.proposed(),
                            expected != null ? expected : c.proposed()));
                }
                if (step.key().entityType().equals("order") || step.key().entityType().equals("invoice")) {
                    out.add(new RecoveryPlan.Invariant("TOTAL_EQUALS_SUM_OF_LINES", step.key(), "total_amount",
                            step.key() + " total_amount equals the sum of its line totals",
                            Values.decimal(proposed.get("total_amount"))));
                }
            }
            case ISSUE_CREDIT_NOTE -> {
                BigDecimal amount = step.amount() == null ? BigDecimal.ZERO : step.amount();
                out.add(new RecoveryPlan.Invariant("CREDITED_AT_LEAST", step.key(), null,
                        step.key() + " has credit notes totalling at least " + amount.toPlainString(), amount));
                if (amount.compareTo(LARGE_COMPENSATION) >= 0) {
                    out.add(new RecoveryPlan.Invariant("LARGE_COMPENSATION_REVIEWED", step.key(), null,
                            "A compensation this large was approved by a recovery approver", amount));
                }
            }
            default -> {
            }
        }
        return out;
    }

    /** Kahn's algorithm with a deterministic tie-break; cycle members (already manual) go last. */
    private static List<EntityKey> topologicalOrder(Set<EntityKey> nodes, IncidentGraph graph) {
        Map<EntityKey, Set<EntityKey>> out = new HashMap<>();
        Map<EntityKey, Integer> indegree = new HashMap<>();
        nodes.forEach(n -> indegree.put(n, 0));
        for (Edge e : graph.edges()) {
            EntityKey s = EntityKey.of(e.source());
            EntityKey t = EntityKey.of(e.target());
            if (!s.equals(t) && nodes.contains(s) && nodes.contains(t)
                    && out.computeIfAbsent(s, k -> new TreeSet<>()).add(t)) {
                indegree.merge(t, 1, Integer::sum);
            }
        }
        PriorityQueue<EntityKey> ready = new PriorityQueue<>(Comparator
                .comparingInt((EntityKey k) -> typeRank(k.entityType())).thenComparing(Comparator.naturalOrder()));
        indegree.forEach((k, d) -> {
            if (d == 0) {
                ready.add(k);
            }
        });
        List<EntityKey> order = new ArrayList<>();
        while (!ready.isEmpty()) {
            EntityKey n = ready.poll();
            order.add(n);
            for (EntityKey m : out.getOrDefault(n, Set.of())) {
                if (indegree.merge(m, -1, Integer::sum) == 0) {
                    ready.add(m);
                }
            }
        }
        // Records left over sit on cycles (already MANUAL_REVIEW); append them in a stable order.
        // A HashSet lookup here, not List.contains: the list version made planning quadratic
        // (about 100 s for 100,000 orders in the Phase 5 experiment).
        Set<EntityKey> placed = new HashSet<>(order);
        for (EntityKey n : new TreeSet<>(nodes)) {
            if (!placed.contains(n)) {
                order.add(n);
            }
        }
        return order;
    }

    private static int typeRank(String type) {
        return switch (type) {
            case "product" -> 0;
            case "order_line" -> 1;
            case "order" -> 2;
            case "invoice_line" -> 3;
            case "invoice" -> 4;
            default -> 5;
        };
    }
}
