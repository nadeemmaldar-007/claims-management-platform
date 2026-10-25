package com.apac.claims.shared.api;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.apac.claims.shared.exception.ErrorCode;
import com.apac.claims.shared.exception.ValidationFailedException.FieldError;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/**
 * Builds the RFC 7807 error body used everywhere: the standard fields plus {@code code}, {@code timestamp},
 * {@code correlationId} (when the request has one) and, for validation failures, {@code errors}. The exception
 * handler and the security entry points both use it, so every error looks the same.
 */
public final class ProblemDetails {

    public static final String CODE = "code";
    public static final String TIMESTAMP = "timestamp";
    public static final String CORRELATION_ID = "correlationId";
    public static final String ERRORS = "errors";

    private ProblemDetails() {
    }

    public static ProblemDetail of(Clock clock, HttpStatusCode status, ErrorCode code, String detail) {
        return of(clock, status, code, detail, null);
    }

    /** Builds a problem; {@code errors} may be null or empty, in which case the property is left out. */
    public static ProblemDetail of(Clock clock, HttpStatusCode status, ErrorCode code, String detail,
                                   List<FieldError> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        if (errors != null && !errors.isEmpty()) {
            problem.setProperty(ERRORS, errors);
        }
        return decorate(clock, problem, code);
    }

    /**
     * Adds {@code code}, {@code timestamp}, {@code correlationId} and a title when they are missing. Existing
     * values are kept, so a problem that was built with a specific code is not overwritten by a default one.
     */
    public static ProblemDetail decorate(Clock clock, ProblemDetail problem, ErrorCode defaultCode) {
        if (problem.getTitle() == null) {
            HttpStatus resolved = HttpStatus.resolve(problem.getStatus());
            if (resolved != null) {
                problem.setTitle(resolved.getReasonPhrase());
            }
        }
        Map<String, Object> existing = problem.getProperties();
        if (existing == null || !existing.containsKey(CODE)) {
            problem.setProperty(CODE, defaultCode.name());
        }
        if (existing == null || !existing.containsKey(TIMESTAMP)) {
            problem.setProperty(TIMESTAMP, Instant.now(clock).toString());
        }
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null && (existing == null || !existing.containsKey(CORRELATION_ID))) {
            problem.setProperty(CORRELATION_ID, correlationId);
        }
        return problem;
    }

    /** Sets the {@code instance} to the request path; a path that is not a valid URI is simply left out. */
    public static ProblemDetail withInstance(ProblemDetail problem, String requestPath) {
        if (requestPath != null) {
            try {
                problem.setInstance(URI.create(requestPath));
            } catch (IllegalArgumentException ignored) {
                // Not worth failing an error response over a path that cannot be expressed as a URI.
            }
        }
        return problem;
    }

    /** The code used for a status when no more specific one was chosen, for example on Spring's own 405 and 415. */
    public static ErrorCode defaultCodeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 401 -> ErrorCode.UNAUTHENTICATED;
            case 403 -> ErrorCode.ACCESS_DENIED;
            case 404 -> ErrorCode.RESOURCE_NOT_FOUND;
            default -> status.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : ErrorCode.MALFORMED_REQUEST;
        };
    }
}
