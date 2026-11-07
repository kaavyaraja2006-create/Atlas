package com.atlas.recovery.engine;

import com.atlas.provenance.VersionRef;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * SYNTHETIC test double of the commerce services. It records change events
 * and provenance edges exactly as ProductService, OrderService and
 * BillingService do (same entity types, fields, relations and versioning),
 * and separately keeps plain domain facts (which product version priced each
 * line) that the experiment's oracle uses without ever looking at edges.
 */
public final class SimulatedCommerce {

    private final Map<EntityKey, List<VersionInfo>> history = new LinkedHashMap<>();
    private final Map<EntityKey, Map<String, Object>> state = new HashMap<>();
    private final List<Edge> edges = new ArrayList<>();
    private final Random ids;
    private int orderNo;
    private int invoiceNo;
    private long seq;
    /** Global write sequence of each record's versions (a stand-in for commit time). */
    public final Map<EntityKey, List<Long>> writeSeq = new HashMap<>();

    /** Domain facts for the oracle. */
    public record LineFact(UUID lineId, UUID orderId, UUID productId, long pricedFromVersion, int quantity) {
    }

    public final Map<UUID, LineFact> lines = new LinkedHashMap<>();
    public final Map<UUID, List<UUID>> orderLines = new LinkedHashMap<>();
    public final Map<UUID, UUID> invoiceOfOrder = new LinkedHashMap<>();
    public final Map<UUID, List<UUID>> invoiceLines = new LinkedHashMap<>();
    public final Map<UUID, UUID> invoiceLineSource = new LinkedHashMap<>();

    public SimulatedCommerce(long seed) {
        this.ids = new Random(seed ^ 0x5DEECE66DL);
    }

    private UUID newId() {
        return new UUID(ids.nextLong(), ids.nextLong());
    }

    private static BigDecimal money(Object v) {
        return Values.decimal(v).setScale(4, RoundingMode.UNNECESSARY);
    }

    private long write(EntityKey key, String operation, String actor, Map<String, Object> newState) {
        Map<String, Object> before = state.getOrDefault(key, Map.of());
        Set<String> changed = new TreeSet<>();
        for (String f : newState.keySet()) {
            if (!before.containsKey(f) || !Values.same(before.get(f), newState.get(f))) {
                changed.add(f);
            }
        }
        List<VersionInfo> versions = history.computeIfAbsent(key, k -> new ArrayList<>());
        long version = versions.size();
        versions.add(new VersionInfo(version, operation, actor, changed, newState));
        writeSeq.computeIfAbsent(key, k -> new ArrayList<>()).add(++seq);
        state.put(key, new LinkedHashMap<>(newState));
        return version;
    }

    private void edge(EntityKey s, long sv, String sf, EntityKey t, long tv, String tf, Edge.Relation r) {
        edges.add(new Edge(s.at(sv), sf, t.at(tv), tf, r, true));
    }

    public Map<String, Object> current(EntityKey key) {
        return state.get(key);
    }

    public long version(EntityKey key) {
        return history.get(key).size() - 1L;
    }

    // ------------------------------------------------------------------ catalogue

    public UUID createProduct(String sku, String price) {
        UUID id = newId();
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("sku", sku);
        s.put("name", "Item " + sku);
        s.put("unit_price", money(price));
        s.put("unit_cost", money("1"));
        s.put("active", true);
        write(product(id), "INSERT", "USER", s);
        return id;
    }

    public long updateProduct(UUID id, String field, Object value, String actor) {
        Map<String, Object> s = new LinkedHashMap<>(state.get(product(id)));
        s.put(field, value instanceof String str && field.contains("price") ? money(str) : value);
        return write(product(id), "UPDATE", actor, s);
    }

    // ------------------------------------------------------------------ sales

    public UUID createOrder() {
        UUID id = newId();
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("order_number", "SO-" + (++orderNo));
        s.put("customer_id", "00000000-0000-0000-0000-000000000001");
        s.put("status", "DRAFT");
        s.put("total_amount", money("0"));
        s.put("placed_at", null);
        s.put("line_count", 0);
        write(order(id), "INSERT", "USER", s);
        orderLines.put(id, new ArrayList<>());
        return id;
    }

