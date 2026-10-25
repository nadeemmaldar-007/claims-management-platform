package com.apac.claims.integration.domain;

/** State of a payment instruction held by the payment gateway adapter. */
public enum PaymentStatus {
    INITIATED,
    COMPLETED,
    FAILED
}
