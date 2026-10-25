package com.apac.claims.shared.exception;

/** The action is not allowed in the claim's current status (409). The message names both in plain words. */
public class InvalidTransitionException extends ConflictException {

    public InvalidTransitionException(String message) {
        super(ErrorCode.CLAIM_INVALID_TRANSITION, message);
    }
}