    /** Mirrors OrderService.addLine: price copied from the product's CURRENT version. */
    public UUID addLine(UUID orderId, UUID productId, int quantity) {
        EntityKey p = product(productId);
        long pv = version(p);
        BigDecimal price = money(state.get(p).get("unit_price"));
        UUID lineId = newId();
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("order_id", orderId.toString());
        l.put("line_no", orderLines.get(orderId).size() + 1);
        l.put("product_id", productId.toString());
        l.put("description", state.get(p).get("sku") + " - " + state.get(p).get("name"));
        l.put("quantity", quantity);
        l.put("unit_price", price);
        l.put("line_total", price.multiply(BigDecimal.valueOf(quantity)));
        EntityKey line = new EntityKey("order_line", lineId);
        write(line, "INSERT", "USER", l);
        edge(p, pv, "unit_price", line, 0, "unit_price", Edge.Relation.DERIVED_VALUE);
        orderLines.get(orderId).add(lineId);
        lines.put(lineId, new LineFact(lineId, orderId, productId, pv, quantity));

        EntityKey o = order(orderId);
        Map<String, Object> os = new LinkedHashMap<>(state.get(o));
        BigDecimal total = BigDecimal.ZERO;
        for (UUID each : orderLines.get(orderId)) {
            total = total.add(money(state.get(new EntityKey("order_line", each)).get("line_total")));
        }
        os.put("total_amount", total);
        os.put("line_count", orderLines.get(orderId).size());
        long ov = write(o, "UPDATE", "USER", os);
        for (UUID each : orderLines.get(orderId)) {
            edge(new EntityKey("order_line", each), 0, "line_total", o, ov, "total_amount", Edge.Relation.AGGREGATED_INTO);
        }
        return lineId;
    }

    public void confirm(UUID orderId) {
        Map<String, Object> s = new LinkedHashMap<>(state.get(order(orderId)));
        s.put("status", "CONFIRMED");
        s.put("placed_at", "2026-11-05T10:00:00Z");
        write(order(orderId), "UPDATE", "USER", s);
    }

    // ------------------------------------------------------------------ billing

