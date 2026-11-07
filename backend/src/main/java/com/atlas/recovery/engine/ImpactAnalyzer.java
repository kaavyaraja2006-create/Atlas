package com.atlas.recovery.engine;

import com.atlas.provenance.VersionRef;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * PASCAR impact analysis: which records still carry the faulty value, and how.
 *
 * <p>A <em>taint</em> is (record version, field). Starting from the faulty
 * version's changed fields, a breadth-first search spreads taint three ways:
 * <ol>
 *   <li><b>Edges:</b> along a recorded edge whose source field is the tainted field,</li>
 *   <li><b>Formulas:</b> to fields of the same version computed from it (unit_price → line_total),</li>
 *   <li><b>Carry-forward:</b> to the next version of the same record, unless that
 *       version changed the field (the value was overwritten).</li>
 * </ol>
 * Every taint is visited once, so the search terminates on any graph,
 * including cyclic ones. Cost is O(taints + edges examined).
 */
public final class ImpactAnalyzer {

    private record Taint(VersionRef ref, String field) {
    }

    private static final class Visit {
        final int depth;
        final Taint parent;
        boolean recorded;

        Visit(int depth, Taint parent, boolean recorded) {
            this.depth = depth;
            this.parent = parent;
            this.recorded = recorded;
        }
    }

    public ImpactReport analyze(FaultyChange faulty, IncidentGraph graph) {
        Spread spread = spread(faulty, graph);
        Map<Taint, Visit> visits = spread.visits;
        Set<EntityKey> inCycle = cycleMembers(spread.adjacency);
        List<AffectedRecord> records = new ArrayList<>();
        Map<EntityKey, List<Map.Entry<Taint, Visit>>> byEntity = new TreeMap<>();
        visits.entrySet().forEach(e -> byEntity.computeIfAbsent(EntityKey.of(e.getKey().ref()), k -> new ArrayList<>()).add(e));

        for (var entry : byEntity.entrySet()) {
            records.add(classify(entry.getKey(), entry.getValue(), faulty, graph, visits, inCycle));
        }
        records.addAll(untaintedSiblings(records, graph, byEntity.keySet()));
        records.sort(Comparator.comparingInt(AffectedRecord::depth)
                .thenComparing(AffectedRecord::key));
        return new ImpactReport(faulty, records, spread.traversed, Fingerprint.of(graph));
    }

    /**
     * Where the faulty value lived in each field of each record (ATLAS X conflict analysis):
     * the first and last version of that record whose field carried it, and whether every
     * hop on the way was recorded at write time. Same traversal as {@link #analyze}.
     */
    public record TaintSpan(String field, long firstVersion, long lastVersion, boolean recorded) {
    }

    public Map<EntityKey, Map<String, TaintSpan>> taintSpans(FaultyChange faulty, IncidentGraph graph) {
        Map<EntityKey, Map<String, TaintSpan>> out = new TreeMap<>();
        for (var e : spread(faulty, graph).visits.entrySet()) {
            Taint t = e.getKey();
            out.computeIfAbsent(EntityKey.of(t.ref()), k -> new TreeMap<>()).merge(t.field(),
                    new TaintSpan(t.field(), t.ref().version(), t.ref().version(), e.getValue().recorded),
                    (a, b) -> new TaintSpan(a.field(), Math.min(a.firstVersion(), b.firstVersion()),
                            Math.max(a.lastVersion(), b.lastVersion()), a.recorded() || b.recorded()));
        }
        return out;
    }

    private static final class Spread {
        final Map<Taint, Visit> visits = new HashMap<>();
        final Map<EntityKey, Set<EntityKey>> adjacency = new TreeMap<>();
        int traversed;
    }

