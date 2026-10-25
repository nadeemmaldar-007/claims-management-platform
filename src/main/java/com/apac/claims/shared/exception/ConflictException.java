package com.apac.claims.shared.exception;

import org.springframework.http.HttpStatus;

/** The request conflicts with the current state: invalid transition, duplicate claim or concurrent change (409). */
public class ConflictException extends ApiException {

    public ConflictException(ErrorCode errorCode, String message) {
        super(errorCode, HttpStatus.CONFLICT, message);
    }
}
