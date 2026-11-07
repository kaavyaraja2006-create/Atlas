package com.atlas.graph.schema;

import com.atlas.graph.schema.SchemaModel.Constraint;
import com.atlas.graph.schema.SchemaModel.Index;
import com.atlas.graph.schema.SchemaModel.Relationship;
import com.atlas.graph.schema.SchemaModel.Table;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Derives relationship kinds from declared keys and indexes only.
 *
 * <ul>
 *   <li>Every foreign key is one relationship, DECLARED. It is ONE_TO_ONE when its columns are
 *       exactly covered by the primary key or by a unique constraint/index WITHOUT a predicate;
 *       AT_MOST_ONE_ACTIVE when only a PARTIAL unique index covers them (e.g. one non-void
 *       invoice per order); ONE_TO_MANY otherwise.</li>
 *   <li>A table whose primary key is made entirely of the columns of two or more foreign keys,
 *       and that has no other non-key columns than audit timestamps, is treated as a junction:
 *       each pair of its targets gets one MANY_TO_MANY relationship, marked INFERRED, because
 *       nothing in the catalog declares a junction.</li>
 * </ul>
 * Columns that every tenant table shares ({@code organization_id}) count like any other column:
 * a composite key {@code (organization_id, order_id)} is covered only by an index on both.
 */
public final class RelationshipClassifier {

    /** Non-key columns a junction table may still have. */
    private static final Set<String> JUNCTION_EXTRAS = Set.of("created_at", "granted_at", "granted_by", "assigned_at");

    private RelationshipClassifier() {
    }

    public static List<Relationship> classify(List<Table> tables) {
        List<Relationship> out = new ArrayList<>();
        for (Table t : tables) {
            for (Constraint fk : t.foreignKeys()) {
                out.add(declared(t, fk));
            }
            junction(t).ifPresent(out::addAll);
        }
        return out;
    }

    private static Relationship declared(Table t, Constraint fk) {
        Set<String> cols = Set.copyOf(fk.columns());
        boolean pk = t.primaryKey().map(p -> Set.copyOf(p.columns()).equals(cols)).orElse(false);
        boolean unique = t.constraints().stream().anyMatch(c -> c.kind().equals("UNIQUE") && Set.copyOf(c.columns()).equals(cols))
                || t.indexes().stream().anyMatch(i -> i.unique() && i.predicate() == null && Set.copyOf(i.columns()).equals(cols));
        Index partial = t.indexes().stream()
                .filter(i -> i.unique() && i.predicate() != null && Set.copyOf(i.columns()).equals(cols))
                .findFirst().orElse(null);
        String kind;
        String note;
        if (pk || unique) {
            kind = "ONE_TO_ONE";
            note = pk ? "The foreign key is the primary key of " + t.name()
                    : "A unique constraint covers exactly the foreign-key columns";
        } else if (partial != null) {
            kind = "AT_MOST_ONE_ACTIVE";
            note = "One-to-many; partial unique index " + partial.name() + " allows at most one row where "
                    + partial.predicate();
        } else {
            kind = "ONE_TO_MANY";
            note = "No unique constraint covers the foreign-key columns";
        }
        if (!fk.validated()) {
            note += "; constraint is NOT VALID (existing rows were never checked)";
        }
        return new Relationship(fk.name(), t.name(), fk.columns(), fk.references(), fk.referencedColumns(), kind,
                "DECLARED", null, note);
    }

    private static java.util.Optional<List<Relationship>> junction(Table t) {
        List<Constraint> fks = t.foreignKeys();
        if (fks.size() < 2 || t.primaryKey().isEmpty() || !t.kind().equals("TABLE")) {
            return java.util.Optional.empty();
        }
        Set<String> pk = Set.copyOf(t.primaryKey().get().columns());
        Map<String, Constraint> covering = new TreeMap<>();
        Set<String> fkColumns = new HashSet<>();
        for (Constraint fk : fks) {
            if (pk.containsAll(fk.columns())) {
                covering.put(fk.name(), fk);
                fkColumns.addAll(fk.columns());
            }
        }
        if (covering.size() < 2 || !fkColumns.equals(pk)) {
            return java.util.Optional.empty();
        }
        boolean onlyKeys = t.columns().stream().allMatch(c -> pk.contains(c.name()) || JUNCTION_EXTRAS.contains(c.name()));
        if (!onlyKeys) {
            return java.util.Optional.empty();
        }
        List<Constraint> ends = new ArrayList<>(covering.values());
        List<Relationship> out = new ArrayList<>();
        for (int i = 0; i < ends.size(); i++) {
            for (int j = i + 1; j < ends.size(); j++) {
                Constraint a = ends.get(i);
                Constraint b = ends.get(j);
                out.add(new Relationship(t.name() + ":" + a.name() + "+" + b.name(), a.references(), a.referencedColumns(),
                        b.references(), b.referencedColumns(), "MANY_TO_MANY", "INFERRED", t.name(),
                        "Inferred: " + t.name() + " has only the two foreign keys as its primary key; "
                                + "no catalog object declares a junction"));
            }
        }
        return java.util.Optional.of(out);
    }
}
