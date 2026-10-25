package com.apac.claims.shared.exception;

import java.util.List;

import org.springframework.http.HttpStatus;

/**
 * Validation that cannot be expressed as bean validation, for example a decimal scale that depends on the currency.
 * It carries per-field messages so that the error body has the same {@code errors} shape as bean validation.
 */
public class ValidationFailedException extends ApiException {

    /** One invalid field and the reason, as returned in the {@code errors} array. */
    public record FieldError(String field, String message) {
    }

    private final List<FieldError> errors;

    public ValidationFailedException(String message, List<FieldError> errors) {
        super(ErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, message);
        this.errors = List.copyOf(errors);
    }

    /** Convenience for the common case of a single invalid field. */
    public static ValidationFailedException forField(String field, String message) {
        return new ValidationFailedException("Request validation failed", List.of(new FieldError(field, message)));
    }

    public List<FieldError> getErrors() {
        return errors;
    }
}
