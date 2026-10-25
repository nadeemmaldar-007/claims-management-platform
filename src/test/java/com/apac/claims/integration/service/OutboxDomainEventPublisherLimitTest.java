package com.apac.claims.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import com.apac.claims.integration.domain.OutboxEvent;
import com.apac.claims.integration.domain.OutboxEventRepository;
import com.apac.claims.shared.event.DomainEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The exact boundary of the outbox column: an envelope of 8000 characters is stored, one of 8001 is refused. Padding
 * is added to a fixed event until the serialised length is exactly the wanted one, so the test does not depend on how
 * long a UUID or a timestamp happens to be.
 */
@ExtendWith(MockitoExtension.class)
class OutboxDomainEventPublisherLimitTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC);

    @Mock
    private OutboxEventRepository outbox;

    private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();

    private DomainEvent eventWithPadding(String eventId, int padding) {
        return new DomainEvent(eventId, "ClaimSubmitted", "Claim", "CLM-10001", Instant.now(CLOCK), 1,
                Map.of("padding", "x".repeat(padding)));
    }

    private int envelopeLength(DomainEvent event) throws Exception {
        return mapper.writeValueAsString(event).length();
    }

    @Test
    void anEnvelopeOfExactly8000CharactersIsStored() throws Exception {
        String id = "11111111-1111-1111-1111-111111111111";
        int padding = 8000 - envelopeLength(eventWithPadding(id, 0));
        DomainEvent event = eventWithPadding(id, padding);
        assertThat(envelopeLength(event)).isEqualTo(8000);

        new OutboxDomainEventPublisher(outbox, mapper, CLOCK).publish(event);

        ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(saved.capture());
        assertThat(saved.getValue().getPayload()).hasSize(8000);
    }

    @Test
    void anEnvelopeOf8001CharactersIsRefusedAndNothingIsSaved() throws Exception {
        String id = "22222222-2222-2222-2222-222222222222";
        int padding = 8001 - envelopeLength(eventWithPadding(id, 0));
        DomainEvent event = eventWithPadding(id, padding);
        assertThat(envelopeLength(event)).isEqualTo(8001);

        assertThatThrownBy(() -> new OutboxDomainEventPublisher(outbox, mapper, CLOCK).publish(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("8001")
                .hasMessageContaining("8000");

        verify(outbox, never()).save(any());
    }

    @Test
    void theAttemptTimeIsNowFromTheInjectedClock() {
        new OutboxDomainEventPublisher(outbox, mapper, CLOCK).publish(eventWithPadding("33333333-3333-3333-3333-333333333333", 1));

        ArgumentCaptor<OutboxEvent> saved = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(saved.capture());
        assertThat(saved.getValue().getNextAttemptAt()).isEqualTo(Instant.parse("2026-03-01T10:00:00Z"));
    }
}
