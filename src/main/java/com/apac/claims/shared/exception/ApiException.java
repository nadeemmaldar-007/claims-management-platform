package com.apac.claims.shared.exception;

import java.util.Objects;

import org.springframework.http.HttpStatus;

/**
 * Base class of every business exception in every module. It carries the error code and HTTP status,
 * so the single global exception handler can map any failure without knowing the feature modules.
 */
public abstract class ApiException extends RuntimeException {

    private final ErrorCode errorCode;
    private final HttpStatus status;

    protected ApiException(ErrorCode errorCode, HttpStatus status, String message) {
        this(errorCode, status, message, null);
    }

    protected ApiException(ErrorCode errorCode, HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
        this.status = Objects.requireNonNull(status, "status");
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
