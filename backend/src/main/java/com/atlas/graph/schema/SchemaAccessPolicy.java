package com.atlas.graph.schema;

import java.util.UUID;

/**
 * Who may inspect the database schema. One implementation today
 * ({@link PlatformAdminSchemaAccess}: platform administrators only). An organization-level
 * permission later is another implementation of this interface; callers do not change.
 */
public interface SchemaAccessPolicy {

    /** A short description of the rule, recorded with denials. */
    String rule();

    boolean mayInspect(UUID userId);

    /**
     * Throws 403 when {@code userId} may not inspect the schema; the denial is audited first.
     * Every schema endpoint calls this before reading anything.
     */
    void requireSchemaAccess(UUID userId, String attemptedAction);
}
