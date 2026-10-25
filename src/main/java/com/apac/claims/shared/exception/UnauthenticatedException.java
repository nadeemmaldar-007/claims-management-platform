package com.apac.claims.shared.exception;

import org.springframework.http.HttpStatus;

/** The caller is not authenticated, or the credentials they gave are not accepted (401). */
public class UnauthenticatedException extends ApiException {

    public UnauthenticatedException(String message) {
        super(ErrorCode.UNAUTHENTICATED, HttpStatus.UNAUTHORIZED, message);
    }
}
