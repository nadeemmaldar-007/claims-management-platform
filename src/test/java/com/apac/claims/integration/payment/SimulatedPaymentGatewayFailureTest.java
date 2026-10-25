package com.apac.claims.integration.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;

import com.apac.claims.claim.domain.PaymentMethod;
import com.apac.claims.claim.service.PaymentGateway;
import com.apac.claims.claim.service.PaymentGatewayException;
import com.apac.claims.claim.service.PaymentRequest;
import com.apac.claims.integration.domain.PaymentInstruction;
import com.apac.claims.integration.domain.PaymentInstructionRepository;
import com.apac.claims.integration.domain.PaymentStatus;
import com.apac.claims.shared.exception.ErrorCode;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * The simulated gateway with {@code app.integration.payment.simulate-failure} switched on (a context of its own): it
 * fails with a 502, writes nothing, and still answers for a claim that was paid before, because a claim is paid once.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "app.integration.payment.simulate-failure=true")
class SimulatedPaymentGatewayFailureTest {

    @Autowired
    private PaymentGateway gateway;
    @Autowired
    private PaymentInstructionRepository instructions;

    private static String newClaimNumber() {
        return "CLM-F" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
    }

    private static PaymentRequest request(String claimNumber) {
        return new PaymentRequest(claimNumber, new BigDecimal("43000.00"), "INR", PaymentMethod.BANK_TRANSFER);
    }

    @Test
    void theFailureFlagMakesTheGatewayThrowABadGatewayErrorAndNothingIsWritten() {
        String claim = newClaimNumber();

        assertThatThrownBy(() -> gateway.pay(request(claim))).isInstanceOf(PaymentGatewayException.class)
                .satisfies(error -> {
                    PaymentGatewayException failure = (PaymentGatewayException) error;
                    assertThat(failure.getStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_GATEWAY_UNAVAILABLE);
                });

        assertThat(instructions.findByClaimNumber(claim)).isEmpty();
    }

    @Test
    void aClaimThatWasPaidBeforeStillGetsItsReferenceWhileTheFlagIsOn() {
        String claim = newClaimNumber();
        instructions.saveAndFlush(new PaymentInstruction(claim, new BigDecimal("43000.00"), "INR",
                PaymentMethod.BANK_TRANSFER, "PAY-AAAAAAAAAAAA", PaymentStatus.COMPLETED));

        assertThat(gateway.pay(request(claim)).reference()).isEqualTo("PAY-AAAAAAAAAAAA");
    }
}
