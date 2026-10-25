package com.apac.claims.shared.event;

/**
 * Implemented by modules that react to domain events. Delivery is at least once, so an implementation must be
 * idempotent: handling the same event twice must have no extra effect.
 */
public interface DomainEventHandler {

    void handle(DomainEvent event);
}
