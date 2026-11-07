package com.atlas.common.ratelimit;

import com.atlas.common.observability.AtlasMetrics;
import com.atlas.common.web.Problems;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies {@link RateLimitPolicy}. Placed in the security chain after bearer-token
 * authentication, so the API limit can follow the signed-in user. Deliberately not a
 * Spring bean: Boot would also register it as a plain servlet filter and run it twice.
 *
 * <p>The client address is the connection's remote address. Behind a reverse proxy,
 * set {@code server.forward-headers-strategy=framework} (ATLAS_FORWARD_HEADERS) so it is
 * the real client; never trust X-Forwarded-For from the open internet.
 */
public final class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitPolicy policy;
    private final ObjectMapper mapper;
    private final AtlasMetrics metrics;

    public RateLimitFilter(RateLimitPolicy policy, ObjectMapper mapper, AtlasMetrics metrics) {
        this.policy = policy;
        this.mapper = mapper;
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String user = auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName()) ? auth.getName() : null;
        Optional<RateLimitPolicy.Outcome> outcome = policy.check(request.getMethod(), request.getRequestURI(),
                request.getRemoteAddr(), user);
        if (outcome.isEmpty() || outcome.get().decision().allowed()) {
            chain.doFilter(request, response);
            return;
        }
        long retry = outcome.get().decision().retryAfterSeconds();
        metrics.rateLimited(outcome.get().limit());
        ProblemDetail problem = Problems.of(429, "Too Many Requests",
                "Too many requests (" + outcome.get().limit() + " limit). Try again in " + retry + " s.");
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(retry));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), problem);
    }
}
