package com.apac.claims.integration.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import com.apac.claims.claim.domain.PaymentMethod;
import com.apac.claims.support.RepositoryTest;
import com.apac.claims.support.TestData;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

@RepositoryTest
class PaymentInstructionRepositoryTest {

    @Autowired
    private PaymentInstructionRepository payments;
    @Autowired
    private TestEntityManager entityManager;

    @Test
    void findByClaimNumberReturnsTheInstructionWithItsReference() {
        PaymentInstruction saved = payments.saveAndFlush(TestData.paymentInstruction("CLM-T00000042"));
        entityManager.clear();

        PaymentInstruction found = payments.findByClaimNumber("CLM-T00000042").orElseThrow();

        assertThat(found.getReference()).isEqualTo(saved.getReference());
        assertThat(found.getAmount()).isEqualTo(new BigDecimal("3000.00"));
        assertThat(found.getCurrencyCode()).isEqualTo("INR");
        assertThat(found.getPaymentMethod()).isEqualTo(PaymentMethod.BANK_TRANSFER);
        assertThat(found.getStatus()).isEqualTo(PaymentStatus.INITIATED);
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    void findByClaimNumberIsEmptyWhenNothingWasPaid() {
        assertThat(payments.findByClaimNumber("CLM-T00000043")).isEmpty();
    }

    @Test
    void theStatusCanMoveFromInitiatedToCompleted() {
        PaymentInstruction saved = payments.saveAndFlush(TestData.paymentInstruction("CLM-T00000044"));

        saved.setStatus(PaymentStatus.COMPLETED);
        payments.saveAndFlush(saved);
        entityManager.clear();

        assertThat(payments.findByClaimNumber("CLM-T00000044").orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.COMPLETED);
    }
}
