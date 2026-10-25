package com.apac.claims.support.web;

import java.sql.SQLException;
import java.util.List;

import com.apac.claims.claim.domain.Claim;
import com.apac.claims.shared.exception.BusinessRuleException;
import com.apac.claims.shared.exception.ConflictException;
import com.apac.claims.shared.exception.DuplicateResourceException;
import com.apac.claims.shared.exception.ErrorCode;
import com.apac.claims.shared.exception.ResourceNotFoundException;
import com.apac.claims.shared.exception.UnauthenticatedException;
import com.apac.claims.shared.exception.UpstreamServiceException;
import com.apac.claims.shared.exception.ValidationFailedException;
import com.apac.claims.shared.exception.ValidationFailedException.FieldError;
import com.apac.claims.shared.security.Authz;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only controller that throws each kind of exception, so the global exception handler can be tested end to end.
 * It lives under {@code src/test/java}, is only active under the {@code error-contract-test} profile and is hidden
 * from the OpenAPI document, so it can never be shipped or leak into another test.
 */
@RestController
@Hidden
@Profile("error-contract-test")
@RequestMapping("/api/v1/_test")
public class ErrorContractTestController {

    /** Values for the enum field of {@link EchoRequest}. */
    public enum Kind {
        A, B
    }

    /** A request body with two constraints and an enum, to provoke validation and malformed-body errors. */
    public record EchoRequest(@NotBlank String name, @Min(1) int count, Kind kind) {
    }

    @GetMapping("/throw/{type}")
    public String throwIt(@PathVariable String type) {
        switch (type) {
            case "resource-not-found" -> throw ResourceNotFoundException.of("Widget", "42");
            case "duplicate-resource" -> throw new DuplicateResourceException("Widget already exists");
            case "conflict" -> throw new ConflictException(ErrorCode.CLAIM_INVALID_TRANSITION, "Not allowed now");
            case "business-rule" -> throw new BusinessRuleException(ErrorCode.APPROVAL_LIMIT_EXCEEDED, "Escalate");
            case "validation" -> throw new ValidationFailedException("Request validation failed",
                    List.of(new FieldError("name", "must not be blank"), new FieldError("count", "must be positive")));
            case "upstream" -> throw new UpstreamServiceException(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE,
                    "Payment gateway unavailable");
            case "unauthenticated" -> throw new UnauthenticatedException("Please log in");
            case "access-denied" -> throw new AccessDeniedException("internal detail that must not be shown");
            case "authentication" -> throw new BadCredentialsException("internal detail that must not be shown");
            case "optimistic-lock" -> throw new ObjectOptimisticLockingFailureException(Claim.class, 1L);
            case "data-integrity" -> throw new DataIntegrityViolationException(
                    "could not execute statement [insert into app_users (...)]; constraint [uq_app_users_user_id]",
                    new SQLException("Unique index or primary key violation: uq_app_users_user_id"));
            case "unexpected" -> throw new IllegalStateException("password=hunter2 must never reach the client");
            default -> {
                return "no exception: " + type;
            }
        }
    }

    @PostMapping("/echo")
    public EchoRequest echo(@Valid @RequestBody EchoRequest request) {
        return request;
    }

    @GetMapping("/needs-param")
    public String needsParam(@RequestParam("x") String x) {
        return x;
    }

    @GetMapping("/number/{id}")
    public String number(@PathVariable("id") int id) {
        return "id " + id;
    }

    @GetMapping("/manager-only")
    @PreAuthorize(Authz.MANAGER)
    public String managerOnly() {
        return "manager area";
    }

    @GetMapping("/only-get")
    public String onlyGet() {
        return "get works";
    }
}
