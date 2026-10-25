package com.apac.claims.integration.domain;

/** Delivery state of an outbox event: waiting, delivered to every handler, or given up after too many attempts. */
public enum OutboxStatus {
    PENDING,
    PROCESSED,
    FAILED
}
