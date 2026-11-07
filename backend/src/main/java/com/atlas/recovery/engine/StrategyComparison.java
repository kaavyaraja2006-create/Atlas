package com.atlas.recovery.engine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * How two strategies differ in what they would do (Graph Intelligence, step 2). Computed from
 * the COMPLETE record sets while the simulation is still in memory, so the counts are exact
 * even when the stored list of differences is cut at the detail limit.
 *
 * <p>{@code sha256} covers every difference (not only the stored ones) and the counts, so a
 * stored comparison can be checked against a re-run of the same snapshot.
 *
 * @param onlyA            records only strategy A acts on
 * @param onlyB            records only strategy B acts on
 * @param both             records both act on
 * @param sameAction       of those, records where both take the same action on the same fields
 * @param totalDifferences onlyA + onlyB + (both - sameAction)
 */
public record StrategyComparison(RecoveryStrategy a, RecoveryStrategy b, int onlyA, int onlyB, int both, int sameAction,
                                 int totalDifferences, List<Difference> stored, boolean truncated, String sha256) {

    public StrategyComparison {
        stored = List.copyOf(stored);
    }

    public int omitted() {
        return totalDifferences - stored.size();
    }

    /** @param actionA NONE when only B acts on the record (and the reverse) */
    public record Difference(EntityKey key, String actionA, List<String> fieldsA, String actionB, List<String> fieldsB) {
        public Difference {
            fieldsA = List.copyOf(fieldsA);
            fieldsB = List.copyOf(fieldsB);
        }
    }

    static StrategyComparison of(StrategyRecords x, Map<EntityKey, StrategyRecords.RecordDetail> actsA, StrategyRecords y,
                                 Map<EntityKey, StrategyRecords.RecordDetail> actsB, int limit) {
        TreeSet<EntityKey> keys = new TreeSet<>(actsA.keySet());
        keys.addAll(actsB.keySet());
        int onlyA = 0;
        int onlyB = 0;
        int both = 0;
        int same = 0;
        List<Difference> all = new ArrayList<>();
        for (EntityKey k : keys) {
            StrategyRecords.RecordDetail da = actsA.get(k);
            StrategyRecords.RecordDetail db = actsB.get(k);
            if (da != null && db != null) {
                both++;
                if (da.action().equals(db.action()) && Objects.equals(da.fields(), db.fields())) {
                    same++;
                    continue;
                }
            } else if (da != null) {
                onlyA++;
            } else {
                onlyB++;
            }
            all.add(new Difference(k, da == null ? "NONE" : da.action(), da == null ? List.of() : da.fields(),
                    db == null ? "NONE" : db.action(), db == null ? List.of() : db.fields()));
        }
        StringBuilder canonical = new StringBuilder()
                .append(x.strategy()).append('|').append(y.strategy()).append('|')
                .append(onlyA).append('|').append(onlyB).append('|').append(both).append('|').append(same).append('\n');
        for (Difference d : all) {
            canonical.append(d.key()).append('|').append(d.actionA()).append('|').append(String.join(",", d.fieldsA()))
                    .append('|').append(d.actionB()).append('|').append(String.join(",", d.fieldsB())).append('\n');
        }
        List<Difference> kept = all.size() > limit ? all.subList(0, limit) : all;
        return new StrategyComparison(x.strategy(), y.strategy(), onlyA, onlyB, both, same, all.size(), kept,
                all.size() > limit, sha256(canonical.toString()));
    }

    /** Records a strategy acts on (any action but NONE), from its COMPLETE detail list. */
    static Map<EntityKey, StrategyRecords.RecordDetail> acting(List<StrategyRecords.RecordDetail> complete) {
        Map<EntityKey, StrategyRecords.RecordDetail> out = new TreeMap<>();
        complete.stream().filter(d -> !d.action().equals("NONE")).forEach(d -> out.put(d.key(), d));
        return out;
    }

    static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
