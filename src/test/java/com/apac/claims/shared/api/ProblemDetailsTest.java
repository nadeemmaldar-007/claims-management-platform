package com.apac.claims.shared.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.apac.claims.shared.exception.ErrorCode;
import com.apac.claims.shared.exception.ValidationFailedException.FieldError;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

class ProblemDetailsTest {

    private static final Instant NOW = Instant.parse("2026-03-01T10:15:30.123456Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @AfterEach
    void clearLoggingContext() {
        MDC.clear();
    }

    @Test
    void buildsTheStandardFieldsAndTheExtraProperties() {
        ProblemDetail problem = ProblemDetails.of(CLOCK, HttpStatus.CONFLICT, ErrorCode.CLAIM_INVALID_TRANSITION,
                "Not allowed from APPROVED");

        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getTitle()).isEqualTo("Conflict");
        assertThat(problem.getDetail()).isEqualTo("Not allowed from APPROVED");
        assertThat(problem.getProperties()).containsEntry("code", "CLAIM_INVALID_TRANSITION");
    }

    @Test
    void theTimestampIsTheIsoInstantFromTheClock() {
        ProblemDetail problem = ProblemDetails.of(CLOCK, HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "x");

        assertThat(problem.getProperties()).containsEntry("timestamp", "2026-03-01T10:15:30.123456Z");
        assertThat(Instant.parse((String) problem.getProperties().get("timestamp"))).isEqualTo(NOW);
    }

    @Test
    void theCorrelationIdComesFromTheLoggingContext() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "abc-123");

        ProblemDetail problem = ProblemDetails.of(CLOCK, HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "x");

        assertThat(problem.getProperties()).containsEntry("correlationId", "abc-123");
    }

    @Test
    void theCorrelationIdIsAbsentWhenTheRequestHasNone() {
        ProblemDetail problem = ProblemDetails.of(CLOCK, HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "x");

        assertThat(problem.getProperties()).doesNotContainKey("correlationId");
    }

    @Test
    void fieldErrorsAreListedWithFieldAndMessage() {
        List<FieldError> errors = List.of(new FieldError("password", "too short"), new FieldError("email", "invalid"));

        ProblemDetail problem = ProblemDetails.of(CLOCK, HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "x",
                errors);

        assertThat(problem.getProperties()).containsEntry("errors", errors);
    }

    @Test
    void theErrorsPropertyIsLeftOutWhenThereAreNone() {
        ProblemDetail withNull = ProblemDetails.of(CLOCK, HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "x",
                null);
        ProblemDetail withEmpty = ProblemDetails.of(CLOCK, HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED, "x",
                List.of());

        assertThat(withNull.getProperties()).doesNotContainKey("errors");
        assertThat(withEmpty.getProperties()).doesNotContainKey("errors");
    }

    @Test
    void decoratingKeepsAnExistingCodeInsteadOfOverwritingIt() {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setProperty("code", "VALIDATION_FAILED");

        ProblemDetails.decorate(CLOCK, problem, ErrorCode.MALFORMED_REQUEST);

        assertThat(problem.getProperties()).containsEntry("code", "VALIDATION_FAILED");
        assertThat(problem.getProperties()).containsKeys("timestamp");
    }

    @Test
    void decoratingAddsTheMissingPropertiesAndTitleToAPlainProblem() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "req-1");
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed");

        ProblemDetails.decorate(CLOCK, problem, ErrorCode.MALFORMED_REQUEST);

        assertThat(problem.getTitle()).isEqualTo("Method Not Allowed");
        assertThat(problem.getProperties()).containsEntry("code", "MALFORMED_REQUEST")
                .containsEntry("correlationId", "req-1").containsKey("timestamp");
    }

    @Test
    void theInstanceIsTheRequestPath() {
        ProblemDetail problem = ProblemDetails.of(CLOCK, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHENTICATED, "x");

        ProblemDetails.withInstance(problem, "/api/v1/users/me");

        assertThat(problem.getInstance()).hasToString("/api/v1/users/me");
    }

    @Test
    void aPathThatIsNotAValidUriIsLeftOutInsteadOfBreakingTheErrorResponse() {
        ProblemDetail problem = ProblemDetails.of(CLOCK, HttpStatus.NOT_FOUND, ErrorCode.RESOURCE_NOT_FOUND, "x");

        ProblemDetails.withInstance(problem, "/api/v1/has space");
        ProblemDetails.withInstance(problem, null);

        assertThat(problem.getInstance()).isNull();
    }

    @ParameterizedTest
    @CsvSource({
            "400, MALFORMED_REQUEST", "401, UNAUTHENTICATED", "403, ACCESS_DENIED", "404, RESOURCE_NOT_FOUND",
            "405, MALFORMED_REQUEST", "415, MALFORMED_REQUEST", "500, INTERNAL_ERROR", "503, INTERNAL_ERROR"
    })
    void aStatusWithoutASpecificCodeGetsTheDefaultOne(int status, ErrorCode expected) {
        assertThat(ProblemDetails.defaultCodeFor(HttpStatus.valueOf(status))).isEqualTo(expected);
    }
}
