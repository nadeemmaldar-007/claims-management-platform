package com.apac.claims.claim.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.apac.claims.claim.domain.ClaimCounterRepository;
import com.apac.claims.claim.domain.ClaimType;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The claim number generator against the real database with real, separate transactions. The counters are shared
 * with every other test in the run, so nothing here assumes a particular number: tests compare the counter before
 * and after, or numbers inside one test with each other.
 */
@SpringBootTest
@ActiveProfiles("test")
class ClaimNumberGeneratorTest {

    @Autowired
    private ClaimNumberGenerator generator;
    @Autowired
    private ClaimCounterRepository counters;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private long counterOf(ClaimType type) {
        return counters.findById(type).orElseThrow().getNextValue();
    }

    private static long numberOf(String claimNumber) {
        return Long.parseLong(claimNumber.substring(claimNumber.indexOf('-') + 1));
    }

    private String next(ClaimType type) {
        return transactionTemplate.execute(status -> generator.next(type));
    }

    @Test
    void motorNumbersLookLikeCLMAndPropertyNumbersLikeCLP() {
        assertThat(next(ClaimType.MOTOR)).matches("^CLM-\\d{5,}$");
        assertThat(next(ClaimType.PROPERTY)).matches("^CLP-\\d{5,}$");
    }

    @Test
    void numbersStartAtFiveDigitsAndTheCounterNeverFallsBelowItsSeed() {
        assertThat(counterOf(ClaimType.MOTOR)).isGreaterThanOrEqualTo(10001L);
        assertThat(counterOf(ClaimType.PROPERTY)).isGreaterThanOrEqualTo(10001L);
    }

    @Test
    void numbersInOneTransactionIncreaseByExactlyOne() {
        List<String> numbers = transactionTemplate.execute(status ->
                List.of(generator.next(ClaimType.MOTOR), generator.next(ClaimType.MOTOR),
                        generator.next(ClaimType.MOTOR)));

        assertThat(numberOf(numbers.get(1))).isEqualTo(numberOf(numbers.get(0)) + 1);
        assertThat(numberOf(numbers.get(2))).isEqualTo(numberOf(numbers.get(1)) + 1);
    }

    @Test
    void theNextNumberIsTheCounterValueAndTheCounterMovesOnByOne() {
        long before = counterOf(ClaimType.PROPERTY);

        String number = next(ClaimType.PROPERTY);

        assertThat(numberOf(number)).isEqualTo(before);
        assertThat(counterOf(ClaimType.PROPERTY)).isEqualTo(before + 1);
    }

    @Test
    void theTwoCountersAreIndependent() {
        long motorBefore = counterOf(ClaimType.MOTOR);
        long propertyBefore = counterOf(ClaimType.PROPERTY);

        transactionTemplate.executeWithoutResult(status -> {
            generator.next(ClaimType.MOTOR);
            generator.next(ClaimType.MOTOR);
            generator.next(ClaimType.PROPERTY);
        });

        assertThat(counterOf(ClaimType.MOTOR)).isEqualTo(motorBefore + 2);
        assertThat(counterOf(ClaimType.PROPERTY)).isEqualTo(propertyBefore + 1);
    }

    @Test
    void aRolledBackTransactionLeavesNoGap() {
        long before = counterOf(ClaimType.MOTOR);
        AtomicReference<String> rolledBack = new AtomicReference<>();

        transactionTemplate.executeWithoutResult(status -> {
            rolledBack.set(generator.next(ClaimType.MOTOR));
            status.setRollbackOnly();
        });

        assertThat(counterOf(ClaimType.MOTOR)).isEqualTo(before);
        // The number that was rolled back is handed out again, so the series has no hole.
        assertThat(next(ClaimType.MOTOR)).isEqualTo(rolledBack.get());
    }

    @Test
    void anExceptionInsideTheTransactionAlsoRollsTheCounterBack() {
        long before = counterOf(ClaimType.PROPERTY);

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            generator.next(ClaimType.PROPERTY);
            throw new IllegalStateException("the claim insert failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(counterOf(ClaimType.PROPERTY)).isEqualTo(before);
    }

    @Test
    void callingItWithoutATransactionIsRefused() {
        assertThatThrownBy(() -> generator.next(ClaimType.MOTOR)).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void aRefusedCallWithoutATransactionConsumesNoNumber() {
        long before = counterOf(ClaimType.MOTOR);

        assertThatThrownBy(() -> generator.next(ClaimType.MOTOR)).isInstanceOf(IllegalTransactionStateException.class);

        assertThat(counterOf(ClaimType.MOTOR)).isEqualTo(before);
    }

    @Test
    void eightThreadsGeneratingFiveNumbersEachGetFortyDistinctGapFreeNumbers() throws Exception {
        int threads = 8;
        int perThread = 5;
        long before = counterOf(ClaimType.MOTOR);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        List<String> numbers = Collections.synchronizedList(new ArrayList<>());
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    for (int i = 0; i < perThread; i++) {
                        // Each number is generated in its own transaction, which is how submissions behave.
                        numbers.add(next(ClaimType.MOTOR));
                    }
                    return null;
                }));
            }
            startGate.countDown();
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(numbers).hasSize(threads * perThread).doesNotHaveDuplicates();
        List<Long> sorted = numbers.stream().map(ClaimNumberGeneratorTest::numberOf).sorted().toList();
        assertThat(sorted.get(0)).isEqualTo(before);
        for (int i = 1; i < sorted.size(); i++) {
            assertThat(sorted.get(i)).as("number %d", i).isEqualTo(sorted.get(i - 1) + 1);
        }
        assertThat(counterOf(ClaimType.MOTOR)).isEqualTo(before + threads * perThread);
    }
}
