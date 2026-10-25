package com.apac.claims.shared.exception;

/** Someone else took the claim first, or it is no longer waiting in the queue (409). */
public class ClaimAlreadyAssignedException extends ConflictException {

    public ClaimAlreadyAssignedException(String message) {
        super(ErrorCode.CLAIM_ALREADY_ASSIGNED, message);
    }
}
