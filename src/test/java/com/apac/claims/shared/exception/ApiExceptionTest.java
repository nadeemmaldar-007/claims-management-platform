package com.apac.claims.shared.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ApiExceptionTest {

    @Test
    void resourceNotFoundIs404WithItsOwnCode() {
        ResourceNotFoundException e = new ResourceNotFoundException("Claim not found");

        assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(e).hasMessage("Claim not found");
    }

    @Test
    void resourceNotFoundFactoryBuildsTheMessageFromTypeAndIdentifier() {
        ResourceNotFoundException e = ResourceNotFoundException.of("Claim", "CLM-10001");

        assertThat(e).hasMessage("Claim 'CLM-10001' not found");
    }

    @Test
    void duplicateResourceIs409WithItsOwnCode() {
        DuplicateResourceException e = new DuplicateResourceException("User id already in use");

        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE);
    }

    @Test
    void conflictIs409AndKeepsTheCodeItWasGiven() {
        ConflictException e = new ConflictException(ErrorCode.CLAIM_INVALID_TRANSITION, "Not allowed from APPROVED");

        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CLAIM_INVALID_TRANSITION);
        assertThat(e).hasMessage("Not allowed from APPROVED");
    }

    @Test
    void businessRuleIs422AndKeepsTheCodeItWasGiven() {
        BusinessRuleException e = new BusinessRuleException(ErrorCode.APPROVAL_LIMIT_EXCEEDED, "Escalate");

        assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.APPROVAL_LIMIT_EXCEEDED);
    }

    @Test
    void upstreamFailureIs502AndKeepsItsCause() {
        IllegalStateException cause = new IllegalStateException("gateway down");

        UpstreamServiceException e = new UpstreamServiceException(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE,
                "Payment gateway unavailable", cause);

        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE);
        assertThat(e.getCause()).isSameAs(cause);
    }

    @Test
    void upstreamFailureWithoutCauseHasNoCause() {
        UpstreamServiceException e = new UpstreamServiceException(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE, "down");

        assertThat(e.getCause()).isNull();
    }

    @Test
    void validationFailedIs400WithFieldErrors() {
        ValidationFailedException e = ValidationFailedException.forField("claimedAmount", "must be positive");

        assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(e.getErrors()).containsExactly(
                new ValidationFailedException.FieldError("claimedAmount", "must be positive"));
    }

    @Test
    void validationFailedCopiesAndFreezesItsErrorList() {
        List<ValidationFailedException.FieldError> source = new ArrayList<>();
        source.add(new ValidationFailedException.FieldError("a", "bad"));

        ValidationFailedException e = new ValidationFailedException("Request validation failed", source);
        source.add(new ValidationFailedException.FieldError("b", "bad"));

        assertThat(e.getErrors()).hasSize(1);
        assertThatThrownBy(() -> e.getErrors().add(new ValidationFailedException.FieldError("c", "bad")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void everyBusinessExceptionIsAnApiException() {
        assertThat(ApiException.class).isAssignableFrom(ResourceNotFoundException.class);
        assertThat(ApiException.class).isAssignableFrom(DuplicateResourceException.class);
        assertThat(ApiException.class).isAssignableFrom(ConflictException.class);
        assertThat(ApiException.class).isAssignableFrom(BusinessRuleException.class);
        assertThat(ApiException.class).isAssignableFrom(ValidationFailedException.class);
        assertThat(ApiException.class).isAssignableFrom(UpstreamServiceException.class);
        assertThat(RuntimeException.class).isAssignableFrom(ApiException.class);
    }

    @Test
    void anInvalidTransitionIs409WithTheInvalidTransitionCode() {
        InvalidTransitionException e = new InvalidTransitionException("A claim that is approved cannot be picked up.");

        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CLAIM_INVALID_TRANSITION);
        assertThat(e).isInstanceOf(ConflictException.class);
    }

    @Test
    void aClaimThatIsAlreadyAssignedIs409WithItsOwnCode() {
        ClaimAlreadyAssignedException e = new ClaimAlreadyAssignedException("Another officer took this claim first.");

        assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CLAIM_ALREADY_ASSIGNED);
    }

    @Test
    void forbiddenIs403WithTheAccessDeniedCodeAndIsNotAnAuthenticationFailure() {
        ForbiddenException e = new ForbiddenException("Only the assigned officer may do this.");

        assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ACCESS_DENIED);
        assertThat(e).isInstanceOf(ApiException.class).isNotInstanceOf(UnauthenticatedException.class);
    }

    @Test
    void unauthenticatedIs401WithTheUnauthenticatedCode() {
        UnauthenticatedException e = new UnauthenticatedException("Please log in");

        assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNAUTHENTICATED);
    }

    @Test
    void anErrorCodeIsMandatory() {
        assertThatThrownBy(() -> new ConflictException(null, "no code")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void errorCodesAreExactlyTheOnesInTheApiContract() {
        List<String> names = Arrays.stream(ErrorCode.values()).map(Enum::name).toList();

        assertThat(names).containsExactly(
                "VALIDATION_FAILED", "MALFORMED_REQUEST", "UNAUTHENTICATED", "ACCESS_DENIED",
                "RESOURCE_NOT_FOUND", "DUPLICATE_RESOURCE", "CLAIM_INVALID_TRANSITION", "CLAIM_ALREADY_ASSIGNED",
                "CONCURRENT_MODIFICATION", "DUPLICATE_CLAIM", "POLICY_NOT_FOUND", "POLICY_NOT_ACTIVE",
                "POLICY_TYPE_MISMATCH", "POLICY_MARKET_MISMATCH", "AMOUNT_EXCEEDS_SUM_INSURED",
                "ASSESSMENT_REQUIRED", "APPROVAL_LIMIT_EXCEEDED", "INFORMATION_REQUEST_NOT_OPEN",
                "PAYMENT_GATEWAY_UNAVAILABLE", "INTERNAL_ERROR");
    }
}
