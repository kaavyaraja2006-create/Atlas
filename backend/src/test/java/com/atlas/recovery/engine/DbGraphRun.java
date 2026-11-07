package com.atlas.recovery.engine;

import com.atlas.provenance.VersionRef;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.io.BufferedReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Runs PASCAR on a graph exactly as PostgreSQL returned it to the real loader
 * SQL ({@code LOAD_INCIDENT_EDGES}, {@code LOAD_INCIDENT_VERSIONS},
 * {@code FIND_CHANGE_EVENT}, {@code CREDITED_BY_INCIDENT}, each dumped with
 * COPY ... TO CSV by the benchmark driver), and scores it against the
 * oracle's ground truth when one is given.
 *
 * <p>Usage: {@code DbGraphRun <dir>}; reads edges.csv, versions.csv,
 * fault.csv, credited.csv (optional), truth.csv and truth_totals.csv
 * (optional); writes plan.json and result.json.
 */
public final class DbGraphRun {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ObjectReader EXACT = MAPPER.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    private DbGraphRun() {
    }

    public static void main(String[] args) throws IOException {
        Path dir = Path.of(args[0]);
        long t0 = System.nanoTime();
        List<Edge> edges = new ArrayList<>();
        for (List<String> r : csv(dir.resolve("edges.csv"))) {
            edges.add(new Edge(new VersionRef(r.get(0), UUID.fromString(r.get(1)), Long.parseLong(r.get(2))), r.get(3),
                    new VersionRef(r.get(4), UUID.fromString(r.get(5)), Long.parseLong(r.get(6))), r.get(7),
                    Edge.Relation.valueOf(r.get(8)), "RECORDED_AT_WRITE".equals(r.get(9))));
        }
        Map<EntityKey, List<VersionInfo>> histories = new LinkedHashMap<>();
        for (List<String> r : csv(dir.resolve("versions.csv"))) {
            EntityKey k = new EntityKey(r.get(0), UUID.fromString(r.get(1)));
            histories.computeIfAbsent(k, x -> new ArrayList<>()).add(new VersionInfo(Long.parseLong(r.get(2)), r.get(3),
                    r.get(4), new HashSet<>(strings(r.get(5))), object(r.get(6))));
        }
        List<String> f = csv(dir.resolve("fault.csv")).get(0);
        VersionRef ref = new VersionRef(f.get(0), UUID.fromString(f.get(1)), Long.parseLong(f.get(2)));
        FaultyChange fault = new FaultyChange(ref, new HashSet<>(strings(f.get(3))), object(f.get(4)), object(f.get(5)));
        Map<EntityKey, BigDecimal> credited = new HashMap<>();
        if (Files.exists(dir.resolve("credited.csv"))) {
            for (List<String> r : csv(dir.resolve("credited.csv"))) {
                credited.put(new EntityKey("invoice", UUID.fromString(r.get(0))), new BigDecimal(r.get(1)));
            }
        }
        IncidentGraph graph = new IncidentGraph(edges, histories);
        long t1 = System.nanoTime();
        ImpactReport report = new ImpactAnalyzer().analyze(fault, graph);
        long t2 = System.nanoTime();
        RecoveryPlan plan = new PlanGenerator().plan(report, graph, credited);
        long t3 = System.nanoTime();
        Files.writeString(dir.resolve("plan.json"), MAPPER.writeValueAsString(plan));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("graphEdges", edges.size());
        out.put("graphRecords", histories.size());
        out.put("graphVersions", histories.values().stream().mapToInt(List::size).sum());
        out.put("parseMs", (t1 - t0) / 1_000_000);
        out.put("analysisMs", (t2 - t1) / 1_000_000);
        out.put("planMs", (t3 - t2) / 1_000_000);
        out.put("steps", plan.steps().size());
        out.put("automaticSteps", plan.steps().stream().filter(RecoveryPlan.Step::automatic).count());
        out.put("manualSteps", plan.manualSteps());
        out.put("fingerprint", plan.fingerprint());
        Map<String, Long> counts = new LinkedHashMap<>();
        report.counts().forEach((c, n) -> counts.put(c.name(), n));
        out.put("counts", counts);
        Set<EntityKey> selected = new HashSet<>();
        report.impacted().forEach(a -> selected.add(a.key()));
        out.put("selected", selected.size());

        if (Files.exists(dir.resolve("truth.csv"))) {
            Set<EntityKey> truth = new HashSet<>();
            for (List<String> r : csv(dir.resolve("truth.csv"))) {
                truth.add(new EntityKey(r.get(0), UUID.fromString(r.get(1))));
            }
            long tp = selected.stream().filter(truth::contains).count();
            out.put("truth", truth.size());
            out.put("truePositives", tp);
            out.put("precision", selected.isEmpty() ? 1.0 : (double) tp / selected.size());
            out.put("recall", truth.isEmpty() ? 1.0 : (double) tp / truth.size());
        }
        if (Files.exists(dir.resolve("truth_totals.csv"))) {
            int checked = 0;
            int mismatched = 0;
            Map<EntityKey, RecoveryPlan.Step> byKey = new HashMap<>();
            plan.steps().forEach(s -> byKey.put(s.key(), s));
            for (List<String> r : csv(dir.resolve("truth_totals.csv"))) {
                EntityKey k = new EntityKey(r.get(0), UUID.fromString(r.get(1)));
                BigDecimal expected = new BigDecimal(r.get(2));
                RecoveryPlan.Step s = byKey.get(k);
                Map<String, Object> current = graph.current(k);
                // Same rule as the Phase 5 experiment: score every step that names a total; a void invoice needs none.
                if (s == null || s.action() == RecoveryPlan.Action.NO_ACTION) {
                    continue;
                }
                checked++;
                if (s.action() == RecoveryPlan.Action.ISSUE_CREDIT_NOTE || s.action() == RecoveryPlan.Action.SUPPLEMENTARY_CHARGE) {
                    BigDecimal owed = expected.subtract(Values.decimal(current.get("total_amount"))).abs();
                    if (!Values.same(owed, s.amount())) {
                        mismatched++;
                    }
                } else {
                    Object proposed = s.changes().stream().filter(c -> c.field().equals("total_amount"))
                            .map(RecoveryPlan.FieldChange::proposed).findFirst().orElse(current.get("total_amount"));
                    if (!Values.same(proposed, expected)) {
                        mismatched++;
                    }
                }
            }
            out.put("totalsChecked", checked);
            out.put("totalsMismatched", mismatched);
        }
        Files.writeString(dir.resolve("result.json"), MAPPER.writeValueAsString(out));
        System.out.println(MAPPER.writeValueAsString(out));
    }

