package com.atlas.common.web;

import com.atlas.common.error.ApiException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * One error format for the whole API: RFC 9457 problem details
 * ({@code application/problem+json}), always carrying the request's
 * correlation id. Internal exception messages and stack traces never reach
 * the client.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Enriches every problem produced by Spring MVC's built-in handlers (400, 404, 405, ...). */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex,
                                                             @Nullable Object body,
                                                             HttpHeaders headers,
                                                             HttpStatusCode statusCode,
                                                             WebRequest request) {
        ResponseEntity<Object> response = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            Problems.enrich(problem);
        }
        return response;
    }

    /** Bean Validation failures: list every invalid field, not just the first. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .sorted()
                .toList();
        ProblemDetail problem = Problems.of(400, "Validation failed", "One or more fields are invalid.");
        problem.setProperty("errors", errors);
        return ResponseEntity.status(400).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApi(ApiException ex) {
        ProblemDetail problem = Problems.of(ex.status(), ex.title(), ex.getMessage());
        if (!ex.errors().isEmpty()) {
            problem.setProperty("errors", ex.errors());
        }
        return respond(ex.status(), problem);
    }

    /** A unique constraint the service did not anticipate; never echo the SQL message. */
    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ProblemDetail> handleDuplicate(DuplicateKeyException ex) {
        log.info("Unique constraint violation: {}", ex.getMostSpecificCause().getMessage());
        return respond(409, Problems.of(409, "Conflict", "The request conflicts with an existing record."));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleIntegrity(DataIntegrityViolationException ex) {
        log.info("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return respond(409, Problems.of(409, "Conflict", "The request violates a data integrity rule."));
    }

    /** Serialization failures and lock timeouts: nothing was changed; the client may retry. */
    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<ProblemDetail> handleConcurrency(ConcurrencyFailureException ex) {
        log.info("Concurrency failure: {}", ex.getMostSpecificCause().getMessage());
        return respond(409, Problems.of(409, "Conflict",
                "The data changed while this request was running. Nothing was saved; try again."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return respond(500, Problems.of(500, "Internal Server Error",
                "An unexpected error occurred. Quote the correlation id when reporting it."));
    }

    private static ResponseEntity<ProblemDetail> respond(int status, ProblemDetail problem) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }
}
