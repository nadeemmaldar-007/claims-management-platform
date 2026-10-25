package com.apac.claims.shared.exception;

/**
 * Machine-readable error codes returned in the {@code code} property of every error response.
 * The list is part of the API contract, so a code is never renamed once clients can depend on it.
 */
public enum ErrorCode {
    VALIDATION_FAILED,
    MALFORMED_REQUEST,
    UNAUTHENTICATED,
    ACCESS_DENIED,
    RESOURCE_NOT_FOUND,
    DUPLICATE_RESOURCE,
    CLAIM_INVALID_TRANSITION,
    CLAIM_ALREADY_ASSIGNED,
    CONCURRENT_MODIFICATION,
    DUPLICATE_CLAIM,
    POLICY_NOT_FOUND,
    POLICY_NOT_ACTIVE,
    POLICY_TYPE_MISMATCH,
    POLICY_MARKET_MISMATCH,
    AMOUNT_EXCEEDS_SUM_INSURED,
    ASSESSMENT_REQUIRED,
    APPROVAL_LIMIT_EXCEEDED,
    INFORMATION_REQUEST_NOT_OPEN,
    PAYMENT_GATEWAY_UNAVAILABLE,
    INTERNAL_ERROR
}
