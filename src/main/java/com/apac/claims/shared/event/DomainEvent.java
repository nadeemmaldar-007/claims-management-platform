package com.apac.claims.shared.event;

import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Envelope of a domain event as stored in the outbox and handed to handlers. The {@code eventId} is the
 * deduplication key for consumers, because delivery is at least once.
 *
 * @param version schema version of the envelope, currently 1
 * @param payload event data in insertion order; unmodifiable
 */
public record DomainEvent(String eventId, String eventType, String aggregateType, String aggregateId,
                          Instant occurredAt, int version, Map<String, Object> payload) {

    /** Current envelope schema version. */
    public static final int CURRENT_VERSION = 1;

    public DomainEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        // A linked copy keeps the payload order stable in the JSON and tolerates null values from callers.
        payload = payload == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    /** Creates an event with a new UUID, the current time and version 1. */
    public static DomainEvent of(String eventType, String aggregateType, String aggregateId,
                                 Map<String, Object> payload) {
        return of(Clock.systemUTC(), eventType, aggregateType, aggregateId, payload);
    }

    /** Same as {@link #of(String, String, String, Map)} with an explicit clock, so tests control the time. */
    public static DomainEvent of(Clock clock, String eventType, String aggregateType, String aggregateId,
                                 Map<String, Object> payload) {
        return new DomainEvent(UUID.randomUUID().toString(), eventType, aggregateType, aggregateId,
                Instant.now(clock), CURRENT_VERSION, payload);
    }
}
