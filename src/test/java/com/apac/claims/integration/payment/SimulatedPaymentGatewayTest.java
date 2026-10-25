package com.apac.claims.integration.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.apac.claims.claim.domain.PaymentMethod;
import com.apac.claims.claim.service.PaymentGateway;
import com.apac.claims.claim.service.PaymentReceipt;
import com.apac.claims.claim.service.PaymentRequest;
import com.apac.claims.integration.domain.PaymentInstruction;
import com.apac.claims.integration.domain.PaymentInstructionRepository;
import com.apac.claims.integration.domain.PaymentStatus;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The simulated payment gateway against the real database, with the failure flag off. Every test pays its own claim
 * number, so the tests do not depend on each other.
 */
@SpringBootTest
@ActiveProfiles("test")
class SimulatedPaymentGatewayTest {

    @Autowired
    private PaymentGateway gateway;
    @Autowired
    private PaymentInstructionRepository instructions;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private static String newClaimNumber() {
        return "CLM-P" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    private static PaymentRequest request(String claimNumber, String amount) {
        return new PaymentRequest(claimNumber, new BigDecimal(amount), "INR", PaymentMethod.BANK_TRANSFER);
    }

    private long rowsOf(String claimNumber) {
        return instructions.findAll().stream().filter(row -> claimNumber.equals(row.getClaimNumber())).count();
    }

    @Test
    void theReferenceIsPayAndTwelveUpperCaseHexadecimalCharacters() {
        PaymentReceipt receipt = gateway.pay(request(newClaimNumber(), "43000.00"));

        assertThat(receipt.reference()).matches("^PAY-[0-9A-F]{12}$");
    }

    @Test
    void thePaymentIsStoredWithTheClaimTheAmountTheMethodAndTheStatusCompleted() {
        String claim = newClaimNumber();

        PaymentReceipt receipt = gateway.pay(new PaymentRequest(claim, new BigDecimal("39000.00"), "INR",
                PaymentMethod.CHEQUE));

        PaymentInstruction stored = instructions.findByClaimNumber(claim).orElseThrow();
        assertThat(stored.getReference()).isEqualTo(receipt.reference());
        assertThat(stored.getAmount()).isEqualByComparingTo("39000.00");
        assertThat(stored.getCurrencyCode()).isEqualTo("INR");
        assertThat(stored.getPaymentMethod()).isEqualTo(PaymentMethod.CHEQUE);
        assertThat(stored.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
    }

    @Test
    void theSameClaimNumberTwiceGivesTheSameReferenceAndOneRowEvenWithAnotherAmount() {
        String claim = newClaimNumber();

        PaymentReceipt first = gateway.pay(request(claim, "43000.00"));
        PaymentReceipt second = gateway.pay(request(claim, "1.00"));

        assertThat(second.reference()).isEqualTo(first.reference());
        assertThat(rowsOf(claim)).isEqualTo(1);
        assertThat(instructions.findByClaimNumber(claim).orElseThrow().getAmount()).isEqualByComparingTo("43000.00");
    }

    @Test
    void differentClaimsGetDifferentReferences() {
        PaymentReceipt one = gateway.pay(request(newClaimNumber(), "100.00"));
        PaymentReceipt two = gateway.pay(request(newClaimNumber(), "100.00"));

        assertThat(one.reference()).isNotEqualTo(two.reference());
    }

    @Test
    void thePaymentSurvivesTheRollbackOfTheTransactionThatAskedForIt() {
        String claim = newClaimNumber();
        TransactionTemplate callersTransaction = new TransactionTemplate(transactionManager);

        PaymentReceipt[] inside = new PaymentReceipt[1];
        callersTransaction.executeWithoutResult(status -> {
            inside[0] = gateway.pay(request(claim, "43000.00"));
            status.setRollbackOnly();
        });

        // The claim transaction was rolled back, the payment (an outside system) was not.
        assertThat(instructions.findByClaimNumber(claim)).isPresent();
        assertThat(gateway.pay(request(claim, "43000.00")).reference()).isEqualTo(inside[0].reference());
    }

    @Test
    void twoRequestsForTheSameClaimAtTheSameMomentGetTheSameReferenceAndOneRow() throws Exception {
        String claim = newClaimNumber();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<PaymentReceipt>> futures = List.of(
                    pool.submit(() -> {
                        ready.countDown();
                        go.await();
                        return gateway.pay(request(claim, "43000.00"));
                    }),
                    pool.submit(() -> {
                        ready.countDown();
                        go.await();
                        return gateway.pay(request(claim, "43000.00"));
                    }));
            ready.await();
            go.countDown();

            PaymentReceipt first = futures.get(0).get(60, TimeUnit.SECONDS);
            PaymentReceipt second = futures.get(1).get(60, TimeUnit.SECONDS);

            assertThat(first.reference()).isEqualTo(second.reference());
            assertThat(rowsOf(claim)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
