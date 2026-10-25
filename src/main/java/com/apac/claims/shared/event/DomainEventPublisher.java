package com.apac.claims.shared.event;

/**
 * Port used by services to raise a domain event. The implementation (in the integration module) must write the
 * event inside the caller's transaction, so a rollback removes the state change and the event together.
 */
public interface DomainEventPublisher {

    void publish(DomainEvent event);
}