    private Spread spread(FaultyChange faulty, IncidentGraph graph) {
        if (graph.version(faulty.key(), faulty.ref().version()).isEmpty()) {
            throw new IllegalArgumentException("Faulty version " + faulty.ref() + " is not in the graph");
        }
        Spread spread = new Spread();
        Map<Taint, Visit> visits = spread.visits;
        Deque<Taint> queue = new ArrayDeque<>();
        Map<EntityKey, Set<EntityKey>> adjacency = spread.adjacency;
        int traversed = 0;

        for (String field : faulty.fields()) {
            Taint t = new Taint(faulty.ref(), field);
            visits.put(t, new Visit(0, null, true));
            queue.add(t);
        }

        while (!queue.isEmpty()) {
            Taint t = queue.poll();
            Visit visit = visits.get(t);
            EntityKey key = EntityKey.of(t.ref());

            for (String derived : RepairRules.derivedFields(t.ref().entityType(), t.field())) {
                enqueue(visits, queue, new Taint(t.ref(), derived), visit.depth, t, visit.recorded);
            }
            Optional<VersionInfo> next = graph.next(key, t.ref().version());
            if (next.isPresent() && !next.get().changedFields().contains(t.field())) {
                enqueue(visits, queue, new Taint(key.at(next.get().version()), t.field()), visit.depth, t, visit.recorded);
            }
            for (Edge edge : graph.outgoing(t.ref())) {
                if (!edge.carries(t.field())) {
                    continue;
                }
                traversed++;
                EntityKey target = EntityKey.of(edge.target());
                adjacency.computeIfAbsent(key, k -> new TreeSet<>()).add(target);
                enqueue(visits, queue, new Taint(edge.target(), edge.targetFieldFor(t.field())), visit.depth + 1, t,
                        visit.recorded && edge.recordedAtWrite());
            }
        }
        spread.traversed = traversed;
        return spread;
    }

    private static void enqueue(Map<Taint, Visit> visits, Deque<Taint> queue, Taint t, int depth, Taint parent,
                                boolean recorded) {
        Visit existing = visits.get(t);
        if (existing == null) {
            visits.put(t, new Visit(depth, parent, recorded));
            queue.add(t);
        } else if (recorded && !existing.recorded) {
            // A recorded path beats a backfilled one: upgrade and re-propagate (at most once per taint).
            existing.recorded = true;
            queue.add(t);
        }
    }

