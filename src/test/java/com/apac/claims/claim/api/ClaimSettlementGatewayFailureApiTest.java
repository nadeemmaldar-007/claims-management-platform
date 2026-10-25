package com.apac.claims.claim.api;

import static com.apac.claims.support.ClaimTestSupport.code;
import static com.apac.claims.support.ClaimTestSupport.expect;
import static com.apac.claims.support.ClaimTestSupport.path;
import static com.apac.claims.support.ClaimTestSupport.read;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.apac.claims.claim.domain.Claim;
import com.apac.claims.claim.domain.ClaimStatus;
import com.apac.claims.claim.service.PaymentGateway;
import com.apac.claims.claim.service.PaymentGatewayException;
import com.apac.claims.claim.service.PaymentReceipt;
import com.apac.claims.claim.service.PaymentRequest;
import com.apac.claims.identity.domain.Role;
import com.apac.claims.integration.domain.PaymentInstruction;
import com.apac.claims.integration.domain.PaymentInstructionRepository;
import com.apac.claims.integration.payment.SimulatedPaymentGateway;
import com.apac.claims.support.AbstractClaimApiTest;
import com.apac.claims.support.ClaimTestSupport.Account;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MvcResult;

/**
 * What a failing payment gateway does to a settlement. The test replaces the gateway of the application with one it can
 * switch: it fails before paying, or fails after the real simulated gateway has paid (an answer that got lost), and
 * otherwise passes the call on. The claim must stay APPROVED with nothing written whenever the settlement fails, and a
 * retry must succeed with the reference of the payment that is stored.
 */
@Import(ClaimSettlementGatewayFailureApiTest.SwitchableGatewayConfig.class)
class ClaimSettlementGatewayFailureApiTest extends AbstractClaimApiTest {

    /** Fails on request, before or after the real gateway did its work. */
    static class SwitchableGateway implements PaymentGateway {

        private final PaymentGateway real;
        volatile boolean failBeforePaying;
        volatile boolean failAfterPaying;
        final AtomicInteger calls = new AtomicInteger();

        SwitchableGateway(PaymentGateway real) {
            this.real = real;
        }

        @Override
        public PaymentReceipt pay(PaymentRequest request) {
            calls.incrementAndGet();
            if (failBeforePaying) {
                throw new PaymentGatewayException("The payment gateway is not available");
            }
            PaymentReceipt receipt = real.pay(request);
            if (failAfterPaying) {
                throw new IllegalStateException("the answer of the gateway was lost");
            }
            return receipt;
        }

        void behaveNormally() {
            failBeforePaying = false;
            failAfterPaying = false;
        }
    }

    @TestConfiguration
    static class SwitchableGatewayConfig {

        @Bean
        @Primary
        SwitchableGateway switchableGateway(SimulatedPaymentGateway real) {
            return new SwitchableGateway(real);
        }
    }

    @Autowired
    private SwitchableGateway gateway;
    @Autowired
    private PaymentInstructionRepository paymentInstructions;

    @BeforeEach
    void gatewayWorksAgain() {
        gateway.behaveNormally();
    }

    private static String settle(String claimNumber) {
        return path(claimNumber, "/settle");
    }

    private static Map<String, Object> bank() {
        return Map.of("paymentMethod", "BANK_TRANSFER");
    }

    @Test
    void whenTheGatewayIsDownTheAnswerIsABadGatewayAndNothingChanges() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.approved(support.newHolder("IN"), officer);
        int entries = timelineCount(claimNumber);
        List<String> events = eventTypesOf(claimNumber);
        Long version = claims.findByClaimNumber(claimNumber).orElseThrow().getVersion();
        gateway.failBeforePaying = true;

        MvcResult result = support.post(officer, settle(claimNumber), bank());

