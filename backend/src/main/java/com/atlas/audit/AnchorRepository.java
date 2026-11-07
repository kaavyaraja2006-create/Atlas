package com.atlas.audit;

import com.atlas.audit.anchor.ChainAnchor;
import com.atlas.audit.anchor.ChainSegments;
import com.atlas.common.db.Db;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for audit-chain anchoring (V9). */
@Repository
class AnchorRepository implements ChainSegments {

    static final String CHAIN_HEAD = """
            SELECT last_index, encode(last_hash, 'hex') AS last_hash
              FROM audit_chain_heads WHERE organization_id = :orgId
            """;

    static final String ORGANIZATIONS_WITH_CHAINS = """
            SELECT organization_id FROM audit_chain_heads WHERE last_index > 0 ORDER BY organization_id
            """;

    /** Recomputes from content; see recompute_audit_chain in V9. */
    static final String RECOMPUTE_CHAIN = """
            SELECT last_index, encode(last_hash, 'hex') AS last_hash, first_mismatch
              FROM recompute_audit_chain(:orgId, :after, decode(:prevHash, 'hex'), :upto)
            """;

    static final String INSERT_ANCHOR = """
            INSERT INTO audit_anchors (organization_id, chain_index, hash, key_id, signature, sink, anchored_at)
            VALUES (:orgId, :chainIndex, decode(:hash, 'hex'), :keyId, :signature, :sink, :anchoredAt)
            ON CONFLICT (organization_id, chain_index) DO NOTHING
            """;

    static final String LATEST_ANCHOR_INDEX = """
            SELECT COALESCE(max(chain_index), 0) FROM audit_anchors WHERE organization_id = :orgId
            """;

    record Head(long index, String hash) {
    }

    private final JdbcClient jdbc;

    AnchorRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Head> head(UUID orgId) {
        return jdbc.sql(CHAIN_HEAD).param("orgId", orgId)
                .query((rs, n) -> new Head(rs.getLong("last_index"), rs.getString("last_hash"))).optional();
    }

    List<UUID> organizationsWithChains() {
        return jdbc.sql(ORGANIZATIONS_WITH_CHAINS).query(UUID.class).list();
    }

    long latestAnchorIndex(UUID orgId) {
        return jdbc.sql(LATEST_ANCHOR_INDEX).param("orgId", orgId).query(Long.class).single();
    }

    void record(ChainAnchor a, String sink) {
        jdbc.sql(INSERT_ANCHOR).param("orgId", a.organizationId()).param("chainIndex", a.chainIndex())
                .param("hash", a.hash()).param("keyId", a.keyId()).param("signature", a.signature())
                .param("sink", sink).param("anchoredAt", Db.ts(a.anchoredAt())).update();
    }

    @Override
    public Recomputed recompute(UUID orgId, long after, String prevHash, long upto) {
        return jdbc.sql(RECOMPUTE_CHAIN).param("orgId", orgId).param("after", after).param("prevHash", prevHash)
                .param("upto", upto)
                .query((rs, n) -> new Recomputed(rs.getLong("last_index"), rs.getString("last_hash"),
                        rs.getObject("first_mismatch", Long.class)))
                .single();
    }

    @Override
    public long headIndex(UUID orgId) {
        return head(orgId).map(Head::index).orElse(0L);
    }

    static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
