package com.atlas.graph.schema;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * The application schema as read from PostgreSQL's catalog (Graph Intelligence, ER explorer).
 * Framework-free: built by {@code SchemaIntrospector} from the live database, or from a stored
 * snapshot. Every list is sorted, so two reads of the same structure are equal and serialize
 * identically (the snapshot hash depends on it).
 */
public record SchemaModel(List<Table> tables, List<Relationship> relationships) {

    public SchemaModel {
        tables = tables.stream().sorted(Comparator.comparing(Table::name)).toList();
        relationships = relationships.stream().sorted(Comparator.comparing(Relationship::constraint)
                .thenComparing(Relationship::from).thenComparing(Relationship::to)).toList();
    }

    public record Column(String name, int position, String type, boolean nullable, String defaultValue) {
    }

    /**
     * @param kind       PRIMARY_KEY, FOREIGN_KEY, UNIQUE, CHECK or EXCLUSION
     * @param references for a foreign key: the referenced table and columns, else null / empty
     * @param validated  false for a constraint added NOT VALID (existing rows were never checked)
     */
    public record Constraint(String name, String kind, List<String> columns, String definition, String references,
                             List<String> referencedColumns, boolean deferrable, boolean validated) {
        public Constraint {
            columns = List.copyOf(columns);
            referencedColumns = referencedColumns == null ? List.of() : List.copyOf(referencedColumns);
        }
    }

    /** @param predicate the WHERE clause of a partial index, else null */
    public record Index(String name, List<String> columns, boolean unique, boolean primary, String predicate,
                        String definition) {
        public Index {
            columns = List.copyOf(columns);
        }
    }

    /** A trigger's name, timing and events only: function bodies are never exposed. */
    public record Trigger(String name, String timing, List<String> events, String function) {
        public Trigger {
            events = List.copyOf(events);
        }
    }

    /** @param kind TABLE or VIEW */
    public record Table(String name, String kind, List<Column> columns, List<Constraint> constraints, List<Index> indexes,
                        List<Trigger> triggers) {
        public Table {
            columns = columns.stream().sorted(Comparator.comparingInt(Column::position)).toList();
            constraints = constraints.stream().sorted(Comparator.comparing(Constraint::name)).toList();
            indexes = indexes.stream().sorted(Comparator.comparing(Index::name)).toList();
            triggers = triggers.stream().sorted(Comparator.comparing(Trigger::name)).toList();
        }

        public Optional<Constraint> primaryKey() {
            return constraints.stream().filter(c -> c.kind().equals("PRIMARY_KEY")).findFirst();
        }

        public List<Constraint> foreignKeys() {
            return constraints.stream().filter(c -> c.kind().equals("FOREIGN_KEY")).toList();
        }
    }

    /**
     * A relationship between two tables and the evidence for its kind.
     *
     * @param kind     ONE_TO_ONE, ONE_TO_MANY, AT_MOST_ONE_ACTIVE (a partial unique index limits
     *                 the "many" side to one row matching its predicate) or MANY_TO_MANY
     * @param evidence DECLARED (a foreign key, kind derived from declared keys and indexes) or
     *                 INFERRED (many-to-many through a junction table: no catalog object declares it)
     * @param via      the junction table of a MANY_TO_MANY, else null
     * @param note     why the kind was chosen, in words
     */
    public record Relationship(String constraint, String from, List<String> fromColumns, String to,
                               List<String> toColumns, String kind, String evidence, String via, String note) {
        public Relationship {
            fromColumns = List.copyOf(fromColumns);
            toColumns = List.copyOf(toColumns);
        }
    }

    public Map<String, Table> byName() {
        Map<String, Table> out = new TreeMap<>();
        tables.forEach(t -> out.put(t.name(), t));
        return out;
    }

    /** The same tables with relationships computed by {@link RelationshipClassifier}. */
    public static SchemaModel of(List<Table> tables) {
        return new SchemaModel(new ArrayList<>(tables), RelationshipClassifier.classify(tables));
    }
}
