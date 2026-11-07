package com.atlas.recovery.engine;

import java.util.List;
import java.util.Set;

/**
 * One record in an impact report.
 *
 * @param depth         edges between the faulty change and this record (0 for the origin)
 * @param taintedFields fields of the CURRENT version still carrying the faulty value
 * @param path          last hops of the shortest chain from the faulty change, as "type:id@v";
 *                      starts with "…" when earlier hops were omitted (depth gives the full length)
 */
public record AffectedRecord(EntityKey key, Classification classification, int depth, long currentVersion,
                             Set<String> taintedFields, List<String> path, String reason) {

    public AffectedRecord {
        taintedFields = Set.copyOf(taintedFields);
        path = List.copyOf(path);
    }
}
