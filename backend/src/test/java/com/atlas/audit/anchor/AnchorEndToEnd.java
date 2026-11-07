package com.atlas.audit.anchor;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * End-to-end anchoring against PostgreSQL, without Spring: the real signer, file
 * log, service and verifier, and the real SQL (read from AnchorRepository's
 * constants) run through psql. Creates a throwaway database, writes events,
 * anchors twice, then plays the superuser rewrite that verify_audit_chain cannot
 * see. Exits non-zero if any expectation fails.
 *
 * <p>Run by scripts/anchor-end-to-end.sh (needs psql, PG* variables, a superuser).
 */
public final class AnchorEndToEnd {

    private static final UUID ORG = UUID.fromString("0b000000-0000-4000-8000-0000000000e2");
    private static final String DB = "atlas_anchor_e2e";
    private static int failures;

    private AnchorEndToEnd() {
    }

    public static void main(String[] args) throws Exception {
        Path migrations = Path.of(args[0]);
        Map<String, String> sql = statements(Class.forName("com.atlas.audit.AnchorRepository"));
        psql("postgres", "DROP DATABASE IF EXISTS " + DB);
        psql("postgres", "CREATE DATABASE " + DB);
        try {
            try (var files = Files.list(migrations)) {
                for (Path f : files.filter(p -> p.getFileName().toString().matches("V\\d+__.*\\.sql"))
                        .sorted(Comparator.comparingInt(p -> Integer.parseInt(p.getFileName().toString().split("__")[0].substring(1))))
                        .toList()) {
                    run(List.of("psql", "-X", "-q", "-v", "ON_ERROR_STOP=1", "-d", DB, "--single-transaction", "-f", f.toString()));
                }
            }
            psql(DB, "INSERT INTO organizations (id, slug, name) VALUES ('" + ORG + "', 'e2e', 'Anchor e2e (synthetic)')");
            Psql chain = new Psql(sql);
            Path dir = Files.createTempDirectory("anchors");
            FileAnchorLog log = new FileAnchorLog(dir);
            KeyPair keys = AnchorSigner.generate();
            AnchorSigner signer = new AnchorSigner("e2e-1", keys.getPrivate());
            AnchorService service = new AnchorService(chain, log, signer, Clock.systemUTC());
            AnchorVerifier verifier = new AnchorVerifier(chain, Map.of("e2e-1", keys.getPublic()));

            events(1, 20);
            expect("first anchor taken at #20", service.anchor(ORG).map(ChainAnchor::chainIndex).orElse(0L) == 20);
            expect("no anchor when nothing changed", service.anchor(ORG).isEmpty());
            events(21, 25);
            expect("second anchor at #25", service.anchor(ORG).map(ChainAnchor::chainIndex).orElse(0L) == 25);
            events(26, 30);
            expect("anchors are in the external file", Files.readAllLines(dir.resolve(ORG + ".anchors.jsonl")).size() == 2);
            expect("and recorded locally", psql(DB, sql.get("LATEST_ANCHOR_INDEX").replace(":orgId", q(ORG))).equals(List.of("25")));

            AnchorReport ok = verifier.verify(ORG, log);
            expect("untouched chain: INTACT, 5 events not yet anchored (" + ok.detail() + ")",
                    ok.status() == AnchorReport.Status.INTACT && ok.unanchoredEvents() == 5);

            // The superuser rewrite: forge event 7, recompute every later hash and the head.
            psql(DB, "BEGIN; SET LOCAL session_replication_role = replica;"
                    + " UPDATE change_events SET after_state = '{\"name\": \"forged\"}' WHERE organization_id = " + q(ORG)
                    + " AND chain_index = 7;"
                    + " DO $$ DECLARE e change_events; v bytea; BEGIN"
                    + "  SELECT hash INTO v FROM change_events WHERE organization_id = " + q(ORG) + " AND chain_index = 6;"
                    + "  FOR e IN SELECT * FROM change_events WHERE organization_id = " + q(ORG)
                    + "   AND chain_index >= 7 ORDER BY chain_index LOOP"
                    + "   UPDATE change_events SET prev_hash = v, hash = sha256(v || change_event_payload(e)) WHERE id = e.id;"
                    + "   v := sha256(v || change_event_payload(e)); END LOOP;"
                    + "  UPDATE audit_chain_heads SET last_hash = v WHERE organization_id = " + q(ORG) + "; END $$; COMMIT;");
            expect("verify_audit_chain is fooled", psql(DB, "SELECT verify_audit_chain(" + q(ORG) + ")").equals(List.of("")));
            AnchorReport bad = verifier.verify(ORG, log);
            expect("anchors catch it: REWRITTEN in #1-#20 (" + bad.detail() + ")",
                    bad.status() == AnchorReport.Status.REWRITTEN && bad.fromIndex() == 1 && bad.toIndex() == 20);
        } finally {
            psql("postgres", "DROP DATABASE IF EXISTS " + DB);
        }
        System.out.println(failures == 0 ? "== all expectations met" : "== " + failures + " FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    /** The repository's SQL, through psql, as ChainSegments and AnchorService.Heads. */
    static final class Psql implements ChainSegments, AnchorService.Heads {
        private final Map<String, String> sql;

        Psql(Map<String, String> sql) {
            this.sql = sql;
        }

        @Override
        public Recomputed recompute(UUID org, long after, String prevHash, long upto) {
            String[] r = psql(DB, sql.get("RECOMPUTE_CHAIN").replace(":orgId", q(org)).replace(":after", "" + after)
                    .replace(":prevHash", "'" + prevHash + "'").replace(":upto", "" + upto)).get(0).split("\\|", -1);
            return new Recomputed(Long.parseLong(r[0]), r[1], r[2].isEmpty() ? null : Long.parseLong(r[2]));
        }

        @Override
        public long headIndex(UUID org) {
            return head(org).map(AnchorService.Head::index).orElse(0L);
        }

        @Override
        public Optional<AnchorService.Head> head(UUID org) {
            List<String> rows = psql(DB, sql.get("CHAIN_HEAD").replace(":orgId", q(org)));
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            String[] r = rows.get(0).split("\\|");
            return Optional.of(new AnchorService.Head(Long.parseLong(r[0]), r[1]));
        }

        @Override
        public long latestAnchorIndex(UUID org) {
            return Long.parseLong(psql(DB, sql.get("LATEST_ANCHOR_INDEX").replace(":orgId", q(org))).get(0));
        }

        @Override
        public void recordLocally(ChainAnchor a, String sink) {
            psql(DB, sql.get("INSERT_ANCHOR").replace(":orgId", q(a.organizationId()))
                    .replace(":chainIndex", "" + a.chainIndex()).replace(":hash", "'" + a.hash() + "'")
                    .replace(":keyId", "'" + a.keyId() + "'").replace(":signature", "'" + a.signature() + "'")
                    .replace(":sink", "'" + sink.replace("'", "''") + "'").replace(":anchoredAt", "'" + a.anchoredAt() + "'"));
        }
    }

    private static void events(int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i <= to; i++) {
            sb.append("INSERT INTO change_events (organization_id, txn_id, actor_kind, entity_type, entity_id, entity_version,"
                    + " operation, changed_fields, after_state) VALUES (").append(q(ORG)).append(", gen_random_uuid(),"
                    + " 'SYSTEM', 'product', gen_random_uuid(), 0, 'INSERT', '{}', '{\"name\": \"item ").append(i).append("\"}');");
        }
        psql(DB, sb.toString());
    }

    private static void expect(String what, boolean ok) {
        System.out.println((ok ? "   PASS  " : "   FAIL  ") + what);
        if (!ok) {
            failures++;
        }
    }

    static Map<String, String> statements(Class<?> c) throws IllegalAccessException {
        Map<String, String> out = new java.util.HashMap<>();
        for (Field f : c.getDeclaredFields()) {
            if (f.getType() == String.class && java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                f.setAccessible(true);
                out.put(f.getName(), (String) f.get(null));
            }
        }
        return out;
    }

    static String q(UUID id) {
        return "'" + id + "'";
    }

    private static final Pattern TAG = Pattern.compile("^(INSERT|UPDATE|DELETE|BEGIN|COMMIT|SET|DO|CREATE|DROP)( .*)?$");

    static List<String> psql(String db, String sql) {
        return run(List.of("psql", "-X", "-q", "-At", "-v", "ON_ERROR_STOP=1", "-d", db, "-c", sql));
    }

    static List<String> run(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.waitFor() != 0) {
                throw new IllegalStateException(err.strip());
            }
            if (out.equals("\n")) {
                return List.of("");   // one row holding NULL
            }
            List<String> rows = new ArrayList<>();
            for (String line : out.split("\n")) {
                Matcher m = TAG.matcher(line);
                if (!line.isEmpty() && !m.matches()) {
                    rows.add(line);
                }
            }
            return rows;
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
