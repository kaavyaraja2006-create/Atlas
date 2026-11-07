package com.atlas.common.error;

import java.util.List;

/**
 * An error the client caused or must react to, carrying its HTTP status.
 * Framework-free so domain code and its unit tests do not depend on Spring.
 * Rendered as RFC 9457 problem+json by GlobalExceptionHandler.
 */
public class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int status;
    private final String title;
    private final transient List<String> errors;

    public ApiException(int status, String title, String detail) {
        this(status, title, detail, List.of());
    }

    public ApiException(int status, String title, String detail, List<String> errors) {
        super(detail);
        this.status = status;
        this.title = title;
        this.errors = List.copyOf(errors);
    }

    public int status() {
        return status;
    }

    public String title() {
        return title;
    }

    public List<String> errors() {
        return errors;
    }

    public static ApiException badRequest(String detail) {
        return new ApiException(400, "Bad Request", detail);
    }

    public static ApiException validation(List<String> errors) {
        return new ApiException(400, "Validation failed", "One or more fields are invalid.", errors);
    }

    public static ApiException unauthorized(String detail) {
        return new ApiException(401, "Unauthorized", detail);
    }

    public static ApiException forbidden(String permission) {
        return new ApiException(403, "Forbidden", "Missing permission: " + permission);
    }

    /** 403 for a rule other than a missing permission (e.g. separation of duties). */
    public static ApiException denied(String detail) {
        return new ApiException(403, "Forbidden", detail);
    }

    /** Also used for records of another tenant, so their existence is not confirmed. */
    public static ApiException notFound(String what) {
        return new ApiException(404, "Not Found", what + " not found");
    }

    public static ApiException conflict(String detail) {
        return new ApiException(409, "Conflict", detail);
    }

    public static ApiException unprocessable(String detail) {
        return new ApiException(422, "Business rule violated", detail);
    }

    public static ApiException preconditionRequired() {
        return new ApiException(428, "Precondition Required",
                "This update requires an If-Match header carrying the record's current ETag.");
    }
}
