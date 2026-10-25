/**
 * Integration: the transactional outbox, the relay that delivers events to handlers, and the payment gateway
 * adapter that implements the claim module's {@code PaymentGateway} port.
 *
 * <p>May depend on: {@code shared}, {@code config}, {@code claim}
 * (it implements a port owned by {@code claim} and dispatches shared events).
 */
package com.apac.claims.integration;
