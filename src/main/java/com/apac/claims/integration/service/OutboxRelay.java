package com.apac.claims.integration.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.apac.claims.config.OutboxProperties;
import com.apac.claims.integration.domain.OutboxEvent;
import com.apac.claims.integration.domain.OutboxEventRepository;
import com.apac.claims.integration.domain.OutboxStatus;
import com.apac.claims.shared.event.DomainEvent;
import com.apac.claims.shared.event.DomainEventHandler;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivers the events of the outbox to the {@link DomainEventHandler} beans. {@link #processBatch()} takes the
 * PENDING rows whose time has come, oldest first, and handles each one in a transaction of its own, so a handler that
 * fails rolls back that event only and never stops the others. A successful event becomes PROCESSED. A failed one
 * gets its attempt counter, the error and the next try recorded in a <em>new</em> transaction (otherwise the counter
 * would be rolled back together with the failure), with a delay that doubles with every attempt (2, 4, 8, 16 seconds
 * ...); after {@code app.outbox.relay.max-attempts} attempts it becomes FAILED and stays for a person to look at.
 *
 * <p>The error that is stored is the exception class and its message, cut to 1000 characters; the payload of the
 * event is never copied into the error or the log, because it carries amounts and user ids.
 *
 * <p>Limits, on purpose: this is a <strong>single-instance</strong> relay (two instances would deliver the same row
 * twice; scaling out needs {@code SELECT ... FOR UPDATE SKIP LOCKED}); delivery is <strong>at least once</strong>, so
 * handlers must be idempotent; and the order is that of the batch, it is not guaranteed after a retry.
 */
@Service
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int MAX_ERROR_LENGTH = 1000;
    /** Keeps {@code 1L << attempts} far below overflow whatever the configuration says. */
    private static final int MAX_BACKOFF_SHIFT = 30;

    private final OutboxEventRepository outbox;
    private final ObjectProvider<DomainEventHandler> handlers;
    private final ObjectReader envelopeReader;
    private final OutboxProperties properties;
    private final Clock clock;
    private final TransactionTemplate perEvent;
    private final TransactionTemplate failureRecord;

    public OutboxRelay(OutboxEventRepository outbox, ObjectProvider<DomainEventHandler> handlers,
                       ObjectMapper objectMapper, OutboxProperties properties, Clock clock,
                       PlatformTransactionManager transactionManager) {
        this.outbox = outbox;
        this.handlers = handlers;
        // Amounts are read back as BigDecimal, so 45000.00 keeps its two decimal places.
        this.envelopeReader = objectMapper.readerFor(DomainEvent.class)
                .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        this.properties = properties;
        this.clock = clock;
        this.perEvent = newTransaction(transactionManager);
        this.failureRecord = newTransaction(transactionManager);
    }

    private static TransactionTemplate newTransaction(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    /**
     * Handles one batch of due events.
     *
     * @return the number of events that were delivered to every handler and marked PROCESSED
     */
    public int processBatch() {
        Instant now = Instant.now(clock);
        List<OutboxEvent> due = outbox.findDueEvents(OutboxStatus.PENDING, now,
                PageRequest.of(0, properties.relay().batchSize()));
        int processed = 0;
        for (OutboxEvent candidate : due) {
            if (deliver(candidate.getId(), candidate.getEventType(), candidate.getAggregateId())) {
                processed++;
            }
        }
        return processed;
    }

    private boolean deliver(String id, String type, String claimNumber) {
        try {
            Boolean handled = perEvent.execute(status -> handle(id));
            if (Boolean.TRUE.equals(handled)) {
                log.info("Outbox event processed type={} claim={}", type, claimNumber);
                return true;
            }
            return false;
        } catch (RuntimeException e) {
            recordFailure(id, type, claimNumber, e);
            return false;
        }
    }

    /** Runs inside the transaction of this event; a runtime exception rolls it back. */
    private boolean handle(String id) {
        OutboxEvent row = outbox.findById(id).orElse(null);
        if (row == null || row.getStatus() != OutboxStatus.PENDING) {
            return false;
        }
        DomainEvent event = read(row);
        for (DomainEventHandler handler : handlers.orderedStream().toList()) {
            handler.handle(event);
        }
        row.setStatus(OutboxStatus.PROCESSED);
        row.setProcessedAt(Instant.now(clock));
        outbox.save(row);
        return true;
    }

    private DomainEvent read(OutboxEvent row) {
        try {
            return envelopeReader.readValue(row.getPayload());
        } catch (JsonProcessingException e) {
            // getOriginalMessage() leaves out the source text, which would be the payload.
            throw new IllegalStateException("The envelope of outbox event " + row.getId() + " could not be read: "
                    + e.getOriginalMessage(), e);
        }
    }

    /** Counts the attempt, keeps the error and schedules the next try, in a transaction of its own. */
    private void recordFailure(String id, String type, String claimNumber, RuntimeException cause) {
        String error = describe(cause);
        try {
            failureRecord.executeWithoutResult(status -> {
                OutboxEvent row = outbox.findById(id).orElseThrow();
                int attempts = row.getAttempts() + 1;
                row.setAttempts(attempts);
                row.setLastError(error);
                if (attempts >= properties.relay().maxAttempts()) {
                    row.setStatus(OutboxStatus.FAILED);
                } else {
                    row.setNextAttemptAt(Instant.now(clock).plusSeconds(1L << Math.min(attempts, MAX_BACKOFF_SHIFT)));
                }
                outbox.save(row);
            });
        } catch (RuntimeException e) {
            log.error("Could not record the failure of outbox event type={} claim={}", type, claimNumber, e);
        }
        log.warn("Outbox event failed type={} claim={} error={}", type, claimNumber, error);
    }

    private static String describe(Throwable cause) {
        String message = cause.getMessage();
        String text = cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
    }
}
