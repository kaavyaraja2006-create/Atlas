package com.atlas.recovery.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Field-level, temporal conflict analysis (ATLAS X, Milestone 3). For every field that
 * carried the faulty value, reads what happened to the record AFTER: who changed what, in
 * which version, and whether recorded provenance explains it. The result is evidence, not
 * a verdict on intent: a person's later edit may be a fix or a mistake, so it goes to
 * human review instead of being overwritten or silently kept.
 *
 * <p>Framework-free and deterministic: the same report and graph give the same list.
 */
public final class ConflictAnalyzer {

    private final ImpactAnalyzer analyzer = new ImpactAnalyzer();

    /** Conflicts for every field that ever carried the faulty value, sorted by record then field. */
    public List<FieldConflict> analyze(FaultyChange faulty, ImpactReport report, IncidentGraph graph) {
        Map<EntityKey, Map<String, ImpactAnalyzer.TaintSpan>> spans = analyzer.taintSpans(faulty, graph);
        List<FieldConflict> out = new ArrayList<>();
        for (var entry : spans.entrySet()) {
            EntityKey key = entry.getKey();
            Optional<AffectedRecord> affected = report.find(key);
            for (ImpactAnalyzer.TaintSpan span : entry.getValue().values()) {
                out.add(classify(key, span, affected, graph));
            }
        }
        out.sort(Comparator.comparing(FieldConflict::key).thenComparing(FieldConflict::field));
        return out;
    }

    /**
     * Steps whose record has moved on since the plan was made (observed now vs the plan's
     * expected version). Each becomes CONCURRENT_MODIFICATION: the plan must be re-made.
     */
    public List<FieldConflict> concurrent(RecoveryPlan plan, Map<EntityKey, Long> observedVersions) {
        List<FieldConflict> out = new ArrayList<>();
        for (RecoveryPlan.Step step : plan.steps()) {
            Long now = observedVersions.get(step.key());
            if (now == null || now == step.expectedVersion()) {
                continue;
            }
            for (RecoveryPlan.FieldChange change : step.changes()) {
                out.add(new FieldConflict(step.key(), change.field(), ConflictKind.CONCURRENT_MODIFICATION,
                        step.expectedVersion(), step.expectedVersion(), now, null, Set.of(),
                        FieldConflict.Recommendation.HUMAN_REVIEW, FieldConflict.Severity.HIGH,
                        "Planned against v" + step.expectedVersion() + ", the record is now at v" + now
                                + ": the planned write would act on data the plan never saw. Re-plan."));
            }
        }
        return out;
    }

    private static FieldConflict classify(EntityKey key, ImpactAnalyzer.TaintSpan span, Optional<AffectedRecord> affected,
                                          IncidentGraph graph) {
        String f = span.field();
        long current = graph.latest(key).map(VersionInfo::version).orElse(span.lastVersion());

        if (affected.isPresent() && affected.get().classification() == Classification.REQUIRES_MANUAL_REVIEW) {
            return conflict(key, span, ConflictKind.REQUIRES_MANUAL_REVIEW, null, null, Set.of(),
                    FieldConflict.Recommendation.HUMAN_REVIEW, FieldConflict.Severity.HIGH,
                    affected.get().reason() + ".");
        }

        Optional<VersionInfo> overwrite = graph.history(key).stream()
                .filter(v -> v.version() > span.lastVersion() && v.changedFields().contains(f))
                .findFirst();
        if (overwrite.isPresent()) {
            VersionInfo v = overwrite.get();
            if (v.byRecovery()) {
                return conflict(key, span, ConflictKind.NO_CONFLICT, v.version(), v.actorKind(), v.changedFields(),
                        FieldConflict.Recommendation.NONE, FieldConflict.Severity.LOW,
                        f + " was already corrected by recovery in v" + v.version() + ".");
            }
            List<Edge> rederived = graph.incoming(key.at(v.version())).stream()
                    .filter(e -> e.targetField().equals(f)).toList();
            if (!rederived.isEmpty()) {
                Edge e = rederived.get(0);
                return conflict(key, span, ConflictKind.SOURCE_VERSION_MISMATCH, v.version(), v.actorKind(),
                        v.changedFields(), FieldConflict.Recommendation.PRESERVE_LATER_CHANGE, FieldConflict.Severity.MEDIUM,
                        f + " was re-derived in v" + v.version() + " from " + e.source() + "." + e.sourceField()
                                + " (recorded provenance), not from the faulty value: keep it, and check that source.");
            }
            if ("USER".equals(v.actorKind())) {
                return conflict(key, span, ConflictKind.SAME_FIELD_CONFLICT, v.version(), v.actorKind(),
                        v.changedFields(), FieldConflict.Recommendation.HUMAN_REVIEW, FieldConflict.Severity.HIGH,
                        "A person changed " + f + " in v" + v.version() + " after it carried the faulty value"
                                + " (v" + span.firstVersion() + "–v" + span.lastVersion() + "). It may be a fix or a"
                                + " legitimate new value; recovery must not overwrite it without a decision.");
            }
            return conflict(key, span, ConflictKind.UNKNOWN_CHANGE_ORIGIN, v.version(), v.actorKind(), v.changedFields(),
                    FieldConflict.Recommendation.HUMAN_REVIEW, FieldConflict.Severity.HIGH,
                    f + " was changed in v" + v.version() + " by " + v.actorKind()
                            + " with no recorded provenance: where the new value came from is unknown.");
        }

        if (!span.recorded()) {
            return conflict(key, span, ConflictKind.REQUIRES_MANUAL_REVIEW, null, null, Set.of(),
                    FieldConflict.Recommendation.HUMAN_REVIEW, FieldConflict.Severity.MEDIUM,
                    "The faulty value reached " + f + " only through backfilled (inferred) provenance.");
        }

        Set<String> otherFields = new TreeSet<>();
        Long lastOther = null;
        String lastActor = null;
        for (VersionInfo v : graph.history(key)) {
            // A version that rewrote f itself is propagation (e.g. adding a line recomputes the
            // total and line_count together), not an unrelated later edit.
            if (v.version() > span.firstVersion() && v.version() <= current && !v.byRecovery()
                    && !v.changedFields().contains(f)) {
                otherFields.addAll(v.changedFields());
                lastOther = v.version();
                lastActor = v.actorKind();
            }
        }
        otherFields.remove(f);
        if (!otherFields.isEmpty()) {
            return conflict(key, span, ConflictKind.DIFFERENT_FIELD_UPDATE, lastOther, lastActor, otherFields,
                    FieldConflict.Recommendation.CORRECT_AUTOMATICALLY, FieldConflict.Severity.LOW,
                    "Later changes touched " + String.join(", ", otherFields) + " but not " + f
                            + ": a field-level correction of " + f + " keeps them (whole-row restore would not).");
        }
        return conflict(key, span, ConflictKind.NO_CONFLICT, null, null, Set.of(),
                FieldConflict.Recommendation.CORRECT_AUTOMATICALLY, FieldConflict.Severity.LOW,
                f + " still holds the faulty value and nothing else changed since.");
    }

    private static FieldConflict conflict(EntityKey key, ImpactAnalyzer.TaintSpan span, ConflictKind kind, Long later,
                                          String actor, Set<String> fields, FieldConflict.Recommendation rec,
                                          FieldConflict.Severity severity, String explanation) {
        return new FieldConflict(key, span.field(), kind, span.firstVersion(), span.lastVersion(), later, actor, fields,
                rec, severity, explanation);
    }
}
