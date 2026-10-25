package com.apac.claims.shared.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    private static final String UUID_PATTERN = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

    private final CorrelationIdFilter filter = new CorrelationIdFilter();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @AfterEach
    void clearLoggingContext() {
        MDC.clear();
    }

    /** Runs the filter and returns the id that code inside the chain could see in the logging context. */
    private String runWith(String headerValue) throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (headerValue != null) {
            request.addHeader(CorrelationIdFilter.HEADER, headerValue);
        }
        AtomicReference<String> seenInChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> seenInChain.set(MDC.get(CorrelationIdFilter.MDC_KEY));
        filter.doFilter(request, response, chain);
        return seenInChain.get();
    }

    @Test
    void generatesAUuidWhenTheHeaderIsMissing() throws Exception {
        String seen = runWith(null);

        assertThat(seen).matches(UUID_PATTERN);
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo(seen);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc-123", "req_2026.03.01", "A", "0123456789012345678901234567890123456789012345678901234567890123"})
    void echoesAValidValue(String supplied) throws Exception {
        String seen = runWith(supplied);

        assertThat(seen).isEqualTo(supplied);
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo(supplied);
    }

    @ParameterizedTest
    @ValueSource(strings = {"has space", "semi;colon", "slash/slash", "new\nline", "tab\there", "café", "<script>"})
    void replacesAValueWithInvalidCharacters(String supplied) throws Exception {
        String seen = runWith(supplied);

        assertThat(seen).isNotEqualTo(supplied).matches(UUID_PATTERN);
        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo(seen);
    }

    @Test
    void replacesAnOverLongValue() throws Exception {
        String sixtyFiveCharacters = "a".repeat(65);

        String seen = runWith(sixtyFiveCharacters);

        assertThat(seen).matches(UUID_PATTERN);
    }

    @Test
    void replacesAnEmptyValue() throws Exception {
        assertThat(runWith("")).matches(UUID_PATTERN);
    }

    @Test
    void theHeaderIsAlreadyOnTheResponseWhenTheChainStarts() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        AtomicReference<String> headerInChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) ->
                headerInChain.set(((MockHttpServletResponse) res).getHeader(CorrelationIdFilter.HEADER)));

        // A 401 written by the security filters happens inside the chain, so the header must already be there.
        assertThat(headerInChain.get()).matches(UUID_PATTERN);
    }

    @Test
    void clearsTheLoggingContextAfterTheRequest() throws Exception {
        runWith("abc-123");

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void clearsTheLoggingContextEvenWhenTheChainFails() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        FilterChain failing = (req, res) -> {
            throw new ServletException("boom");
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, failing)).isInstanceOf(ServletException.class);

        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void twoRequestsGetDifferentGeneratedIds() throws Exception {
        String first = runWith(null);
        String second = runWith(null);

        assertThat(first).isNotEqualTo(second);
    }
}
