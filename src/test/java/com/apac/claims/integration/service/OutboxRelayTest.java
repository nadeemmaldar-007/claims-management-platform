package com.apac.claims.integration.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.apac.claims.config.OutboxProperties;
import com.apac.claims.integration.domain.OutboxEvent;
import com.apac.claims.integration.domain.OutboxEventRepository;
import com.apac.claims.integration.domain.OutboxStatus;
import com.apac.claims.shared.event.DomainEvent;
import com.apac.claims.shared.event.DomainEventHandler;
import com.apac.claims.support.OutboxBacklog;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * The relay against the real database, with a handler of the test that records what it receives and fails for the
 * aggregate ids the test names. The database is shared with every other test, so rows of other tests may be PENDING:
 * each test drains the relay until nothing is left and asserts only about the events it created itself.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(OutboxRelayTest.RecordingConfig.class)
class OutboxRelayTest {

    private static final String TEST_EVENT = "TestEvent";

    /** Remembers every event it is given, in order, and throws for the aggregate ids in {@link #failing}. */
    static class RecordingHandler implements DomainEventHandler {

        final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();
        final List<String> order = new CopyOnWriteArrayList<>();
        final Set<String> failing = ConcurrentHashMap.newKeySet();

        @Override
        public void handle(DomainEvent event) {
            if (!TEST_EVENT.equals(event.eventType())) {
                return;
            }
            calls.computeIfAbsent(event.aggregateId(), id -> new AtomicInteger()).incrementAndGet();
            order.add(event.aggregateId());
            if (failing.contains(event.aggregateId())) {
                throw new IllegalStateException("simulated handler failure for " + event.aggregateId());
            }
        }

        int callsFor(String aggregateId) {
            AtomicInteger count = calls.get(aggregateId);
            return count == null ? 0 : count.get();
        }
    }

    @TestConfiguration
    static class RecordingConfig {

        @Bean
        RecordingHandler recordingHandler() {
            return new RecordingHandler();
        }
    }

    @Autowired
    private OutboxRelay relay;
    @Autowired
    private OutboxEventRepository outbox;
    @Autowired
    private RecordingHandler handler;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private OutboxProperties properties;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void forgetEarlierCallsAndParkTheBacklog() {
        OutboxBacklog.park(jdbc);
        handler.calls.clear();
        handler.order.clear();
        handler.failing.clear();
    }

