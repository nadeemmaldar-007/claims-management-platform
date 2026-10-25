package com.apac.claims.shared.exception;

import org.springframework.http.HttpStatus;

/** An external system failed, for example the payment gateway (502). */
public class UpstreamServiceException extends ApiException {

    public UpstreamServiceException(ErrorCode errorCode, String message) {
        super(errorCode, HttpStatus.BAD_GATEWAY, message);
    }

    public UpstreamServiceException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, HttpStatus.BAD_GATEWAY, message, cause);
    }
}
