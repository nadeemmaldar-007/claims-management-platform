package com.apac.claims.integration.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.apac.claims.support.RepositoryTest;
import com.apac.claims.support.TestData;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;

@RepositoryTest
class OutboxEventRepositoryTest {

    @Autowired
    private OutboxEventRepository outbox;
    @Autowired
    private TestEntityManager entityManager;

    private Instant now;

    @BeforeEach
    void fixTheClock() {
        now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    }

    private OutboxEvent event(long occurredSecondsBeforeNow, OutboxStatus status, long nextAttemptSecondsFromNow) {
        OutboxEvent event = TestData.outboxEvent(now.minusSeconds(occurredSecondsBeforeNow));
        event.setStatus(status);
        event.setNextAttemptAt(now.plusSeconds(nextAttemptSecondsFromNow));
        return event;
    }

    // ---- Persistable: insert, not merge ------------------------------------------------------------------

    @Test
    void aNewEventKnowsItIsNewUntilItIsSaved() {
        OutboxEvent event = TestData.outboxEvent(now);
        assertThat(event.isNew()).isTrue();

        outbox.saveAndFlush(event);

        assertThat(event.isNew()).isFalse();
    }

    @Test
    void anEventLoadedFromTheDatabaseIsNotNew() {
        OutboxEvent saved = outbox.saveAndFlush(TestData.outboxEvent(now));
        entityManager.clear();

        assertThat(outbox.findById(saved.getId()).orElseThrow().isNew()).isFalse();
    }

    @Test
    void savingASecondNewEventWithTheSameIdFailsBecauseItIsAnInsertNotAMerge() {
        // With a merge the second save would silently overwrite the first; the duplicate key proves an INSERT.
        OutboxEvent first = outbox.saveAndFlush(TestData.outboxEvent(now));
        OutboxEvent second = new OutboxEvent(first.getId(), "Claim", "CLM-T99999999", "ClaimApproved", "{}", now);

        assertThrows(DataIntegrityViolationException.class, () -> outbox.saveAndFlush(second));
    }

    @Test
    void aNewEventStartsPendingWithZeroAttemptsAndIsDueImmediately() {
        OutboxEvent saved = outbox.saveAndFlush(TestData.outboxEvent(now));

        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(saved.getAttempts()).isZero();
        assertThat(saved.getNextAttemptAt()).isEqualTo(saved.getOccurredAt());
        assertThat(saved.getProcessedAt()).isNull();
        assertThat(saved.getLastError()).isNull();
    }

    // ---- due events query --------------------------------------------------------------------------------

    @Test
    void dueEventsAreThePendingOnesWhoseNextAttemptTimeHasPassedOldestFirst() {
        OutboxEvent newer = event(10, OutboxStatus.PENDING, -1);
        OutboxEvent older = event(30, OutboxStatus.PENDING, -1);
        OutboxEvent notYetDue = event(20, OutboxStatus.PENDING, 3600);
        OutboxEvent processed = event(40, OutboxStatus.PROCESSED, -1);
        OutboxEvent failed = event(50, OutboxStatus.FAILED, -1);
        outbox.saveAll(List.of(newer, older, notYetDue, processed, failed));
        outbox.flush();

        List<OutboxEvent> due = outbox.findDueEvents(OutboxStatus.PENDING, now, PageRequest.of(0, 10));

        assertThat(due).containsExactly(older, newer);
    }

    @Test
    void anEventWhoseNextAttemptTimeIsExactlyNowIsDue() {
        OutboxEvent onTheBoundary = outbox.saveAndFlush(event(5, OutboxStatus.PENDING, 0));

        assertThat(outbox.findDueEvents(OutboxStatus.PENDING, now, PageRequest.of(0, 10)))
                .containsExactly(onTheBoundary);
    }

    @Test
    void thePageableLimitsTheBatchSizeAndKeepsTheOldestEvents() {
        OutboxEvent oldest = event(30, OutboxStatus.PENDING, -1);
        OutboxEvent middle = event(20, OutboxStatus.PENDING, -1);
        OutboxEvent newest = event(10, OutboxStatus.PENDING, -1);
        outbox.saveAll(List.of(newest, oldest, middle));
        outbox.flush();

        assertThat(outbox.findDueEvents(OutboxStatus.PENDING, now, PageRequest.of(0, 2)))
                .containsExactly(oldest, middle);
    }

    @Test
    void anEventLeavesTheDueListOnceItIsProcessed() {
        OutboxEvent saved = outbox.saveAndFlush(event(5, OutboxStatus.PENDING, -1));
        assertThat(outbox.findDueEvents(OutboxStatus.PENDING, now, PageRequest.of(0, 10))).containsExactly(saved);

        saved.setStatus(OutboxStatus.PROCESSED);
        saved.setProcessedAt(now);
        outbox.saveAndFlush(saved);

        assertThat(outbox.findDueEvents(OutboxStatus.PENDING, now, PageRequest.of(0, 10))).isEmpty();
    }

    @Test
    void aRetryScheduledInTheFutureKeepsTheEventOutOfTheDueList() {
        OutboxEvent saved = outbox.saveAndFlush(event(5, OutboxStatus.PENDING, -1));

        saved.setAttempts(1);
        saved.setLastError("handler failed");
        saved.setNextAttemptAt(now.plusSeconds(2));
        outbox.saveAndFlush(saved);

        assertThat(outbox.findDueEvents(OutboxStatus.PENDING, now, PageRequest.of(0, 10))).isEmpty();
        assertThat(outbox.findDueEvents(OutboxStatus.PENDING, now.plusSeconds(2), PageRequest.of(0, 10)))
                .containsExactly(saved);
    }

    // ---- payload size ------------------------------------------------------------------------------------

    @Test
    void aPayloadOfEightThousandCharactersFits() {
        String payload = "x".repeat(8000);
        OutboxEvent event = new OutboxEvent(TestData.eventId(), "Claim", "CLM-T00000001", "ClaimSubmitted", payload,
                now);

        OutboxEvent saved = outbox.saveAndFlush(event);
        entityManager.clear();

        assertThat(outbox.findById(saved.getId()).orElseThrow().getPayload()).hasSize(8000);
    }

    @Test
    void aPayloadLongerThanEightThousandCharactersIsRefused() {
        OutboxEvent event = new OutboxEvent(TestData.eventId(), "Claim", "CLM-T00000001", "ClaimSubmitted",
                "x".repeat(8001), now);

        assertThrows(DataIntegrityViolationException.class, () -> outbox.saveAndFlush(event));
    }
}