    private static String newClaimNumber() {
        return "CLM-T" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    /** A PENDING row of the test event type that is due now, or at {@code dueAt} when that is given. */
    private OutboxEvent pending(String claimNumber, Instant occurredAt, Instant dueAt) throws Exception {
        DomainEvent event = new DomainEvent(UUID.randomUUID().toString(), TEST_EVENT, "Claim", claimNumber, occurredAt,
                1, Map.of("claimNumber", claimNumber));
        OutboxEvent row = new OutboxEvent(event.eventId(), "Claim", claimNumber, TEST_EVENT,
                objectMapper.writeValueAsString(event), occurredAt);
        row.setNextAttemptAt(dueAt);
        return outbox.saveAndFlush(row);
    }

    /** Created an hour ago, so the test's events come before the rows other tests left behind. */
    private OutboxEvent pending(String claimNumber) throws Exception {
        Instant earlier = Instant.now().minus(Duration.ofHours(1));
        return pending(claimNumber, earlier, earlier);
    }

    /** Runs the relay until a batch delivers nothing, at most 50 times. */
    private void drain() {
        for (int loop = 0; loop < 50; loop++) {
            if (relay.processBatch() == 0) {
                return;
            }
        }
    }

    private OutboxEvent reload(OutboxEvent row) {
        return outbox.findById(row.getId()).orElseThrow();
    }

    @Test
    void aPendingEventReachesTheHandlerAndBecomesProcessed() throws Exception {
        String claim = newClaimNumber();
        OutboxEvent row = pending(claim);

        drain();

        OutboxEvent after = reload(row);
        assertThat(handler.callsFor(claim)).isEqualTo(1);
        assertThat(after.getStatus()).isEqualTo(OutboxStatus.PROCESSED);
        assertThat(after.getProcessedAt()).isNotNull();
        assertThat(after.getAttempts()).isZero();
        assertThat(after.getLastError()).isNull();
    }

    @Test
    void processBatchReturnsHowManyEventsWereDeliveredAndASecondRunDeliversNoneOfThem() throws Exception {
        String first = newClaimNumber();
        String second = newClaimNumber();
        pending(first);
        pending(second);

        int delivered = relay.processBatch();
        drain();

        assertThat(delivered).isGreaterThanOrEqualTo(2);
        assertThat(handler.callsFor(first)).isEqualTo(1);
        assertThat(handler.callsFor(second)).isEqualTo(1);
    }

    @Test
    void eventsAreDeliveredOldestFirst() throws Exception {
        String oldest = newClaimNumber();
        String middle = newClaimNumber();
        String newest = newClaimNumber();
        Instant base = Instant.now().minus(Duration.ofHours(3));
        // Saved in the opposite order, so the order of delivery can only come from the time of the event.
        pending(newest, base.plusSeconds(20), base.plusSeconds(20));
        pending(oldest, base, base);
        pending(middle, base.plusSeconds(10), base.plusSeconds(10));

        drain();

        List<String> mine = new ArrayList<>(handler.order);
        mine.retainAll(List.of(oldest, middle, newest));
        assertThat(mine).containsExactly(oldest, middle, newest);
    }

    @Test
    void aHandlerThatFailsLeavesTheEventPendingWithTheAttemptTheErrorAndALaterTry() throws Exception {
        String claim = newClaimNumber();
        handler.failing.add(claim);
        OutboxEvent row = pending(claim);
        Instant before = Instant.now();

        drain();

        OutboxEvent after = reload(row);
        assertThat(handler.callsFor(claim)).isEqualTo(1);
        assertThat(after.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(after.getAttempts()).isEqualTo(1);
        assertThat(after.getProcessedAt()).isNull();
        assertThat(after.getLastError()).contains("IllegalStateException").contains("simulated handler failure");
        // First failure: two seconds later.
        assertThat(after.getNextAttemptAt()).isAfterOrEqualTo(before.plusSeconds(2))
                .isBefore(Instant.now().plusSeconds(3));
    }

    @Test
    void theStoredErrorIsShortAndNeverHoldsThePayload() throws Exception {
        String claim = newClaimNumber();
        handler.failing.add(claim);
        OutboxEvent row = pending(claim);

        drain();

        String error = reload(row).getLastError();
        assertThat(error.length()).isLessThanOrEqualTo(1000);
        assertThat(error).doesNotContain("eventId").doesNotContain("\"payload\"");
    }

    @Test
    void theDelayBeforeTheNextTryDoublesWithEveryAttempt() throws Exception {
        String claim = newClaimNumber();
        handler.failing.add(claim);
        OutboxEvent row = pending(claim);

        long[] expectedSeconds = {2, 4, 8, 16};
        for (int attempt = 0; attempt < expectedSeconds.length; attempt++) {
            makeDueNow(row);
            Instant before = Instant.now();
            drain();
            Instant after = Instant.now();

            OutboxEvent current = reload(row);
            assertThat(current.getAttempts()).isEqualTo(attempt + 1);
            assertThat(current.getNextAttemptAt()).as("attempt %d", attempt + 1)
                    .isAfterOrEqualTo(before.plusSeconds(expectedSeconds[attempt]))
                    // The database keeps microseconds and rounds, so the stored time may be up to a microsecond later.
                    .isBeforeOrEqualTo(after.plusSeconds(expectedSeconds[attempt]).plus(1, ChronoUnit.MICROS));
        }
    }

    @Test
    void afterTheMaximumNumberOfAttemptsTheEventIsFailedAndNotTriedAgain() throws Exception {
        String claim = newClaimNumber();
        handler.failing.add(claim);
        OutboxEvent row = pending(claim);
        int max = properties.relay().maxAttempts();

        for (int attempt = 1; attempt <= max; attempt++) {
            makeDueNow(row);
            drain();
        }

        OutboxEvent failed = reload(row);
        assertThat(failed.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(failed.getAttempts()).isEqualTo(max);
        assertThat(failed.getLastError()).contains("simulated handler failure");
        int calls = handler.callsFor(claim);
        drain();
        assertThat(handler.callsFor(claim)).as("a FAILED event is not delivered again").isEqualTo(calls)
                .isEqualTo(max);
    }

    @Test
    void anEventThatIsNotDueYetIsSkipped() throws Exception {
        String claim = newClaimNumber();
        Instant earlier = Instant.now().minus(Duration.ofHours(1));
        OutboxEvent row = pending(claim, earlier, Instant.now().plus(Duration.ofHours(1)));

        drain();

        assertThat(handler.callsFor(claim)).isZero();
        OutboxEvent after = reload(row);
        assertThat(after.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(after.getAttempts()).isZero();
    }

    @Test
    void aFailingEventDoesNotStopTheNextOneInTheSameBatch() throws Exception {
        String failing = newClaimNumber();
        String healthy = newClaimNumber();
        handler.failing.add(failing);
        Instant base = Instant.now().minus(Duration.ofHours(2));
        OutboxEvent failingRow = pending(failing, base, base);
        OutboxEvent healthyRow = pending(healthy, base.plusSeconds(5), base.plusSeconds(5));

        int delivered = relay.processBatch();

        assertThat(delivered).isGreaterThanOrEqualTo(1);
        assertThat(reload(failingRow).getAttempts()).isEqualTo(1);
        assertThat(reload(failingRow).getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(reload(healthyRow).getStatus()).isEqualTo(OutboxStatus.PROCESSED);
        assertThat(handler.callsFor(healthy)).isEqualTo(1);
    }

    @Test
    void anEventThatCannotBeReadIsRetriedAndItsPayloadIsNotCopiedIntoTheError() throws Exception {
        String claim = newClaimNumber();
        Instant earlier = Instant.now().minus(Duration.ofHours(1));
        OutboxEvent broken = outbox.saveAndFlush(new OutboxEvent(UUID.randomUUID().toString(), "Claim", claim,
                TEST_EVENT, "this is not json {\"secretAmount\": \"123456\"}", earlier));

        drain();

        OutboxEvent after = reload(broken);
        assertThat(after.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(after.getAttempts()).isEqualTo(1);
        assertThat(after.getLastError()).contains("could not be read").doesNotContain("123456")
                .doesNotContain("secretAmount");
        assertThat(handler.callsFor(claim)).isZero();
    }

    @Test
    void anEventThatWasAlreadyProcessedIsNotDeliveredAgainUnlessItIsMadePendingAgain() throws Exception {
        String claim = newClaimNumber();
        OutboxEvent row = pending(claim);
        drain();
        drain();
        assertThat(handler.callsFor(claim)).isEqualTo(1);

        OutboxEvent again = reload(row);
        again.setStatus(OutboxStatus.PENDING);
        again.setProcessedAt(null);
        outbox.saveAndFlush(again);
        drain();

        assertThat(handler.callsFor(claim)).as("at least once: a reset row is delivered a second time").isEqualTo(2);
        assertThat(reload(row).getStatus()).isEqualTo(OutboxStatus.PROCESSED);
    }

    private void makeDueNow(OutboxEvent row) {
        OutboxEvent current = reload(row);
        current.setNextAttemptAt(Instant.now().minusSeconds(1));
        outbox.saveAndFlush(current);
    }
}