    /** Mirrors BillingService.createFromOrder. */
    public UUID invoice(UUID orderId) {
        UUID invoiceId = newId();
        EntityKey inv = new EntityKey("invoice", invoiceId);
        List<UUID> ils = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        List<Map.Entry<UUID, Map<String, Object>>> pending = new ArrayList<>();
        for (UUID lineId : orderLines.get(orderId)) {
            Map<String, Object> ol = state.get(new EntityKey("order_line", lineId));
            UUID ilId = newId();
            Map<String, Object> il = new LinkedHashMap<>();
            il.put("invoice_id", invoiceId.toString());
            il.put("line_no", ol.get("line_no"));
            il.put("order_line_id", lineId.toString());
            il.put("description", ol.get("description"));
            il.put("quantity", ol.get("quantity"));
            il.put("unit_price", ol.get("unit_price"));
            il.put("line_total", ol.get("line_total"));
            total = total.add(money(ol.get("line_total")));
            pending.add(Map.entry(ilId, il));
            ils.add(ilId);
            invoiceLineSource.put(ilId, lineId);
        }
        for (var e : pending) {
            EntityKey il = new EntityKey("invoice_line", e.getKey());
            write(il, "INSERT", "USER", e.getValue());
            edge(new EntityKey("order_line", invoiceLineSource.get(e.getKey())), 0, "unit_price", il, 0, "unit_price",
                    Edge.Relation.COPIED_INTO);
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("invoice_number", "INV-" + (++invoiceNo));
        s.put("order_id", orderId.toString());
        s.put("status", "DRAFT");
        s.put("total_amount", total);
        s.put("issued_at", null);
        s.put("line_count", ils.size());
        write(inv, "INSERT", "USER", s);
        for (UUID ilId : ils) {
            edge(new EntityKey("invoice_line", ilId), 0, "line_total", inv, 0, "total_amount", Edge.Relation.AGGREGATED_INTO);
        }
        invoiceOfOrder.put(orderId, invoiceId);
        invoiceLines.put(invoiceId, ils);
        return invoiceId;
    }

    public void setInvoiceStatus(UUID invoiceId, String status) {
        EntityKey inv = new EntityKey("invoice", invoiceId);
        Map<String, Object> s = new LinkedHashMap<>(state.get(inv));
        s.put("status", status);
        if ("ISSUED".equals(status)) {
            s.put("issued_at", "2026-11-05T11:00:00Z");
        }
        write(inv, "UPDATE", "USER", s);
    }

    /** A person edits one field of any record directly (no provenance edges). */
    public long edit(EntityKey key, String field, Object value) {
        Map<String, Object> s = new LinkedHashMap<>(state.get(key));
        s.put(field, value instanceof String str && (field.contains("price") || field.contains("total")) ? money(str) : value);
        return write(key, "UPDATE", "USER", s);
    }

    /**
     * A person or system re-derives one field from a source's CURRENT version (ATLAS X):
     * a normal write with a recorded provenance edge, e.g. re-pricing a line by hand.
     */
    public long rederive(EntityKey key, String field, Object value, EntityKey source, String sourceField,
                         Edge.Relation relation, String actor) {
        Map<String, Object> s = new LinkedHashMap<>(state.get(key));
        s.put(field, value instanceof String str ? money(str) : value);
        long v = write(key, "UPDATE", actor, s);
        edge(source, version(source), sourceField, key, v, field, relation);
        return v;
    }

    /** A system job (not a person, not recovery) edits one field, with no provenance. */
    public long systemEdit(EntityKey key, String field, Object value) {
        Map<String, Object> s = new LinkedHashMap<>(state.get(key));
        s.put(field, value instanceof String str && (field.contains("price") || field.contains("total")) ? money(str) : value);
        return write(key, "UPDATE", "SYSTEM", s);
    }

    // ------------------------------------------------------------------ recovery writes

    /**
     * Mirrors the recovery executor's field write: a new version by actor
     * RECOVERY, plus one provenance edge per input from the source's current
     * version (so a later analysis sees the corrected flow).
     */
    public long recoveryWrite(EntityKey key, Map<String, Object> fields, List<RecoveryPlan.Input> inputs) {
        Map<String, Object> s = new LinkedHashMap<>(state.get(key));
        s.putAll(fields);
        long v = write(key, "UPDATE", VersionInfo.RECOVERY_ACTOR, s);
        for (RecoveryPlan.Input in : inputs) {
            edge(in.source(), version(in.source()), in.sourceField(), key, v, in.targetField(), in.relation());
        }
        return v;
    }

    /** Mirrors the executor's credit note: a new credit_note record, no provenance edge. */
    public UUID recoveryCreditNote(UUID invoiceId, BigDecimal amount) {
        UUID id = newId();
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("invoice_id", invoiceId.toString());
        s.put("amount", money(amount));
        s.put("status", "ISSUED");
        write(new EntityKey("credit_note", id), "INSERT", VersionInfo.RECOVERY_ACTOR, s);
        return id;
    }

    /** A point to roll back to, for simulating transactions. */
    public record Mark(Map<EntityKey, Integer> sizes, int edges, long seq, int orderNo, int invoiceNo) {
    }

    public Mark mark() {
        Map<EntityKey, Integer> sizes = new HashMap<>();
        history.forEach((k, v) -> sizes.put(k, v.size()));
        return new Mark(sizes, edges.size(), seq, orderNo, invoiceNo);
    }

    /** Undoes every write since the mark (record ids drawn meanwhile are not reused). */
    public void rollback(Mark m) {
        history.entrySet().removeIf(e -> !m.sizes().containsKey(e.getKey()));
        state.keySet().removeIf(k -> !m.sizes().containsKey(k));
        writeSeq.keySet().removeIf(k -> !m.sizes().containsKey(k));
        m.sizes().forEach((k, n) -> {
            List<VersionInfo> versions = history.get(k);
            while (versions.size() > n) {
                versions.remove(versions.size() - 1);
                List<Long> ws = writeSeq.get(k);
                ws.remove(ws.size() - 1);
            }
            state.put(k, new LinkedHashMap<>(versions.get(versions.size() - 1).after()));
        });
        while (edges.size() > m.edges()) {
            edges.remove(edges.size() - 1);
        }
        seq = m.seq();
        orderNo = m.orderNo();
        invoiceNo = m.invoiceNo();
    }

    // ------------------------------------------------------------------ export

    public IncidentGraph graph() {
        return new IncidentGraph(edges, history);
    }

    /** The same history with a random fraction of edges missing (simulates instrumentation gaps). */
    public IncidentGraph graphWithEdgeLoss(double fraction, long seed) {
        Random r = new Random(seed);
        List<Edge> kept = edges.stream().filter(e -> r.nextDouble() >= fraction).toList();
        return new IncidentGraph(kept, history);
    }

    public long seq() {
        return seq;
    }

    public int edgeCount() {
        return edges.size();
    }

    public int versionCount() {
        return history.values().stream().mapToInt(List::size).sum();
    }

    public Map<EntityKey, List<VersionInfo>> histories() {
        return history;
    }

    public FaultyChange faultyChange(UUID productId, long version, String field) {
        EntityKey p = product(productId);
        Map<String, Object> before = history.get(p).get((int) version - 1).after();
        Map<String, Object> after = history.get(p).get((int) version).after();
        return new FaultyChange(p.at(version), Set.of(field), Map.of(field, before.get(field)), Map.of(field, after.get(field)));
    }

    public static EntityKey product(UUID id) {
        return new EntityKey("product", id);
    }

    public static EntityKey order(UUID id) {
        return new EntityKey("order", id);
    }

    static VersionRef ref(EntityKey key, long v) {
        return key.at(v);
    }
}
