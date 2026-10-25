package com.apac.claims.shared.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class DomainEventTest {

    @Test
    void factoryFillsTheEnvelopeFields() {
        Instant before = Instant.now();

        DomainEvent event = DomainEvent.of("ClaimSubmitted", "Claim", "CLM-10001", Map.of("status", "SUBMITTED"));

        assertThat(event.eventType()).isEqualTo("ClaimSubmitted");
        assertThat(event.aggregateType()).isEqualTo("Claim");
        assertThat(event.aggregateId()).isEqualTo("CLM-10001");
        assertThat(event.version()).isEqualTo(1);
        assertThat(event.payload()).containsEntry("status", "SUBMITTED");
        assertThat(event.occurredAt()).isBetween(before, Instant.now());
    }

    @Test
    void eventIdIsAUuid() {
        DomainEvent event = DomainEvent.of("ClaimSubmitted", "Claim", "CLM-10001", Map.of());

        assertThat(UUID.fromString(event.eventId()).toString()).isEqualTo(event.eventId());
    }

    @Test
    void everyEventGetsItsOwnId() {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            ids.add(DomainEvent.of("ClaimSubmitted", "Claim", "CLM-10001", Map.of()).eventId());
        }

        assertThat(ids).hasSize(100);
    }

    @Test
    void anExplicitClockControlsTheTimestamp() {
        Instant fixed = Instant.parse("2026-01-15T10:30:00Z");
        Clock clock = Clock.fixed(fixed, ZoneOffset.UTC);

        DomainEvent event = DomainEvent.of(clock, "ClaimApproved", "Claim", "CLM-10002", Map.of());

        assertThat(event.occurredAt()).isEqualTo(fixed);
    }

    @Test
    void payloadKeepsTheOrderItWasGiven() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("zeta", 1);
        payload.put("alpha", 2);
        payload.put("mid", 3);

        DomainEvent event = DomainEvent.of("ClaimSubmitted", "Claim", "CLM-10001", payload);

        assertThat(event.payload().keySet()).containsExactly("zeta", "alpha", "mid");
    }

    @Test
    void payloadCannotBeModifiedAfterwards() {
        DomainEvent event = DomainEvent.of("ClaimSubmitted", "Claim", "CLM-10001", Map.of("a", 1));

        assertThatThrownBy(() -> event.payload().put("b", 2)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void laterChangesToTheCallersMapDoNotLeakIntoTheEvent() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("a", 1);

        DomainEvent event = DomainEvent.of("ClaimSubmitted", "Claim", "CLM-10001", payload);
        payload.put("b", 2);

        assertThat(event.payload()).containsOnlyKeys("a");
    }

    @Test
    void aNullPayloadBecomesAnEmptyOne() {
        DomainEvent event = DomainEvent.of("ClaimSubmitted", "Claim", "CLM-10001", null);

        assertThat(event.payload()).isEmpty();
    }

    @Test
    void requiredEnvelopeFieldsMustNotBeNull() {
        assertThatThrownBy(() -> DomainEvent.of(null, "Claim", "CLM-10001", Map.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> DomainEvent.of("ClaimSubmitted", null, "CLM-10001", Map.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> DomainEvent.of("ClaimSubmitted", "Claim", null, Map.of()))
                .isInstanceOf(NullPointerException.class);
    }
}
