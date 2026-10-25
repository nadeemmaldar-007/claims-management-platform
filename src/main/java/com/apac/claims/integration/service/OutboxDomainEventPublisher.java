package com.apac.claims.integration.service;

import java.time.Clock;
import java.time.Instant;

import com.apac.claims.integration.domain.OutboxEvent;
import com.apac.claims.integration.domain.OutboxEventRepository;
import com.apac.claims.shared.event.DomainEvent;
import com.apac.claims.shared.event.DomainEventPublisher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes a domain event to the outbox table. It requires a transaction that is already running
 * ({@code MANDATORY}), so an event can never be written without the state change it belongs to, and a rollback
 * removes both. Delivery to handlers is a separate step done later by the relay.
 */
@Service
public class OutboxDomainEventPublisher implements DomainEventPublisher {

    /** The size of the {@code payload} column of {@code outbox_events}. */
    static final int MAX_ENVELOPE_LENGTH = 8000;

    private static final Logger log = LoggerFactory.getLogger(OutboxDomainEventPublisher.class);

    private final OutboxEventRepository outbox;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OutboxDomainEventPublisher(OutboxEventRepository outbox, ObjectMapper objectMapper, Clock clock) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Stores the whole envelope (event id, type, aggregate, time, version, payload) as JSON with status PENDING,
     * no attempts yet and an attempt time of now, so the relay picks it up at once.
     *
     * @throws IllegalStateException when the envelope cannot be serialised or is longer than the column
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(DomainEvent event) {
        String json = serialise(event);
        if (json.length() > MAX_ENVELOPE_LENGTH) {
            throw new IllegalStateException("Event " + event.eventType() + " is " + json.length()
                    + " characters long, which does not fit the " + MAX_ENVELOPE_LENGTH + "-character outbox column");
        }
        OutboxEvent row = new OutboxEvent(event.eventId(), event.aggregateType(), event.aggregateId(),
                event.eventType(), json, event.occurredAt());
        row.setNextAttemptAt(Instant.now(clock));
        outbox.save(row);
        // Only the type and the claim number are logged; the payload carries amounts and user ids.
        log.info("Outbox event written type={} aggregate={}", event.eventType(), event.aggregateId());
    }

    private String serialise(DomainEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Event " + event.eventType() + " could not be serialised", e);
        }
    }
}
