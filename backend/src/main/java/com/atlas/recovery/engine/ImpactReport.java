package com.atlas.recovery.engine;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Result of impact analysis. {@code fingerprint} identifies exactly the state that was analysed. */
public record ImpactReport(FaultyChange faulty, List<AffectedRecord> records, int traversedEdges, String fingerprint) {

    public ImpactReport {
        records = List.copyOf(records);
    }

    public Map<Classification, Long> counts() {
        Map<Classification, Long> counts = new EnumMap<>(Classification.class);
        for (Classification c : Classification.values()) {
            counts.put(c, 0L);
        }
        records.forEach(r -> counts.merge(r.classification(), 1L, Long::sum));
        return counts;
    }

    /** Records that need some action or decision (everything except no-longer-affected and not-reached context). */
    public List<AffectedRecord> impacted() {
        return records.stream().filter(r -> !r.classification().context()).toList();
    }

    public Optional<AffectedRecord> find(EntityKey key) {
        return records.stream().filter(r -> r.key().equals(key)).findFirst();
    }
}
