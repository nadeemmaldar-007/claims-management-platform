package com.apac.claims.integration.payment;

import java.util.Optional;
import java.util.UUID;

import com.apac.claims.claim.service.PaymentGateway;
import com.apac.claims.claim.service.PaymentGatewayException;
import com.apac.claims.claim.service.PaymentReceipt;
import com.apac.claims.claim.service.PaymentRequest;
import com.apac.claims.config.PaymentProperties;
import com.apac.claims.integration.domain.PaymentInstruction;
import com.apac.claims.integration.domain.PaymentInstructionRepository;
import com.apac.claims.integration.domain.PaymentStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The simulated payment system. It is idempotent by claim number through the {@code payment_instructions} table: a
 * claim that already has an instruction gets its reference back and nothing is paid twice. Otherwise, when {@code
 * app.integration.payment.simulate-failure} is true it fails with a {@link PaymentGatewayException}, and when it is false
 * it stores an instruction with a new reference ({@code PAY-} and twelve upper-case hexadecimal characters) and returns it.
 *
 * <p>It works in a transaction of its own ({@code REQUIRES_NEW}) because it models a system outside the application:
 * once a payment has been made, the instruction must survive even if the claim transaction that asked for it is rolled
 * back afterwards, and a retry then finds it. If two requests for the same claim race, the unique claim number lets only
 * one insert win; the other re-reads the winner's row and returns its reference.
 */
@Component
public class SimulatedPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(SimulatedPaymentGateway.class);

    private final PaymentInstructionRepository instructions;
    private final PaymentProperties properties;
    private final TransactionTemplate newTransaction;

    public SimulatedPaymentGateway(PaymentInstructionRepository instructions, PaymentProperties properties,
                                   PlatformTransactionManager transactionManager) {
        this.instructions = instructions;
        this.properties = properties;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public PaymentReceipt pay(PaymentRequest request) {
        try {
            return newTransaction.execute(status -> payOnce(request));
        } catch (DataIntegrityViolationException race) {
            return newTransaction.execute(status -> existing(request.claimNumber()).orElseThrow(() -> race));
        }
    }

    private PaymentReceipt payOnce(PaymentRequest request) {
        Optional<PaymentReceipt> known = existing(request.claimNumber());
        if (known.isPresent()) {
            return known.get();
        }
        if (properties.simulateFailure()) {
            throw new PaymentGatewayException("The payment gateway is not available");
        }
        String reference = "PAY-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        // Flushed here so that a lost race shows up inside this transaction and not at its commit.
        instructions.saveAndFlush(new PaymentInstruction(request.claimNumber(), request.amount(),
                request.currencyCode(), request.paymentMethod(), reference, PaymentStatus.COMPLETED));
        log.info("Payment made claim={} reference={}", request.claimNumber(), reference);
        return new PaymentReceipt(reference);
    }

    private Optional<PaymentReceipt> existing(String claimNumber) {
        return instructions.findByClaimNumber(claimNumber)
                .map(instruction -> new PaymentReceipt(instruction.getReference()));
    }
}
