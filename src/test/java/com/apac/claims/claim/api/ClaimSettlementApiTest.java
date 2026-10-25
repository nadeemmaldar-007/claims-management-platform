package com.apac.claims.claim.api;

import static com.apac.claims.support.ClaimTestSupport.code;
import static com.apac.claims.support.ClaimTestSupport.expect;
import static com.apac.claims.support.ClaimTestSupport.path;
import static com.apac.claims.support.ClaimTestSupport.read;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.apac.claims.claim.domain.Claim;
import com.apac.claims.claim.domain.ClaimStatus;
import com.apac.claims.claim.domain.PaymentMethod;
import com.apac.claims.identity.domain.Role;
import com.apac.claims.integration.domain.PaymentInstruction;
import com.apac.claims.integration.domain.PaymentInstructionRepository;
import com.apac.claims.integration.domain.PaymentStatus;
import com.apac.claims.support.AbstractClaimApiTest;
import com.apac.claims.support.ClaimTestSupport.Account;
import com.apac.claims.support.ClaimTestSupport.Holder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Settlement through the API with the simulated gateway: who may settle, which status allows it, what is stored and
 * written, and that a claim is paid once however often or however fast it is asked. The failure of the gateway has
 * its own class, because it needs a gateway the test can switch off.
 */
class ClaimSettlementApiTest extends AbstractClaimApiTest {

    private static final String REFERENCE = "^PAY-[0-9A-F]{12}$";

    @Autowired
    private PaymentInstructionRepository paymentInstructions;

    private static String settle(String claimNumber) {
        return path(claimNumber, "/settle");
    }

    private static Map<String, Object> bank() {
        return Map.of("paymentMethod", "BANK_TRANSFER");
    }

    private static BigDecimal money(MvcResult result, String jsonPath) throws Exception {
        return new BigDecimal(read(result, jsonPath).toString());
    }

    // ---- the happy path -----------------------------------------------------------------------------------------------

    @Test
    void aManagerSettlesAnApprovedClaimAndTheClaimShowsItsSettlement() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.approved(support.newHolder("IN"), officer);

        MvcResult result = support.post(support.manager(), settle(claimNumber),
                Map.of("paymentMethod", "BANK_TRANSFER", "remarks", "Paid to the account on file."));

