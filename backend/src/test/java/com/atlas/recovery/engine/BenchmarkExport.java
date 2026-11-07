package com.atlas.recovery.engine;

import java.io.IOException;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SYNTHETIC benchmark data: writes one generated history (the Phase 5
 * generator, same seeds) as CSV files that PostgreSQL loads with COPY.
 *
 * <p>Change events are written in global write order, so the database's own
 * trigger assigns chain positions and hashes exactly as live writes would.
 * Business rows carry each record's current state and version. The oracle's
 * ground truth is written separately; the benchmark never derives it from
 * the database.
 *
 * <p>Usage: {@code BenchmarkExport <orders> <seed> <LIVE|OVERWRITTEN> <outDir>}
 */
public final class BenchmarkExport {

    private BenchmarkExport() {
    }

    public static void main(String[] args) throws IOException {
        int orders = Integer.parseInt(args[0]);
        long seed = Long.parseLong(args[1]);
        ImpactExperiment.Scenario scenario = ImpactExperiment.Scenario.valueOf(args[2]);
        Path out = Path.of(args[3]);
        Files.createDirectories(out);

        ImpactExperiment.World w = ImpactExperiment.generate(orders, seed, scenario);
        SimulatedCommerce sim = w.sim();
        Map<EntityKey, List<VersionInfo>> histories = sim.histories();

        try (PrintWriter products = writer(out, "products.csv");
             PrintWriter orderRows = writer(out, "orders.csv");
             PrintWriter lines = writer(out, "order_lines.csv");
             PrintWriter invoices = writer(out, "invoices.csv");
             PrintWriter invoiceLines = writer(out, "invoice_lines.csv")) {
            for (var e : histories.entrySet()) {
                EntityKey k = e.getKey();
                Map<String, Object> s = sim.current(k);
                long v = e.getValue().size() - 1L;
                String id = k.entityId().toString();
                switch (k.entityType()) {
                    case "product" -> row(products, id, s.get("sku"), s.get("name"), s.get("unit_price"),
                            s.get("unit_cost"), s.get("active"), v);
                    case "order" -> row(orderRows, id, s.get("order_number"), s.get("customer_id"), s.get("status"),
                            s.get("total_amount"), s.get("placed_at"), v);
                    case "order_line" -> row(lines, id, s.get("order_id"), s.get("line_no"), s.get("product_id"),
                            s.get("description"), s.get("quantity"), s.get("unit_price"), v);
                    case "invoice" -> row(invoices, id, s.get("order_id"), s.get("invoice_number"), s.get("total_amount"),
                            s.get("status"), s.get("issued_at"), v);
                    case "invoice_line" -> row(invoiceLines, id, s.get("invoice_id"), s.get("line_no"),
                            s.get("order_line_id"), s.get("description"), s.get("quantity"), s.get("unit_price"));
                    default -> throw new IllegalStateException("unexpected type " + k.entityType());
                }
            }
        }

        // Change events in global write order (writeSeq), each with its before-state.
        record Ev(long seq, EntityKey key, int version) {
        }
        List<Ev> events = new ArrayList<>();
        sim.writeSeq.forEach((k, seqs) -> {
            for (int i = 0; i < seqs.size(); i++) {
                events.add(new Ev(seqs.get(i), k, i));
            }
        });
        events.sort((a, b) -> Long.compare(a.seq(), b.seq()));
        Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
        long faultSeq = -1;
        EntityKey faultKey = EntityKey.of(w.fault().ref());
        try (PrintWriter ce = writer(out, "change_events.csv")) {
            for (Ev e : events) {
                List<VersionInfo> h = histories.get(e.key());
                VersionInfo vi = h.get(e.version());
                Map<String, Object> before = e.version() == 0 ? null : h.get(e.version() - 1).after();
                if (e.key().equals(faultKey) && e.version() == w.faultyVersion()) {
                    faultSeq = e.seq();
                }
                row(ce, UUID.nameUUIDFromBytes(("txn" + e.seq()).getBytes(StandardCharsets.UTF_8)), vi.actorKind(),
                        e.key().entityType(), e.key().entityId(), e.version(), vi.operation(),
                        "{" + String.join(",", new java.util.TreeSet<>(vi.changedFields())) + "}",
                        before == null ? null : json(before), json(vi.after()), t0.plusSeconds(e.seq()));
            }
        }
        try (PrintWriter pe = writer(out, "provenance_edges.csv")) {
            for (Edge e : sim.graph().edges()) {
                row(pe, e.source().entityType(), e.source().entityId(), e.source().version(), e.sourceField(),
                        e.target().entityType(), e.target().entityId(), e.target().version(), e.targetField(),
                        e.relation().name(), e.recordedAtWrite() ? "RECORDED_AT_WRITE" : "BACKFILLED");
            }
        }
        try (PrintWriter t = writer(out, "truth.csv")) {
            for (EntityKey k : w.truth().affected()) {
                row(t, k.entityType(), k.entityId());
            }
        }
        try (PrintWriter t = writer(out, "truth_totals.csv")) {
            for (var e : w.truth().correctTotals().entrySet()) {
                row(t, e.getKey().entityType(), e.getKey().entityId(), e.getValue());
            }
        }
        Files.writeString(out.resolve("meta.json"), "{\"orders\":" + orders + ",\"seed\":" + seed + ",\"scenario\":\""
                + scenario + "\",\"faultyProduct\":\"" + w.faultyProduct() + "\",\"faultyVersion\":" + w.faultyVersion()
                + ",\"faultSeq\":" + faultSeq + ",\"versions\":" + sim.versionCount() + ",\"edges\":" + sim.edgeCount()
                + ",\"truth\":" + w.truth().affected().size() + ",\"naive\":" + w.truth().naive().size()
                + ",\"fullRestore\":" + w.truth().fullRestore().size() + "}\n");
    }

    private static PrintWriter writer(Path dir, String name) throws IOException {
        return new PrintWriter(Files.newBufferedWriter(dir.resolve(name), StandardCharsets.UTF_8));
    }

    /** One CSV row: every value quoted; null as an unquoted empty field (COPY's NULL). */
    private static void row(PrintWriter w, Object... values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            Object v = values[i];
            if (v == null) {
                continue;
            }
            String s = v instanceof BigDecimal d ? d.toPlainString() : String.valueOf(v);
            sb.append('"').append(s.replace("\"", "\"\"")).append('"');
        }
        w.println(sb);
    }

    static String json(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var e : m.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(str(e.getKey())).append(':').append(value(e.getValue()));
        }
        return sb.append('}').toString();
    }

    private static String value(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof BigDecimal d) {
            return d.toPlainString();
        }
        if (v instanceof Number || v instanceof Boolean) {
            return v.toString();
        }
        if (v instanceof Collection<?> c) {
            return "[" + String.join(",", c.stream().map(BenchmarkExport::value).toList()) + "]";
        }
        return str(v.toString());
    }

    private static String str(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
