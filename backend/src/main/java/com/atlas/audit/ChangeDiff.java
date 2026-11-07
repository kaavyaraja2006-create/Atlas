package com.atlas.audit;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/** Computes which fields differ between two snapshots of the same record. */
public final class ChangeDiff {

    private ChangeDiff() {
    }

    /** Sorted names of fields whose values differ; keys present on only one side count as changed. */
    public static List<String> changedFields(Map<String, ?> before, Map<String, ?> after) {
        TreeSet<String> keys = new TreeSet<>(before.keySet());
        keys.addAll(after.keySet());
        List<String> changed = new ArrayList<>();
        for (String key : keys) {
            if (!before.containsKey(key) || !after.containsKey(key)
                    || !sameValue(before.get(key), after.get(key))) {
                changed.add(key);
            }
        }
        return changed;
    }

    /** Numbers compare by value, so 500 and 500.0000 are the same price. */
    static boolean sameValue(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return new BigDecimal(x.toString()).compareTo(new BigDecimal(y.toString())) == 0;
        }
        return Objects.equals(a, b);
    }
}
