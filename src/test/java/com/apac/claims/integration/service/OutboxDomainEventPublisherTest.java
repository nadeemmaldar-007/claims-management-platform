package com.apac.claims.integration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import com.apac.claims.integration.domain.OutboxEvent;
import com.apac.claims.integration.domain.OutboxEventRepository;
import com.apac.claims.integration.domain.OutboxStatus;
import com.apac.claims.shared.event.DomainEvent;
import com.apac.claims.shared.event.DomainEventPublisher;
import com.apac.claims.support.TestData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The write side of the outbox against the real database: what is stored, that it is stored only together with the
 * caller's own transaction, and that it disappears with a rollback.
 */
@SpringBootTest
@ActiveProfiles("test")
class OutboxDomainEventPublisherTest {

    @Autowired
    private DomainEventPublisher publisher;
    @Autowired
    private OutboxEventRepository outbox;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    private static DomainEvent eventFor(String claimNumber, Map<String, Object> payload) {
        return DomainEvent.of("ClaimSubmitted", "Claim", claimNumber, payload);
    }

    private static String uniqueClaimNumber() {
        return "CLM-T" + TestData.unique().substring(0, 8);
    }

    private void publishInTransaction(DomainEvent event) {
        transactionTemplate.executeWithoutResult(status -> publisher.publish(event));
    }

    @Test
    void insideATransactionAPendingRowIsWrittenWithTheEventId() {
        DomainEvent event = eventFor(uniqueClaimNumber(), Map.of("status", "SUBMITTED"));
        Instant before = Instant.now();

        publishInTransaction(event);

        OutboxEvent row = outbox.findById(event.eventId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getProcessedAt()).isNull();
        assertThat(row.getLastError()).isNull();
        assertThat(row.getNextAttemptAt()).isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));
        assertThat(row.getEventType()).isEqualTo("ClaimSubmitted");
        assertThat(row.getAggregateType()).isEqualTo("Claim");
        assertThat(row.getAggregateId()).isEqualTo(event.aggregateId());
    }

    @Test
    void theStoredEnvelopeIsParseableJsonWithEveryRequiredField() throws Exception {
        DomainEvent event = eventFor(uniqueClaimNumber(), Map.of("claimType", "MOTOR", "marketCode", "IN"));

        publishInTransaction(event);

        JsonNode envelope = objectMapper.readTree(outbox.findById(event.eventId()).orElseThrow().getPayload());
        assertThat(envelope.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("eventId", "eventType", "aggregateType", "aggregateId", "occurredAt",
                        "version", "payload");
        assertThat(envelope.get("eventId").asText()).isEqualTo(event.eventId());
        assertThat(envelope.get("eventType").asText()).isEqualTo("ClaimSubmitted");
        assertThat(envelope.get("aggregateType").asText()).isEqualTo("Claim");
        assertThat(envelope.get("aggregateId").asText()).isEqualTo(event.aggregateId());
        assertThat(envelope.get("version").asInt()).isEqualTo(1);
        assertThat(envelope.get("payload").get("claimType").asText()).isEqualTo("MOTOR");
        assertThat(envelope.get("payload").get("marketCode").asText()).isEqualTo("IN");
    }

    @Test
    void instantsAreIso8601TextAndNotEpochNumbers() throws Exception {
        DomainEvent event = eventFor(uniqueClaimNumber(), Map.of());

        publishInTransaction(event);

        JsonNode occurredAt = objectMapper.readTree(outbox.findById(event.eventId()).orElseThrow().getPayload())
                .get("occurredAt");
        assertThat(occurredAt.isTextual()).isTrue();
        assertThat(Instant.parse(occurredAt.asText())).isEqualTo(event.occurredAt());
    }

    @Test
    void amountsAreWrittenAsJsonNumbersWithTheirScale() throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("claimedAmount", new BigDecimal("45000.00"));
        DomainEvent event = eventFor(uniqueClaimNumber(), payload);

        publishInTransaction(event);

        String stored = outbox.findById(event.eventId()).orElseThrow().getPayload();
        JsonNode amount = objectMapper.readTree(stored).get("payload").get("claimedAmount");
        assertThat(amount.isNumber()).isTrue();
        assertThat(stored).contains("\"claimedAmount\":45000.00");
    }

    @Test
    void outsideATransactionItIsRefusedAndNothingIsWritten() {
        DomainEvent event = eventFor(uniqueClaimNumber(), Map.of());

        assertThatThrownBy(() -> publisher.publish(event)).isInstanceOf(IllegalTransactionStateException.class);

        assertThat(outbox.findById(event.eventId())).isEmpty();
    }

    @Test
    void aRolledBackTransactionTakesTheEventWithIt() {
        DomainEvent event = eventFor(uniqueClaimNumber(), Map.of());

        transactionTemplate.executeWithoutResult(status -> {
            publisher.publish(event);
            status.setRollbackOnly();
        });

        assertThat(outbox.findById(event.eventId())).isEmpty();
    }

    @Test
    void anOversizePayloadFailsAndLeavesNoRow() {
        DomainEvent event = eventFor(uniqueClaimNumber(), Map.of("notes", "x".repeat(9000)));

        assertThatThrownBy(() -> publishInTransaction(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("8000");

        assertThat(outbox.findById(event.eventId())).isEmpty();
    }

    @Test
    void severalEventsInOneTransactionAreAllWritten() {
        String claimNumber = uniqueClaimNumber();
        DomainEvent first = DomainEvent.of("ClaimSubmitted", "Claim", claimNumber, Map.of());
        DomainEvent second = DomainEvent.of("ClaimAssigned", "Claim", claimNumber, Map.of());

        transactionTemplate.executeWithoutResult(status -> {
            publisher.publish(first);
            publisher.publish(second);
        });

        assertThat(outbox.findById(first.eventId())).isPresent();
        assertThat(outbox.findById(second.eventId())).isPresent();
        assertThat(first.eventId()).isNotEqualTo(second.eventId());
    }
}
