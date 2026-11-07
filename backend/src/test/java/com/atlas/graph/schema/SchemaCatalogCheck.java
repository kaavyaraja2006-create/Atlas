package com.atlas.graph.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Runs the relationship classifier on the REAL ATLAS catalog, as dumped by
 * {@code scripts/verify-graph-sql.py --dump-catalog FILE} (rows of the introspection queries,
 * pipe-separated as psql prints them). Mirrors SchemaIntrospector's row mapping.
 * Prints the relationship kinds and exits non-zero when the known facts do not hold. With a
 * second argument, also writes the model as JSON (used for the screenshot fixture).
 */
public final class SchemaCatalogCheck {

    public static void main(String[] args) throws Exception {
        ObjectMapper m = new ObjectMapper();
        JsonNode d = m.readTree(Files.readString(Path.of(args[0])));
        Map<String, String> kinds = new TreeMap<>();
        rows(d, "tables").forEach(r -> kinds.put(r[0], r[1]));
        Map<String, List<SchemaModel.Column>> cols = new TreeMap<>();
        rows(d, "columns").forEach(r -> cols.computeIfAbsent(r[0], k -> new ArrayList<>()).add(
                new SchemaModel.Column(r[1], Integer.parseInt(r[2]), r[3], r[4].equals("t"), r[5].isEmpty() ? null : r[5])));
        Map<String, List<SchemaModel.Constraint>> cons = new TreeMap<>();
        for (String[] r : rows(d, "constraints")) {
            cons.computeIfAbsent(r[0], k -> new ArrayList<>()).add(new SchemaModel.Constraint(r[1], r[2], list(m, r[3]),
                    r[4], r[5].isEmpty() ? null : r[5], r[6].isEmpty() ? List.of() : list(m, r[6]), r[7].equals("t"),
                    r[8].equals("t")));
        }
        Map<String, List<SchemaModel.Index>> idx = new TreeMap<>();
        for (String[] r : rows(d, "indexes")) {
            idx.computeIfAbsent(r[0], k -> new ArrayList<>()).add(new SchemaModel.Index(r[1], list(m, r[2]),
                    r[3].equals("t"), r[4].equals("t"), r[5].isEmpty() ? null : r[5], r[6]));
        }
        List<SchemaModel.Table> tables = new ArrayList<>();
        kinds.forEach((n, k) -> tables.add(new SchemaModel.Table(n, k, cols.getOrDefault(n, List.of()),
                cons.getOrDefault(n, List.of()), idx.getOrDefault(n, List.of()), List.of())));
        SchemaModel model = SchemaModel.of(tables);

        Map<String, Integer> counts = new TreeMap<>();
        model.relationships().forEach(r -> counts.merge(r.kind() + "/" + r.evidence(), 1, Integer::sum));
        System.out.println("tables: " + model.tables().size() + ", relationships: " + model.relationships().size()
                + " " + counts);
        model.relationships().stream().filter(r -> !r.kind().equals("ONE_TO_MANY"))
                .forEach(r -> System.out.println("  " + r.kind() + " " + r.from() + " -> " + r.to()
                        + (r.via() == null ? "" : " via " + r.via()) + ": " + r.note()));
        boolean ok = has(model, "inventory_items", "products", "ONE_TO_ONE")
                && has(model, "audit_chain_heads", "organizations", "ONE_TO_ONE")
                && has(model, "invoices", "orders", "AT_MOST_ONE_ACTIVE")
                && has(model, "recovery_incidents", "change_events", "AT_MOST_ONE_ACTIVE")
                && has(model, "simulation_runs", "recovery_incidents", "AT_MOST_ONE_ACTIVE")
                && model.relationships().stream().filter(r -> r.kind().equals("MANY_TO_MANY")).count() == 2;
        System.out.println(ok ? "known facts hold" : "KNOWN FACTS DO NOT HOLD");
        if (args.length > 1) {
            // The model as the API serializes it, for UI fixtures built from the REAL migrations.
            Files.writeString(Path.of(args[1]), m.writeValueAsString(model));
            System.out.println("wrote " + args[1]);
        }
        System.exit(ok ? 0 : 1);
    }

    private static boolean has(SchemaModel m, String from, String to, String kind) {
        return m.relationships().stream().anyMatch(r -> r.from().equals(from) && r.to().equals(to) && r.kind().equals(kind));
    }

    private static List<String[]> rows(JsonNode d, String key) {
        List<String[]> out = new ArrayList<>();
        d.get(key).forEach(n -> out.add(n.asText().split("\\|", -1)));
        return out;
    }

    private static List<String> list(ObjectMapper m, String json) throws Exception {
        return m.readValue(json, m.getTypeFactory().constructCollectionType(List.class, String.class));
    }
}
