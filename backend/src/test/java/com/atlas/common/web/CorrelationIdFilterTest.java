package com.atlas.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void keepsSafeClientSuppliedId() throws Exception {
        var request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER, "abc-123_x.y");
        var response = new MockHttpServletResponse();
        AtomicReference<String> seenInMdc = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seenInMdc.set(MDC.get(CorrelationIdFilter.MDC_KEY)));

        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo("abc-123_x.y");
        assertThat(seenInMdc.get()).isEqualTo("abc-123_x.y");
    }

    @Test
    void replacesIdThatCouldInjectIntoLogs() {
        assertThat(CorrelationIdFilter.resolve("evil\nFAKE LOG LINE")).doesNotContain("\n").hasSize(36);
        assertThat(CorrelationIdFilter.resolve("x".repeat(65))).hasSize(36);
        assertThat(CorrelationIdFilter.resolve("")).hasSize(36);
        assertThat(CorrelationIdFilter.resolve(null)).hasSize(36);
    }

    @Test
    void clearsMdcAfterRequestEvenWhenChainThrows() {
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        try {
            filter.doFilter(request, response, (req, res) -> {
                throw new IllegalStateException("boom");
            });
        } catch (Exception expected) {
            // the filter must not swallow it
        }
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }
}