    private static Map<String, Object> object(String json) throws IOException {
        return json == null || json.isEmpty() ? Map.of() : EXACT.forType(OBJECT).readValue(json);
    }

    private static List<String> strings(String json) throws IOException {
        return json == null || json.isEmpty() ? List.of() : MAPPER.readValue(json, STRINGS);
    }

    /** RFC 4180 CSV as written by PostgreSQL COPY ... CSV: quoted fields may contain commas, quotes, newlines; empty unquoted = NULL. */
    static List<List<String>> csv(Path file) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            List<String> row = new ArrayList<>();
            StringBuilder field = new StringBuilder();
            boolean quoted = false;
            boolean wasQuoted = false;
            int c;
            boolean any = false;
            while ((c = in.read()) != -1) {
                any = true;
                char ch = (char) c;
                if (quoted) {
                    if (ch == '"') {
                        in.mark(1);
                        int next = in.read();
                        if (next == '"') {
                            field.append('"');
                        } else {
                            quoted = false;
                            if (next != -1) {
                                in.reset();
                            }
                        }
                    } else {
                        field.append(ch);
                    }
                } else if (ch == '"') {
                    quoted = true;
                    wasQuoted = true;
                } else if (ch == ',') {
                    row.add(wasQuoted || field.length() > 0 ? field.toString() : null);
                    field.setLength(0);
                    wasQuoted = false;
                } else if (ch == '\n') {
                    row.add(wasQuoted || field.length() > 0 ? field.toString() : null);
                    rows.add(row);
                    row = new ArrayList<>();
                    field.setLength(0);
                    wasQuoted = false;
                    any = false;
                } else if (ch != '\r') {
                    field.append(ch);
                }
            }
            if (any) {
                row.add(wasQuoted || field.length() > 0 ? field.toString() : null);
                rows.add(row);
            }
        }
        return rows;
    }
}
