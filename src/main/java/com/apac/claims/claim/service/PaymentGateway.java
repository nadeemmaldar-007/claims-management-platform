package com.apac.claims.claim.service;

/**
 * Port to the system that pays an approved claim. The adapter in {@code integration.payment} simulates it. A call is
 * idempotent by claim number: asking again for the same claim returns the reference of the payment that was made, so a
 * retry after a failure somewhere else never pays twice. When the gateway cannot be reached the call throws a
 * {@link PaymentGatewayException}.
 */
public interface PaymentGateway {

    PaymentReceipt pay(PaymentRequest request);
}
