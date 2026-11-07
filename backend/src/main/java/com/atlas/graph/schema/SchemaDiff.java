package com.atlas.graph.schema;

import com.atlas.graph.schema.SchemaModel.Column;
import com.atlas.graph.schema.SchemaModel.Constraint;
import com.atlas.graph.schema.SchemaModel.Index;
import com.atlas.graph.schema.SchemaModel.Table;
import com.atlas.graph.schema.SchemaModel.Trigger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Differences between two schema versions: tables, columns, constraints, indexes, triggers and
 * relationship kinds added, removed or changed. Deterministic (sorted by object and name).
 */
public final class SchemaDiff {

    /** @param change ADDED, REMOVED or CHANGED; before/after describe the object (null when absent) */
    public record Change(String objectType, String table, String name, String change, String before, String after) {
    }

    public record Result(List<Change> changes, int added, int removed, int changed) {
        public boolean identical() {
            return changes.isEmpty();
        }
    }

    private SchemaDiff() {
    }

    public static Result compare(SchemaModel from, SchemaModel to) {
        List<Change> out = new ArrayList<>();
        Map<String, Table> a = from.byName();
        Map<String, Table> b = to.byName();
        for (String name : union(a.keySet(), b.keySet())) {
            Table x = a.get(name);
            Table y = b.get(name);
            if (x == null) {
                out.add(new Change("TABLE", name, name, "ADDED", null, y.kind()));
                continue;
            }
            if (y == null) {
                out.add(new Change("TABLE", name, name, "REMOVED", x.kind(), null));
                continue;
            }
            compareAll(out, "COLUMN", name, x.columns(), y.columns(), Column::name,
                    c -> c.type() + (c.nullable() ? " NULL" : " NOT NULL") + (c.defaultValue() == null ? "" : " DEFAULT " + c.defaultValue()));
            compareAll(out, "CONSTRAINT", name, x.constraints(), y.constraints(), Constraint::name,
                    c -> c.definition() + (c.validated() ? "" : " NOT VALID"));
            compareAll(out, "INDEX", name, x.indexes(), y.indexes(), Index::name, Index::definition);
            compareAll(out, "TRIGGER", name, x.triggers(), y.triggers(), Trigger::name,
                    t -> t.timing() + " " + String.join(" OR ", t.events()) + " " + t.function());
        }
        compareAll(out, "RELATIONSHIP", "", from.relationships(), to.relationships(), SchemaModel.Relationship::constraint,
                r -> r.from() + " -> " + r.to() + " " + r.kind() + " (" + r.evidence() + ")");
        int added = (int) out.stream().filter(c -> c.change().equals("ADDED")).count();
        int removed = (int) out.stream().filter(c -> c.change().equals("REMOVED")).count();
        return new Result(out, added, removed, out.size() - added - removed);
    }

    private static <T> void compareAll(List<Change> out, String type, String table, List<T> xs, List<T> ys,
                                       Function<T, String> name, Function<T, String> describe) {
        Map<String, T> a = new TreeMap<>();
        Map<String, T> b = new TreeMap<>();
        xs.forEach(x -> a.put(name.apply(x), x));
        ys.forEach(y -> b.put(name.apply(y), y));
        for (String n : union(a.keySet(), b.keySet())) {
            T x = a.get(n);
            T y = b.get(n);
            if (x == null) {
                out.add(new Change(type, table, n, "ADDED", null, describe.apply(y)));
            } else if (y == null) {
                out.add(new Change(type, table, n, "REMOVED", describe.apply(x), null));
            } else if (!Objects.equals(describe.apply(x), describe.apply(y))) {
                out.add(new Change(type, table, n, "CHANGED", describe.apply(x), describe.apply(y)));
            }
        }
    }

    private static TreeSet<String> union(java.util.Set<String> a, java.util.Set<String> b) {
        TreeSet<String> s = new TreeSet<>(a);
        s.addAll(b);
        return s;
    }
}
