package com.atlas.common.web;

import java.time.Instant;
import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/** Builds RFC 9457 problem details that always carry correlationId and timestamp. */
public final class Problems {

    private Problems() {
    }

    public static ProblemDetail of(int status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail);
        problem.setTitle(title);
        return enrich(problem);
    }

    public static ProblemDetail enrich(ProblemDetail problem) {
        problem.setProperty("correlationId", MDC.get(CorrelationIdFilter.MDC_KEY));
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }
}
