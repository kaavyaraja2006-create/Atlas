package com.atlas.common.db;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Small JDBC helpers shared by repositories. */
public final class Db {

    private Db() {
    }

    /** pgjdbc binds OffsetDateTime to timestamptz; it cannot infer a type for Instant. */
    public static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    public static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    /** Builds a contains-pattern for ILIKE ... ESCAPE '\', escaping the user's wildcards. */
    public static String containsPattern(String text) {
        String escaped = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