    private AffectedRecord classify(EntityKey key, List<Map.Entry<Taint, Visit>> taints, FaultyChange faulty,
                                    IncidentGraph graph, Map<Taint, Visit> visits, Set<EntityKey> inCycle) {
        long current = graph.latest(key).map(VersionInfo::version).orElse(-1L);
        Set<String> everTainted = new TreeSet<>();
        Set<String> currentTainted = new TreeSet<>();
        Map<String, Long> lastTaintedVersion = new HashMap<>();
        boolean anyRecorded = false;
        Map.Entry<Taint, Visit> shallowest = taints.get(0);
        for (var e : taints) {
            Taint t = e.getKey();
            everTainted.add(t.field());
            lastTaintedVersion.merge(t.field(), t.ref().version(), Math::max);
            if (t.ref().version() == current) {
                currentTainted.add(t.field());
            }
            anyRecorded |= e.getValue().recorded;
            if (e.getValue().depth < shallowest.getValue().depth) {
                shallowest = e;
            }
        }
        int depth = shallowest.getValue().depth;
        List<String> path = path(shallowest.getKey(), visits);

        // A tainted field that does not reach the current version was overwritten later.
        List<String> overwrittenByPeople = new ArrayList<>();
        boolean overwrittenByRecovery = false;
        for (String field : everTainted) {
            if (currentTainted.contains(field)) {
                continue;
            }
            Optional<VersionInfo> overwrite = graph.history(key).stream()
                    .filter(v -> v.version() > lastTaintedVersion.get(field) && v.changedFields().contains(field))
                    .findFirst();
            if (overwrite.isPresent() && overwrite.get().byRecovery()) {
                overwrittenByRecovery = true;
            } else if (overwrite.isPresent()) {
                overwrittenByPeople.add(field + " (changed again in v" + overwrite.get().version() + ")");
            }
        }

        if (key.equals(faulty.key())) {
            if (!overwrittenByPeople.isEmpty()) {
                return new AffectedRecord(key, Classification.CONFLICTED, 0, current, currentTainted, path,
                        "The faulty record was edited again after the faulty change: "
                                + String.join(", ", overwrittenByPeople) + ". Restoring would overwrite that edit.");
            }
            return new AffectedRecord(key, Classification.ORIGIN, 0, current, currentTainted, path,
                    "Origin of the incident");
        }
        if (inCycle.contains(key)) {
            return record(key, Classification.REQUIRES_MANUAL_REVIEW, depth, current, currentTainted, path,
                    "Part of a dependency cycle; automatic ordering is impossible");
        }
        if (!overwrittenByPeople.isEmpty()) {
            return record(key, Classification.CONFLICTED, depth, current, currentTainted, path,
                    "Changed by someone after it consumed the faulty value: " + String.join(", ", overwrittenByPeople));
        }
        if (currentTainted.isEmpty()) {
            return record(key, Classification.NO_LONGER_AFFECTED, depth, current, currentTainted, path,
                    overwrittenByRecovery ? "Already repaired by an earlier recovery" : "Faulty value no longer present");
        }
        if (!anyRecorded) {
            return record(key, Classification.REQUIRES_MANUAL_REVIEW, depth, current, currentTainted, path,
                    "Reached only through backfilled (unconfirmed) provenance");
        }
        RepairRules.Finality finality = RepairRules.finality(key.entityType(), graph.current(key), graph::current);
        if (finality != RepairRules.Finality.EDITABLE) {
            return record(key, Classification.NON_REVERSIBLE, depth, current, currentTainted, path,
                    finality == RepairRules.Finality.NO_ACTION ? "Void: no longer payable, no action needed"
                            : "Legally final: must be compensated, not edited");
        }
        if (!RepairRules.updatable(key.entityType())) {
            return record(key, Classification.REQUIRES_MANUAL_REVIEW, depth, current, currentTainted, path,
                    "No repair rule for record type " + key.entityType());
        }
        return record(key, depth == 1 ? Classification.DIRECTLY_AFFECTED : Classification.INDIRECTLY_AFFECTED,
                depth, current, currentTainted, path, "Carries the faulty value in: " + String.join(", ", currentTainted));
    }

    private static AffectedRecord record(EntityKey key, Classification c, int depth, long current, Set<String> fields,
                                         List<String> path, String reason) {
        return new AffectedRecord(key, c, depth, current, fields, path, reason);
    }

    /** Longest path kept per record; deeper ones keep their last hops after a "…" marker. */
    static final int MAX_PATH = 8;

    /**
     * The last {@link #MAX_PATH} versions on the shortest path to {@code end}.
     * Bounded on purpose: storing whole paths is quadratic on long chains
     * (found by the 50,000-record chain test).
     */
    private static List<String> path(Taint end, Map<Taint, Visit> visits) {
        LinkedList<String> out = new LinkedList<>();
        Taint t = end;
        while (t != null) {
            String step = t.ref().toString();
            if (out.isEmpty() || !out.getFirst().equals(step)) {
                if (out.size() == MAX_PATH) {
                    out.addFirst("…");
                    break;
                }
                out.addFirst(step);
            }
            t = visits.get(t).parent;
        }
        return out;
    }

