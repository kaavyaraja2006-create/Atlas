package com.atlas.recovery.engine;

import com.atlas.provenance.VersionRef;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The slice of history an incident needs: provenance edges plus the version
 * history of every record they touch. Built once per analysis by the loader
 * (from SQL) or by the experiment harness (from synthetic data).
 */
public final class IncidentGraph {

    private final List<Edge> edges;
    private final Map<VersionRef, List<Edge>> outgoing = new HashMap<>();
    private final Map<VersionRef, List<Edge>> incoming = new HashMap<>();
    private final Map<EntityKey, List<VersionInfo>> history = new HashMap<>();
    /** Required-but-missing provenance as the loader found it in the database, if it looked. */
    private Optional<Set<ProvenanceRequirements.Gap>> gaps = Optional.empty();

    public IncidentGraph(Collection<Edge> edges, Map<EntityKey, ? extends Collection<VersionInfo>> histories) {
        this.edges = List.copyOf(edges);
        for (Edge e : this.edges) {
            outgoing.computeIfAbsent(e.source(), k -> new ArrayList<>()).add(e);
            incoming.computeIfAbsent(e.target(), k -> new ArrayList<>()).add(e);
        }
        histories.forEach((key, versions) -> {
            List<VersionInfo> sorted = new ArrayList<>(versions);
            sorted.sort(Comparator.comparingLong(VersionInfo::version));
            for (int i = 1; i < sorted.size(); i++) {
                if (sorted.get(i).version() == sorted.get(i - 1).version()) {
                    throw new IllegalArgumentException("Duplicate version " + sorted.get(i).version() + " for " + key);
                }
            }
            history.put(key, List.copyOf(sorted));
        });
    }

    /**
     * The same graph, with the provenance gaps its loader found for the records in it. A loader
     * that does not load every incoming edge of every record (the database loader skips the
     * inputs of records summed into an affected total) must supply them; see
     * {@link ProvenanceRequirements#gaps}.
     */
    public IncidentGraph withGaps(Collection<ProvenanceRequirements.Gap> found) {
        IncidentGraph g = new IncidentGraph(edges, history);
        g.gaps = Optional.of(Set.copyOf(found));
        return g;
    }

    public Optional<Set<ProvenanceRequirements.Gap>> suppliedGaps() {
        return gaps;
    }

    public List<Edge> edges() {
        return edges;
    }

    public List<Edge> outgoing(VersionRef ref) {
        return outgoing.getOrDefault(ref, List.of());
    }

    public List<Edge> incoming(VersionRef ref) {
        return incoming.getOrDefault(ref, List.of());
    }

    public Set<EntityKey> entities() {
        return Collections.unmodifiableSet(history.keySet());
    }

    public List<VersionInfo> history(EntityKey key) {
        return history.getOrDefault(key, List.of());
    }

    public Optional<VersionInfo> version(EntityKey key, long version) {
        return history(key).stream().filter(v -> v.version() == version).findFirst();
    }

    /** The next recorded version after {@code version}, if any. */
    public Optional<VersionInfo> next(EntityKey key, long version) {
        return history(key).stream().filter(v -> v.version() > version).findFirst();
    }

    public Optional<VersionInfo> latest(EntityKey key) {
        List<VersionInfo> versions = history(key);
        return versions.isEmpty() ? Optional.empty() : Optional.of(versions.get(versions.size() - 1));
    }

    /** Current state of a record (after its latest version), or empty if unknown. */
    public Map<String, Object> current(EntityKey key) {
        return latest(key).map(VersionInfo::after).orElse(Map.of());
    }
}
