package com.apac.claims.shared.exception;

import org.springframework.http.HttpStatus;

/** A unique value (for example a user id or an email) is already in use. */
public class DuplicateResourceException extends ApiException {

    public DuplicateResourceException(String message) {
        super(ErrorCode.DUPLICATE_RESOURCE, HttpStatus.CONFLICT, message);
    }
}