    /** Records summed into an affected record that are not themselves affected (e.g. a correctly priced line). */
    private static List<AffectedRecord> untaintedSiblings(List<AffectedRecord> affected, IncidentGraph graph,
                                                          Set<EntityKey> tainted) {
        Set<EntityKey> seen = new TreeSet<>();
        List<AffectedRecord> out = new ArrayList<>();
        for (AffectedRecord r : affected) {
            if (r.classification().context()) {
                continue;
            }
            for (VersionInfo v : graph.history(r.key())) {
                for (Edge e : graph.incoming(r.key().at(v.version()))) {
                    EntityKey source = EntityKey.of(e.source());
                    if (e.relation() == Edge.Relation.AGGREGATED_INTO && !tainted.contains(source) && seen.add(source)) {
                        long cur = graph.latest(source).map(VersionInfo::version).orElse(e.source().version());
                        List<ProvenanceRequirements.Gap> gaps = ProvenanceRequirements.gaps(source, graph);
                        out.add(gaps.isEmpty()
                                ? new AffectedRecord(source, Classification.NOT_REACHED_PROVENANCE_COMPLETE, r.depth(), cur,
                                        Set.of(), List.of(), "Summed into " + r.key() + "; not reached from the faulty change, "
                                        + "and every required input of this record within the analysed scope was recorded")
                                : new AffectedRecord(source, Classification.NOT_REACHED_PROVENANCE_INCOMPLETE, r.depth(), cur,
                                        Set.of(), List.of(), "Summed into " + r.key() + "; not reached, but required provenance "
                                        + "is missing (" + gaps.stream().map(Object::toString).collect(java.util.stream.Collectors.joining(", "))
                                        + "), so impact cannot be ruled out"));
                    }
                }
            }
        }
        return out;
    }

    /** Records on a directed cycle of the entity-level graph (Tarjan's strongly connected components). */
    static Set<EntityKey> cycleMembers(Map<EntityKey, Set<EntityKey>> adjacency) {
        Map<EntityKey, Integer> index = new HashMap<>();
        Map<EntityKey, Integer> low = new HashMap<>();
        Deque<EntityKey> stack = new ArrayDeque<>();
        Set<EntityKey> onStack = new HashSet<>();
        Set<EntityKey> result = new TreeSet<>();
        int[] counter = {0};
        Set<EntityKey> nodes = new TreeSet<>(adjacency.keySet());
        adjacency.values().forEach(nodes::addAll);
        for (EntityKey n : nodes) {
            if (!index.containsKey(n)) {
                strongConnect(n, adjacency, index, low, stack, onStack, result, counter);
            }
        }
        return result;
    }

    private static void strongConnect(EntityKey root, Map<EntityKey, Set<EntityKey>> adj, Map<EntityKey, Integer> index,
                                      Map<EntityKey, Integer> low, Deque<EntityKey> stack, Set<EntityKey> onStack,
                                      Set<EntityKey> result, int[] counter) {
        // Iterative Tarjan, so deep chains cannot overflow the call stack.
        Deque<Object[]> work = new ArrayDeque<>();
        work.push(new Object[] {root, adj.getOrDefault(root, Set.of()).iterator()});
        index.put(root, counter[0]);
        low.put(root, counter[0]++);
        stack.push(root);
        onStack.add(root);
        while (!work.isEmpty()) {
            Object[] frame = work.peek();
            EntityKey v = (EntityKey) frame[0];
            @SuppressWarnings("unchecked")
            java.util.Iterator<EntityKey> it = (java.util.Iterator<EntityKey>) frame[1];
            if (it.hasNext()) {
                EntityKey w = it.next();
                if (!index.containsKey(w)) {
                    index.put(w, counter[0]);
                    low.put(w, counter[0]++);
                    stack.push(w);
                    onStack.add(w);
                    work.push(new Object[] {w, adj.getOrDefault(w, Set.of()).iterator()});
                } else if (onStack.contains(w)) {
                    low.put(v, Math.min(low.get(v), index.get(w)));
                }
                continue;
            }
            work.pop();
            if (!work.isEmpty()) {
                EntityKey parent = (EntityKey) work.peek()[0];
                low.put(parent, Math.min(low.get(parent), low.get(v)));
            }
            if (low.get(v).equals(index.get(v))) {
                List<EntityKey> component = new ArrayList<>();
                EntityKey w;
                do {
                    w = stack.pop();
                    onStack.remove(w);
                    component.add(w);
                } while (!w.equals(v));
                boolean selfLoop = adj.getOrDefault(v, Set.of()).contains(v);
                if (component.size() > 1 || selfLoop) {
                    result.addAll(component);
                }
            }
        }
    }
}