        expect(result, 502);
        assertThat(code(result)).isEqualTo("PAYMENT_GATEWAY_UNAVAILABLE");
        Claim stored = claims.findByClaimNumber(claimNumber).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ClaimStatus.APPROVED);
        assertThat(stored.getPaymentReference()).isNull();
        assertThat(stored.getPaymentMethod()).isNull();
        assertThat(stored.getSettledAmount()).isNull();
        assertThat(stored.getSettledAt()).isNull();
        assertThat(stored.getVersion()).isEqualTo(version);
        assertThat(timelineCount(claimNumber)).isEqualTo(entries);
        assertThat(eventTypesOf(claimNumber)).isEqualTo(events).doesNotContain("ClaimSettled");
        assertThat(paymentInstructions.findByClaimNumber(claimNumber)).isEmpty();
        MvcResult detail = support.get(officer, path(claimNumber, ""));
        assertThat((String) read(detail, "$.status")).isEqualTo("APPROVED");
        assertThat(detail.getResponse().getContentAsString()).doesNotContain("\"settlement\"");
    }

    @Test
    void aRetryAfterAFailureSucceedsWithTheReferenceOfTheStoredPaymentInstruction() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.approved(support.newHolder("IN"), officer);
        gateway.failBeforePaying = true;
        expect(support.post(officer, settle(claimNumber), bank()), 502);

        gateway.behaveNormally();
        MvcResult retry = support.post(officer, settle(claimNumber), bank());

        expect(retry, 200);
        assertThat((String) read(retry, "$.status")).isEqualTo("SETTLED");
        String reference = read(retry, "$.settlement.paymentReference");
        PaymentInstruction instruction = paymentInstructions.findByClaimNumber(claimNumber).orElseThrow();
        assertThat(instruction.getReference()).isEqualTo(reference);
        assertThat(eventTypesOf(claimNumber)).filteredOn("ClaimSettled"::equals).hasSize(1);
    }

    @Test
    void theFailureIsAnAnswerOfTheContractWithTheUsualFields() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.approved(support.newHolder("IN"), officer);
        gateway.failBeforePaying = true;

        MvcResult result = support.post(officer, settle(claimNumber), bank());

        expect(result, 502);
        assertThat((Integer) read(result, "$.status")).isEqualTo(502);
        assertThat((String) read(result, "$.title")).isEqualTo("Bad Gateway");
        assertThat((String) read(result, "$.instance")).isEqualTo("/api/v1/claims/" + claimNumber + "/settle");
        assertThat((String) read(result, "$.correlationId")).isNotBlank();
    }

    @Test
    void aFailureAfterThePaymentWasMadeKeepsThePaymentAndTheRetryReusesItsReference() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.approved(support.newHolder("IN"), officer);
        int entries = timelineCount(claimNumber);
        gateway.failAfterPaying = true;

        MvcResult lost = support.post(officer, settle(claimNumber), bank());

        // The answer was lost, so the claim transaction rolled back (a 500), but the payment itself was made.
        expect(lost, 500);
        assertThat(claims.findByClaimNumber(claimNumber).orElseThrow().getStatus()).isEqualTo(ClaimStatus.APPROVED);
        assertThat(timelineCount(claimNumber)).isEqualTo(entries);
        PaymentInstruction paid = paymentInstructions.findByClaimNumber(claimNumber).orElseThrow();

        gateway.behaveNormally();
        MvcResult retry = support.post(officer, settle(claimNumber), bank());

        expect(retry, 200);
        assertThat((String) read(retry, "$.settlement.paymentReference")).isEqualTo(paid.getReference());
        assertThat(jdbc.queryForObject("select count(*) from payment_instructions where claim_number = ?",
                Integer.class, claimNumber)).isEqualTo(1);
    }

    @Test
    void aRefusedSettlementDoesNotEvenCallTheGateway() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Account other = support.newAccount(Role.OFFICER, "IN");
        String approved = support.approved(support.newHolder("IN"), officer);
        String underReview = support.underReview(support.newHolder("IN"), officer);
        int before = gateway.calls.get();

        expect(support.post(other, settle(approved), bank()), 403);
        expect(support.post(support.manager(), settle(underReview), bank()), 409);
        expect(support.post(support.manager(), settle("CLM-99999999"), bank()), 404);

        assertThat(gateway.calls.get()).isEqualTo(before);
    }
}
