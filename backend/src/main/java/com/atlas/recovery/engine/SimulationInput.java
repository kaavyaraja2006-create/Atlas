package com.atlas.recovery.engine;

import com.atlas.provenance.VersionRef;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Everything a simulation reads, captured once from a consistent snapshot. Nothing in it
 * can write to the database: the simulator works on copies of these maps.
 *
 * @param graph             provenance graph plus the histories of every record linked to the origin by
 *                          foreign keys (so foreign-key strategies can be modelled)
 * @param sequence          audit-chain position of every recorded version (orders writes across records)
 * @param credited          compensation already issued for this incident, per final record
 * @param reviewedConflicts "type:id.field" of conflicts that already have a human decision
 */
public record SimulationInput(FaultyChange faulty, IncidentGraph graph, Map<VersionRef, Long> sequence,
                              Map<EntityKey, BigDecimal> credited, Set<String> reviewedConflicts) {

    public SimulationInput {
        Objects.requireNonNull(faulty, "faulty");
        Objects.requireNonNull(graph, "graph");
        sequence = Map.copyOf(sequence);
        credited = credited == null ? Map.of() : Map.copyOf(credited);
        reviewedConflicts = reviewedConflicts == null ? Set.of() : Set.copyOf(reviewedConflicts);
    }
}
