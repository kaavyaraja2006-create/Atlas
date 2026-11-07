package com.atlas.common.web;

import com.atlas.common.error.ApiException;

/**
 * Record versions travel as strong ETags ("7"). Updates must send
 * If-Match with the version the client last read; a mismatch is a 409, a
 * missing header is a 428. This is how concurrent edits are caught instead
 * of silently overwritten.
 */
public final class ETags {

    private ETags() {
    }

    public static String of(long version) {
        return "\"" + version + "\"";
    }

    public static long requireVersion(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw ApiException.preconditionRequired();
        }
        String value = ifMatch.trim();
        // If-Match uses strong comparison, so weak validators (W/"7") never match.
        if (value.length() < 3 || value.charAt(0) != '"' || value.charAt(value.length() - 1) != '"') {
            throw ApiException.badRequest("Malformed If-Match header; expected a quoted version such as \"3\".");
        }
        try {
            long version = Long.parseLong(value.substring(1, value.length() - 1));
            if (version < 0) {
                throw new NumberFormatException();
            }
            return version;
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("Malformed If-Match header; expected a quoted version such as \"3\".");
        }
    }
}