        expect(result, 200);
        assertThat((String) read(result, "$.status")).isEqualTo("SETTLED");
        assertThat((String) read(result, "$.settlement.paymentMethod")).isEqualTo("BANK_TRANSFER");
        assertThat((String) read(result, "$.settlement.paymentReference")).matches(REFERENCE);
        assertThat(money(result, "$.settlement.settledAmount")).isEqualByComparingTo("43000.00");
        assertThat((String) read(result, "$.settlement.settledAt")).isNotBlank();
        assertThat(money(result, "$.approvedAmount")).isEqualByComparingTo("43000.00");
        assertThat((String) read(result, "$.decision.type")).isEqualTo("APPROVED");
    }

    @Test
    void theAssignedOfficerMaySettleTheirOwnApprovedClaimToo() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.approved(support.newHolder("IN"), officer);

        MvcResult result = support.post(officer, settle(claimNumber), Map.of("paymentMethod", "CHEQUE"));

        expect(result, 200);
        assertThat((String) read(result, "$.settlement.paymentMethod")).isEqualTo("CHEQUE");
    }

    @Test
    void theSettlementIsStoredOnTheClaimAndInThePaymentInstructionAndTheReferencesAgree() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.approved(support.newHolder("IN"), officer);

        MvcResult result = support.post(officer, settle(claimNumber), bank());

        expect(result, 200);
        String reference = read(result, "$.settlement.paymentReference");
        Claim stored = claims.findByClaimNumber(claimNumber).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(ClaimStatus.SETTLED);
        assertThat(stored.getPaymentMethod()).isEqualTo(PaymentMethod.BANK_TRANSFER);
        assertThat(stored.getPaymentReference()).isEqualTo(reference);
        assertThat(stored.getSettledAmount()).isEqualByComparingTo(stored.getApprovedAmount());
        assertThat(stored.getSettledAt()).isNotNull();
        PaymentInstruction instruction = paymentInstructions.findByClaimNumber(claimNumber).orElseThrow();
        assertThat(instruction.getReference()).isEqualTo(reference);
        assertThat(instruction.getAmount()).isEqualByComparingTo("43000.00");
        assertThat(instruction.getCurrencyCode()).isEqualTo("INR");
        assertThat(instruction.getPaymentMethod()).isEqualTo(PaymentMethod.BANK_TRANSFER);
        assertThat(instruction.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
    }

    @Test
    void theAmountPaidIsTheApprovedAmountEvenWhenItIsLowerThanTheRecommendedPayout() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.assessed(support.newHolder("IN"), officer);
        expect(support.post(officer, path(claimNumber, "/approve"), Map.of("approvedAmount", 39000.00)), 200);

        MvcResult result = support.post(officer, settle(claimNumber), bank());

        expect(result, 200);
        assertThat(money(result, "$.settlement.settledAmount")).isEqualByComparingTo("39000.00");
        assertThat(paymentInstructions.findByClaimNumber(claimNumber).orElseThrow().getAmount())
                .isEqualByComparingTo("39000.00");
    }

    @Test
    void anAmountInYenHasNoDecimalPlacesInTheSettlement() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "JP");
        Holder holder = support.newHolder("JP");
        String claimNumber = support.submitMotor(holder, new BigDecimal("50000"));
        expect(support.post(officer, path(claimNumber, "/pick-up"), null), 200);
        support.assess(claimNumber, officer, new BigDecimal("50000"), true, "APPROVE");
        expect(support.post(officer, path(claimNumber, "/approve"), null), 200);

        MvcResult result = support.post(officer, settle(claimNumber), bank());

        expect(result, 200);
        assertThat(result.getResponse().getContentAsString()).contains("\"settledAmount\":40000,");
    }

    @Test
    void settlementWritesAPublicTimelineEntryAndAClaimSettledEventWithThePaymentReference() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String claimNumber = support.approved(holder, officer);

        MvcResult result = support.post(officer, settle(claimNumber),
                Map.of("paymentMethod", "CHEQUE", "remarks", "Cheque posted today."));

        expect(result, 200);
        String reference = read(result, "$.settlement.paymentReference");
        MvcResult timeline = support.get(holder.account(), path(claimNumber, "/timeline"));
        List<String> types = read(timeline, "$[*].entryType");
        assertThat(types).containsExactly("SUBMITTED", "ASSIGNED", "APPROVED", "SETTLED");
        List<String> visibilities = read(timeline, "$[?(@.entryType=='SETTLED')].visibility");
        List<String> messages = read(timeline, "$[?(@.entryType=='SETTLED')].message");
        List<String> actors = read(timeline, "$[?(@.entryType=='SETTLED')].actor");
        assertThat(visibilities).containsExactly("PUBLIC");
        assertThat(messages.get(0)).contains("43000.00 INR").contains("cheque").contains(reference)
                .contains("Cheque posted today.");
        assertThat(actors).containsExactly("Claims team");
        assertThat(eventTypesOf(claimNumber)).containsExactly("ClaimSubmitted", "ClaimAssigned", "ClaimApproved",
                "ClaimSettled");
        String payload = jdbc.queryForObject("select payload from outbox_events where aggregate_id = ? "
                + "and event_type = 'ClaimSettled'", String.class, claimNumber);
        assertThat(payload).contains(reference).contains("43000").contains("INR");
    }

    @Test
    void theClaimantSeesTheSettlementButNoStaffUserId() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String claimNumber = support.approved(holder, officer);
        expect(support.post(officer, settle(claimNumber), bank()), 200);

        MvcResult claimant = support.get(holder.account(), path(claimNumber, ""));

        assertThat((String) read(claimant, "$.settlement.paymentReference")).matches(REFERENCE);
        assertThat(money(claimant, "$.settlement.settledAmount")).isEqualByComparingTo("43000.00");
        assertThat(claimant.getResponse().getContentAsString()).doesNotContain(officer.userId())
                .doesNotContain("latestAssessment");
    }

    // ---- what refuses a settlement ------------------------------------------------------------------------------------

    @Test
    void aSecondSettlementIsRefusedAndPaysNothingAndWritesNothing() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.approved(support.newHolder("IN"), officer);
        MvcResult first = support.post(officer, settle(claimNumber), bank());
        expect(first, 200);
        int entries = timelineCount(claimNumber);
        List<String> events = eventTypesOf(claimNumber);

        MvcResult second = support.post(support.manager(), settle(claimNumber), bank());

        expect(second, 409);
        assertThat(code(second)).isEqualTo("CLAIM_INVALID_TRANSITION");
        assertThat(timelineCount(claimNumber)).isEqualTo(entries);
        assertThat(eventTypesOf(claimNumber)).isEqualTo(events);
        assertThat(paymentInstructions.findByClaimNumber(claimNumber).orElseThrow().getReference())
                .isEqualTo((String) read(first, "$.settlement.paymentReference"));
    }

    @Test
    void aClaimThatIsNotApprovedCannotBeSettled() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String submitted = support.submitMotor(holder);
        String underReview = support.underReview(holder, officer);
        String pending = support.pendingInformation(holder, officer);
        String escalated = support.escalated(holder, officer);
        String rejected = support.rejected(holder, officer);

        for (String claimNumber : List.of(submitted, underReview, pending, escalated, rejected)) {
            // A manager passes the authority check, so the status is what is refused.
            MvcResult result = support.post(support.manager(), settle(claimNumber), bank());
            expect(result, 409);
            assertThat(code(result)).isEqualTo("CLAIM_INVALID_TRANSITION");
            assertThat((String) read(result, "$.detail")).contains("cannot be settled");
            assertThat(paymentInstructions.findByClaimNumber(claimNumber)).isEmpty();
        }
    }

    @Test
    void anOfficerWhoIsNotTheAssignedOfficerIsForbidden() throws Exception {
        Account assigned = support.newAccount(Role.OFFICER, "IN");
        Account other = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.approved(support.newHolder("IN"), assigned);

        MvcResult result = support.post(other, settle(claimNumber), bank());

        expect(result, 403);
        assertThat(code(result)).isEqualTo("ACCESS_DENIED");
        assertThat(support.statusOf(claimNumber, assigned)).isEqualTo("APPROVED");
        assertThat(paymentInstructions.findByClaimNumber(claimNumber)).isEmpty();
    }

    @Test
    void anOfficerOfAnotherMarketDoesNotSeeTheClaim() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Account officerSg = support.newAccount(Role.OFFICER, "SG");
        String claimNumber = support.approved(support.newHolder("IN"), officer);

        MvcResult result = support.post(officerSg, settle(claimNumber), bank());

        expect(result, 404);
        assertThat(code(result)).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(support.statusOf(claimNumber, officer)).isEqualTo("APPROVED");
    }

    @Test
    void aClaimantCannotSettleEvenTheirOwnClaim() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String claimNumber = support.approved(holder, officer);

        MvcResult result = support.post(holder.account(), settle(claimNumber), bank());

        expect(result, 403);
        assertThat(code(result)).isEqualTo("ACCESS_DENIED");
        assertThat(support.statusOf(claimNumber, officer)).isEqualTo("APPROVED");
    }

    @Test
    void aClaimThatDoesNotExistIsNotFoundAndWithoutATokenTheAnswerIsUnauthenticated() throws Exception {
        MvcResult missing = support.post(support.manager(), settle("CLM-99999999"), bank());
        MvcResult anonymous = support.post(null, settle("CLM-99999999"), bank());

        expect(missing, 404);
        expect(anonymous, 401);
    }

    // ---- the request ---------------------------------------------------------------------------------------------------

    @Test
    void aMissingOrUnknownPaymentMethodIsRejected() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.approved(support.newHolder("IN"), officer);

        MvcResult missing = support.post(officer, settle(claimNumber), Map.of("remarks", "No method given."));
        MvcResult unknown = support.post(officer, settle(claimNumber), Map.of("paymentMethod", "CASH"));

        expect(missing, 400);
        expect(unknown, 400);
        assertThat((String) read(missing, "$.errors[0].field")).isEqualTo("paymentMethod");
        assertThat(code(unknown)).isEqualTo("MALFORMED_REQUEST");
        assertThat(support.statusOf(claimNumber, officer)).isEqualTo("APPROVED");
    }

    @Test
    void remarksOverAThousandCharactersAreRejected() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.approved(support.newHolder("IN"), officer);

        MvcResult result = support.post(officer, settle(claimNumber),
                Map.of("paymentMethod", "BANK_TRANSFER", "remarks", "r".repeat(1001)));

        expect(result, 400);
        assertThat((String) read(result, "$.errors[0].field")).isEqualTo("remarks");
    }

    @ParameterizedTest
    @ValueSource(strings = {"BANK_TRANSFER", "CHEQUE"})
    void bothPaymentMethodsAreAccepted(String method) throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.approved(support.newHolder("IN"), officer);

        MvcResult result = support.post(officer, settle(claimNumber), Map.of("paymentMethod", method));

        expect(result, 200);
        assertThat((String) read(result, "$.settlement.paymentMethod")).isEqualTo(method);
    }

    // ---- a claim is paid once ----------------------------------------------------------------------------------------------

    @Test
    void twoSettlementsAtTheSameMomentPayOnceAndTheLoserIsToldItIsAConflict() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Account manager = support.manager();
        String claimNumber = support.approved(support.newHolder("IN"), officer);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (Account actor : List.of(officer, manager)) {
                Callable<MvcResult> call = () -> {
                    ready.countDown();
                    go.await();
                    return support.post(actor, settle(claimNumber), bank());
                };
                futures.add(pool.submit(call));
            }
            ready.await();
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<MvcResult> future : futures) {
                statuses.add(future.get(60, TimeUnit.SECONDS).getResponse().getStatus());
            }

            assertThat(statuses).containsExactlyInAnyOrder(200, 409);
            assertThat(paymentInstructions.findByClaimNumber(claimNumber)).isPresent();
            assertThat(jdbc.queryForObject("select count(*) from payment_instructions where claim_number = ?",
                    Integer.class, claimNumber)).isEqualTo(1);
            assertThat(eventTypesOf(claimNumber)).filteredOn("ClaimSettled"::equals).hasSize(1);
            assertThat(jdbc.queryForObject("select count(*) from claim_timeline_entries where entry_type = 'SETTLED' "
                    + "and claim_id = (select id from claims where claim_number = ?)", Integer.class, claimNumber))
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
