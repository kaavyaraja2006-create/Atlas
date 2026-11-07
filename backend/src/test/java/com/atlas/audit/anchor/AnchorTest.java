package com.atlas.audit.anchor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Anchoring against an in-memory chain that hashes like the database
 * (hash = SHA-256(previous hash || content)), with the attacks the database
 * alone cannot detect.
 */
class AnchorTest {

    private static final UUID ORG = UUID.fromString("0b000000-0000-4000-8000-000000000001");

    /** A chain with stored hashes that an "attacker" can edit, with or without rehashing. */
    static final class Chain implements ChainSegments {
        final List<String> content = new ArrayList<>();   // index i+1
        final List<String> prev = new ArrayList<>();
        final List<String> hash = new ArrayList<>();
        final List<Long> index = new ArrayList<>();

        void append(String c) {
            String p = hash.isEmpty() ? AnchorVerifier.GENESIS : hash.get(hash.size() - 1);
            content.add(c);
            prev.add(p);
            hash.add(sha(p, c));
            index.add((long) content.size());
        }

        /** Edit event n's content and, like a superuser with the trigger disabled, recompute every later hash. */
        void rewrite(int n, String c, boolean rehash) {
            content.set(n - 1, c);
            if (!rehash) {
                return;
            }
            for (int i = n - 1; i < content.size(); i++) {
                String p = i == 0 ? AnchorVerifier.GENESIS : hash.get(i - 1);
                prev.set(i, p);
                hash.set(i, sha(p, content.get(i)));
            }
        }

        void delete(int n) {
            int i = index.indexOf((long) n);
            content.remove(i);
            prev.remove(i);
            hash.remove(i);
            index.remove(i);
        }

        void truncate(int keep) {
            while (content.size() > keep) {
                delete(content.size());
            }
        }

        String headHash() {
            return hash.get(hash.size() - 1);
        }

        @Override
        public Recomputed recompute(UUID org, long after, String prevHash, long upto) {
            String p = prevHash;
            long expect = after + 1;
            Long mismatch = null;
            for (int i = 0; i < index.size(); i++) {
                long idx = index.get(i);
                if (idx <= after || idx > upto) {
                    continue;
                }
                if (idx != expect) {
                    break;
                }
                if (mismatch == null && !prev.get(i).equals(p)) {
                    mismatch = idx;
                }
                p = sha(p, content.get(i));
                if (mismatch == null && !hash.get(i).equals(p)) {
                    mismatch = idx;
                }
                expect++;
            }
            return new Recomputed(expect - 1, p, mismatch);
        }

        @Override
        public long headIndex(UUID org) {
            return index.isEmpty() ? 0 : index.get(index.size() - 1);
        }

