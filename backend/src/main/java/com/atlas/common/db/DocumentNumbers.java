package com.atlas.common.db;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Gapless, per-organization document numbers (SO-000001, INV-000001,
 * CN-000001). Must run in the transaction that creates the document, so a
 * rollback also releases the number.
 */
@Component
public class DocumentNumbers {

    static final String NEXT_DOCUMENT_NUMBER = """
            SELECT next_document_number(:orgId, :kind)
            """;

    private final JdbcClient jdbc;

    public DocumentNumbers(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String next(UUID orgId, DocumentKind kind) {
        long value = jdbc.sql(NEXT_DOCUMENT_NUMBER).param("orgId", orgId).param("kind", kind.name())
                .query(Long.class).single();
        return kind.format(value);
    }
}
