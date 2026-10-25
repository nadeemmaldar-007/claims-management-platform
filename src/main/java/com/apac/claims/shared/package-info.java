/**
 * Shared kernel: the {@code ApiException} base class (error code and HTTP status), error codes,
 * {@code PageResponse}, {@code MoneyRules}, the domain-event contracts ({@code DomainEvent},
 * {@code DomainEventPublisher}, {@code DomainEventHandler}) and the correlation id filter.
 * The single {@code GlobalExceptionHandler} lives in {@code shared.api}, so it never needs to know feature modules.
 *
 * <p>May depend on: no other module.
 */
package com.apac.claims.shared;