        static String sha(String prevHex, String content) {
            try {
                MessageDigest d = MessageDigest.getInstance("SHA-256");
                d.update(HexFormat.of().parseHex(prevHex));
                d.update(content.getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(d.digest());
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** In-memory anchor store an attacker can also edit. */
    static final class MemoryLog implements AnchorLog {
        final List<ChainAnchor> anchors = new ArrayList<>();

        @Override
        public void append(ChainAnchor a) {
            anchors.add(a);
        }

        @Override
        public List<ChainAnchor> read(UUID org) {
            return List.copyOf(anchors);
        }

        @Override
        public String describe() {
            return "memory";
        }
    }

    private final KeyPair keys = AnchorSigner.generate();
    private final AnchorSigner signer = new AnchorSigner("k1", keys.getPrivate());
    private final Map<String, PublicKey> trusted = Map.of("k1", keys.getPublic());
    private Instant now = Instant.parse("2026-11-06T10:00:00Z");

    private Chain chain(int n) {
        Chain c = new Chain();
        for (int i = 1; i <= n; i++) {
            c.append("event " + i);
        }
        return c;
    }

    private void anchorAt(Chain c, MemoryLog log, int index) {
        log.append(signer.sign(ORG, index, c.hash.get(index - 1), now));
        now = now.plusSeconds(300);
    }

    private AnchorReport verify(Chain c, AnchorLog log) {
        return new AnchorVerifier(c, trusted).verify(ORG, log);
    }

    /** Ten events, anchored at #3 and #7. */
    private record Fixture(Chain chain, MemoryLog log) {
    }

    private Fixture anchored() {
        Chain c = chain(10);
        MemoryLog log = new MemoryLog();
        anchorAt(c, log, 3);
        anchorAt(c, log, 7);
        return new Fixture(c, log);
    }

    // ------------------------------------------------------------------ detection

    @Test
    void anUntouchedChainVerifiesAndReportsWhatIsNotYetAnchored() {
        Fixture f = anchored();
        AnchorReport r = verify(f.chain(), f.log());
        assertEquals(AnchorReport.Status.INTACT, r.status(), r.detail());
        assertEquals(2, r.anchorsChecked());
        assertEquals(3, r.unanchoredEvents());
    }

    @Test
    void aConsistentRewriteBeforeAnAnchorIsCaughtAndLocatedToItsSegment() {
        Fixture f = anchored();
        f.chain().rewrite(2, "forged", true);
        AnchorReport r = verify(f.chain(), f.log());
        assertEquals(AnchorReport.Status.REWRITTEN, r.status());
        assertEquals(1, r.fromIndex());
        assertEquals(3, r.toIndex());

        Fixture g = anchored();
        g.chain().rewrite(5, "forged", true);
        AnchorReport s = verify(g.chain(), g.log());
        assertEquals(AnchorReport.Status.REWRITTEN, s.status());
        assertEquals(4, s.fromIndex());
        assertEquals(7, s.toIndex());
    }

    @Test
    void aConsistentRewriteAfterTheLatestAnchorIsNotDetectable() {
        // The documented limit: exposure is the anchoring interval.
        Fixture f = anchored();
        f.chain().rewrite(9, "forged", true);
        assertEquals(AnchorReport.Status.INTACT, verify(f.chain(), f.log()).status());
    }

    @Test
    void anEditWithoutRehashingAfterTheLatestAnchorIsCaught() {
        Fixture f = anchored();
        f.chain().rewrite(9, "edited", false);
        AnchorReport r = verify(f.chain(), f.log());
        assertEquals(AnchorReport.Status.REWRITTEN, r.status());
        assertEquals(9, r.fromIndex());
    }

    @Test
    void deletedAndTruncatedEventsAreCaught() {
        Fixture f = anchored();
        f.chain().delete(5);
        AnchorReport r = verify(f.chain(), f.log());
        assertEquals(AnchorReport.Status.MISSING_EVENTS, r.status());
        assertEquals(5, r.fromIndex());

        Fixture g = anchored();
        g.chain().truncate(5);
        AnchorReport s = verify(g.chain(), g.log());
        assertEquals(AnchorReport.Status.MISSING_EVENTS, s.status(), s.detail());

        Fixture h = anchored();
        h.chain().delete(9);
        assertEquals(AnchorReport.Status.MISSING_EVENTS, verify(h.chain(), h.log()).status());
    }

    // ------------------------------------------------------------------ the anchor store itself

    @Test
    void anAnchorSignedByAnUntrustedKeyIsRejected() throws Exception {
        Fixture f = anchored();
        f.chain().rewrite(2, "forged", true);
        // The attacker re-anchors the forged chain with their own key.
        AnchorSigner theirs = new AnchorSigner("k1", AnchorSigner.generate().getPrivate());
        f.log().anchors.set(0, theirs.sign(ORG, 3, f.chain().hash.get(2), Instant.parse("2026-11-06T10:00:00Z")));
        AnchorReport r = verify(f.chain(), f.log());
        assertEquals(AnchorReport.Status.BAD_SIGNATURE, r.status());
        assertEquals(3, r.fromIndex());
    }

    @Test
    void anEditedAnchorFailsItsSignature() {
        Fixture f = anchored();
        f.chain().rewrite(2, "forged", true);
        ChainAnchor a = f.log().anchors.get(0);
        f.log().anchors.set(0, new ChainAnchor(a.organizationId(), a.chainIndex(), f.chain().hash.get(2), a.anchoredAt(),
                a.keyId(), a.signature()));
        assertEquals(AnchorReport.Status.BAD_SIGNATURE, verify(f.chain(), f.log()).status());
    }

    @Test
    void reorderedAnchorsAreRejectedButAnExactRepeatIsAccepted() {
        Fixture f = anchored();
        f.log().anchors.add(0, f.log().anchors.remove(1));
        assertEquals(AnchorReport.Status.BAD_ANCHOR_LOG, verify(f.chain(), f.log()).status());

        // Later in time but lower in the chain: history was cut back and re-anchored.
        Fixture h = anchored();
        anchorAt(h.chain(), h.log(), 5);
        AnchorReport back = verify(h.chain(), h.log());
        assertEquals(AnchorReport.Status.BAD_ANCHOR_LOG, back.status(), back.detail());

        Fixture g = anchored();
        g.log().anchors.add(g.log().anchors.get(1));   // the same anchor appended twice
        assertEquals(AnchorReport.Status.INTACT, verify(g.chain(), g.log()).status());
    }

    @Test
    void withoutAnchorsTheReportSaysSoInsteadOfClaimingIntegrity() {
        AnchorReport r = verify(chain(4), new MemoryLog());
        assertEquals(AnchorReport.Status.NO_ANCHORS, r.status());
        assertEquals(4, r.unanchoredEvents());
    }

    // ------------------------------------------------------------------ file store

    @Test
    void theFileLogRoundTripsAndRefusesAnythingElse() throws IOException {
        Path dir = Files.createTempDirectory("anchors");
        FileAnchorLog log = new FileAnchorLog(dir);
        Chain c = chain(6);
        ChainAnchor a = signer.sign(ORG, 4, c.hash.get(3), now);
        log.append(a);
        log.append(signer.sign(ORG, 6, c.headHash(), now.plusSeconds(60)));
        List<ChainAnchor> back = log.read(ORG);
        assertEquals(2, back.size());
        assertEquals(a, back.get(0));
        assertEquals(AnchorReport.Status.INTACT, new AnchorVerifier(c, trusted).verify(ORG, log).status());
        assertTrue(log.read(UUID.randomUUID()).isEmpty());

        Path file = dir.resolve(ORG + ".anchors.jsonl");
        Files.writeString(file, "not an anchor\n", java.nio.file.StandardOpenOption.APPEND);
        assertThrows(AnchorLog.UnreadableAnchorLog.class, () -> log.read(ORG));
        assertEquals(AnchorReport.Status.BAD_ANCHOR_LOG, new AnchorVerifier(c, trusted).verify(ORG, log).status());
    }

    // ------------------------------------------------------------------ taking anchors

    static final class Heads implements AnchorService.Heads {
        final Chain chain;
        final List<ChainAnchor> local = new ArrayList<>();
        boolean failLocal;

        Heads(Chain chain) {
            this.chain = chain;
        }

        @Override
        public Optional<AnchorService.Head> head(UUID org) {
            return chain.content.isEmpty() ? Optional.empty()
                    : Optional.of(new AnchorService.Head(chain.headIndex(org), chain.headHash()));
        }

        @Override
        public long latestAnchorIndex(UUID org) {
            return local.isEmpty() ? 0 : local.get(local.size() - 1).chainIndex();
        }

        @Override
        public void recordLocally(ChainAnchor a, String sink) {
            if (failLocal) {
                throw new IllegalStateException("database unavailable");
            }
            local.add(a);
        }
    }

    @Test
    void anchorsAreTakenOnlyWhenTheHeadMovedAndTheExternalCopyComesFirst() {
        Chain c = chain(3);
        Heads heads = new Heads(c);
        MemoryLog log = new MemoryLog();
        AnchorService service = new AnchorService(heads, log, signer, Clock.fixed(now, ZoneOffset.UTC));
        assertTrue(service.anchor(ORG).isPresent());
        assertFalse(service.anchor(ORG).isPresent(), "nothing new to anchor");
        c.append("event 4");
        heads.failLocal = true;
        assertThrows(IllegalStateException.class, () -> service.anchor(ORG));
        assertEquals(2, log.anchors.size(), "the external copy was written before the local record failed");
        heads.failLocal = false;
        assertTrue(service.anchor(ORG).isPresent(), "the next run anchors again");
        assertEquals(AnchorReport.Status.INTACT, new AnchorVerifier(c, trusted).verify(ORG, log).status());
        assertFalse(new AnchorService(new Heads(new Chain()), log, signer, Clock.systemUTC()).anchor(ORG).isPresent(),
                "an empty chain is not anchored");
    }

    // ------------------------------------------------------------------ keys

    @Test
    void keysRoundTripThroughTheirTextFormAndOnlyEd25519IsAccepted() throws Exception {
        String priv = AnchorSigner.encode(keys.getPrivate());
        String pub = AnchorSigner.encode(keys.getPublic());
        AnchorSigner again = new AnchorSigner("k1", AnchorSigner.privateKey(priv));
        ChainAnchor a = again.sign(ORG, 1, "a".repeat(64), now);
        assertTrue(AnchorSigner.verify(a, Map.of("k1", AnchorSigner.publicKey(pub))));
        assertFalse(AnchorSigner.verify(a, Map.of("k2", AnchorSigner.publicKey(pub))), "unknown key id is not trusted");
        KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048);
        assertThrows(IllegalArgumentException.class, () -> new AnchorSigner("k1", rsa.generateKeyPair().getPrivate()));
        assertThrows(IllegalArgumentException.class, () -> AnchorSigner.privateKey("not base64!"));
        assertThrows(IllegalArgumentException.class, () -> new ChainAnchor(ORG, 1, "XYZ", now, "k1", "s"));
    }
}
