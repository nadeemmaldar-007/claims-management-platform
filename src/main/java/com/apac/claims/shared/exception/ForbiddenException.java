package com.apac.claims.shared.exception;

import org.springframework.http.HttpStatus;

/**
 * The caller may see the resource but is not allowed to act on it, for example an officer who is not the assigned
 * officer (403). A resource the caller may not see at all is a {@link ResourceNotFoundException} (404) instead, so
 * that its existence is not revealed.
 */
public class ForbiddenException extends ApiException {

    public ForbiddenException(String message) {
        super(ErrorCode.ACCESS_DENIED, HttpStatus.FORBIDDEN, message);
    }
}
