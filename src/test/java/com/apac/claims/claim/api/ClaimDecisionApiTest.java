package com.apac.claims.claim.api;

import static com.apac.claims.support.ClaimTestSupport.code;
import static com.apac.claims.support.ClaimTestSupport.expect;
import static com.apac.claims.support.ClaimTestSupport.path;
import static com.apac.claims.support.ClaimTestSupport.read;
import static com.apac.claims.support.ClaimTestSupport.rejectionBody;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.apac.claims.claim.domain.AssessmentRecommendation;
import com.apac.claims.claim.domain.Claim;
import com.apac.claims.claim.domain.ClaimAssessment;
import com.apac.claims.claim.domain.DecisionType;
import com.apac.claims.identity.domain.Role;
import com.apac.claims.support.AbstractClaimApiTest;
import com.apac.claims.support.ClaimTestSupport.Account;
import com.apac.claims.support.ClaimTestSupport.Holder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Escalation, approval and rejection: who may decide in which status, the approval limit of an officer, what the
 * decision records, and what the claimant is shown of it. Claims of an Indian holder have a sum insured of 1000000, a
 * deductible of 1000 and are approved by an officer up to the market threshold of 500000; the Singapore tests use a
 * threshold of 10000.
 */
class ClaimDecisionApiTest extends AbstractClaimApiTest {

    private static final String LIMIT_MESSAGE = "Approved amount exceeds your approval limit; escalate to a manager.";

    private static String approve(String claimNumber) {
        return path(claimNumber, "/approve");
    }

    private static String reject(String claimNumber) {
        return path(claimNumber, "/reject");
    }

    private static String escalate(String claimNumber) {
        return path(claimNumber, "/escalate");
    }

    private static Map<String, Object> approval(Object amount, String remarks) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (amount != null) {
            body.put("approvedAmount", amount);
        }
        if (remarks != null) {
            body.put("remarks", remarks);
        }
        return body;
    }

    private static BigDecimal money(MvcResult result, String jsonPath) throws Exception {
        return new BigDecimal(read(result, jsonPath).toString());
    }

    /** The value of a timeline field on the newest entry. */
    private static String last(MvcResult timeline, String field) throws Exception {
        List<String> values = read(timeline, "$[*]." + field);
        return values.get(values.size() - 1);
    }

    private static Map<String, Object> escalation(String remarks) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("remarks", remarks);
        return body;
    }

    // ---- approval ---------------------------------------------------------------------------------------------------------

    @Test
    void approvingWithoutAnAssessmentIsRefused() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.underReview(support.newHolder("IN"), officer);

        MvcResult result = support.post(officer, approve(claimNumber), null);

        expect(result, 409);
        assertThat(code(result)).isEqualTo("ASSESSMENT_REQUIRED");
        assertThat(support.statusOf(claimNumber, officer)).isEqualTo("UNDER_REVIEW");
    }

    @Test
    void onlyTheLatestAssessmentCountsAndItMustConfirmCoverageWithAPayout() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String noCoverage = support.underReview(support.newHolder("IN"), officer);
        support.assess(noCoverage, officer, new BigDecimal("44000.00"), false, "REJECT");
        String noPayout = support.underReview(support.newHolder("IN"), officer);
        support.assess(noPayout, officer, new BigDecimal("500.00"), true, "REJECT");
        String superseded = support.underReview(support.newHolder("IN"), officer);
        support.assess(superseded, officer, new BigDecimal("44000.00"), true, "APPROVE");
        support.assess(superseded, officer, new BigDecimal("44000.00"), false, "REJECT");

        for (String claimNumber : List.of(noCoverage, noPayout, superseded)) {
            MvcResult result = support.post(officer, approve(claimNumber), null);
            expect(result, 409);
            assertThat(code(result)).isEqualTo("ASSESSMENT_REQUIRED");
        }
    }

    @Test
    void aLatestAssessmentWithoutConfirmedCoverageIsRefusedEvenWhenItCarriesAPayout() throws Exception {
        // The API cannot store this combination (without coverage the payout is always 0), so the row is written
        // directly: the rule is "coverage confirmed AND a payout above zero", and both halves must hold.
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.underReview(support.newHolder("IN"), officer);
        assessments.saveAndFlush(new ClaimAssessment(claims.findByClaimNumber(claimNumber).orElseThrow(),
                users.findByUserId(officer.userId()).orElseThrow(), new BigDecimal("44000.00"),
                new BigDecimal("1000.00"), new BigDecimal("43000.00"), false, AssessmentRecommendation.REJECT, null));

        MvcResult result = support.post(officer, approve(claimNumber), null);

        expect(result, 409);
        assertThat(code(result)).isEqualTo("ASSESSMENT_REQUIRED");
    }

    @Test
    void anApprovalWithoutABodyApprovesTheRecommendedPayoutAndRecordsTheDecision() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String claimNumber = support.assessed(holder, officer);

        MvcResult result = support.post(officer, approve(claimNumber), null);

        expect(result, 200);
        assertThat((String) read(result, "$.status")).isEqualTo("APPROVED");
        assertThat(money(result, "$.approvedAmount")).isEqualByComparingTo("43000.00");
        assertThat((String) read(result, "$.decision.type")).isEqualTo("APPROVED");
        assertThat(money(result, "$.decision.approvedAmount")).isEqualByComparingTo("43000.00");
        assertThat((String) read(result, "$.decision.decidedBy")).isEqualTo(officer.userId());
        assertThat((String) read(result, "$.decision.decidedAt")).isNotBlank();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("reasonCode");
        Claim stored = claims.findByClaimNumber(claimNumber).orElseThrow();
        assertThat(stored.getDecisionType()).isEqualTo(DecisionType.APPROVED);
        assertThat(stored.getApprovedAmount()).isEqualByComparingTo("43000.00");
        assertThat(stored.getDecidedBy().getUserId()).isEqualTo(officer.userId());
        MvcResult timeline = support.get(officer, path(claimNumber, "/timeline"));
        assertThat(last(timeline, "entryType")).isEqualTo("APPROVED");
        assertThat(last(timeline, "message")).isEqualTo("Claim approved for 43000.00 INR.");
        assertThat(last(timeline, "visibility")).isEqualTo("PUBLIC");
        assertThat(eventTypesOf(claimNumber)).containsExactly("ClaimSubmitted", "ClaimAssigned", "ClaimApproved");
        String payload = jdbc.queryForObject("select payload from outbox_events where aggregate_id = ? "
                + "and event_type = 'ClaimApproved'", String.class, claimNumber);
        assertThat(payload).contains("43000").contains("INR").contains(holder.account().userId());
    }

    @Test
    void anApprovalMayNameALowerAmountAndRemarksThatTheClaimantReads() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String claimNumber = support.assessed(holder, officer);

        MvcResult result = support.post(officer, approve(claimNumber), approval(new BigDecimal("39000.00"),
                "Approved as assessed, less the betterment."));

        expect(result, 200);
        assertThat(money(result, "$.approvedAmount")).isEqualByComparingTo("39000.00");
        MvcResult claimant = support.get(holder.account(), path(claimNumber, ""));
        assertThat(money(claimant, "$.decision.approvedAmount")).isEqualByComparingTo("39000.00");
        assertThat((String) read(claimant, "$.decision.remarks")).isEqualTo("Approved as assessed, less the betterment.");
        MvcResult timeline = support.get(holder.account(), path(claimNumber, "/timeline"));
        assertThat(last(timeline, "message")).contains("39000.00").contains("betterment");
    }

    @Test
    void anAmountEqualToTheRecommendedPayoutIsAcceptedAndOneAboveItIsNot() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String above = support.assessed(holder, officer);
        String equal = support.assessed(holder, officer);

        MvcResult tooMuch = support.post(officer, approve(above), approval(new BigDecimal("43000.01"), null));
        MvcResult exact = support.post(officer, approve(equal), approval(new BigDecimal("43000.00"), null));

        expect(tooMuch, 422);
        assertThat((String) read(tooMuch, "$.detail")).contains("approvedAmount").contains("43000.00");
        assertThat(support.statusOf(above, officer)).isEqualTo("UNDER_REVIEW");
        expect(exact, 200);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-5.00", "100.123"})
    void anApprovedAmountThatIsNotAboveZeroOrHasTooManyDecimalPlacesIsRejected(String amount) throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.assessed(support.newHolder("IN"), officer);

        MvcResult result = support.post(officer, approve(claimNumber), approval(new BigDecimal(amount), null));

        expect(result, 400);
        assertThat((String) read(result, "$.errors[0].field")).isEqualTo("approvedAmount");
        assertThat(support.statusOf(claimNumber, officer)).isEqualTo("UNDER_REVIEW");
    }

    @Test
    void anOfficerAboveTheMarketLimitMustEscalateAndAManagerCanThenApproveTheSameClaim() throws Exception {
        // Singapore: threshold 10000. Claim 25000, loss 25000, deductible 100, so the payout is 24900.
        Account officer = support.newAccount(Role.OFFICER, "SG");
        Holder holder = support.newHolder("SG");
        String claimNumber = support.submitMotor(holder, new BigDecimal("25000.00"));
        expect(support.post(officer, path(claimNumber, "/pick-up"), null), 200);
        MvcResult assessment = support.assess(claimNumber, officer, new BigDecimal("25000.00"), true, "APPROVE");
        assertThat(money(assessment, "$.recommendedPayout")).isEqualByComparingTo("24900.00");
        int entries = timelineCount(claimNumber);
        List<String> events = eventTypesOf(claimNumber);

        MvcResult refused = support.post(officer, approve(claimNumber), null);

        expect(refused, 422);
        assertThat(code(refused)).isEqualTo("APPROVAL_LIMIT_EXCEEDED");
        assertThat((String) read(refused, "$.detail")).isEqualTo(LIMIT_MESSAGE);
        assertThat(support.statusOf(claimNumber, officer)).isEqualTo("UNDER_REVIEW");
        assertThat(timelineCount(claimNumber)).isEqualTo(entries);
        assertThat(eventTypesOf(claimNumber)).isEqualTo(events);

        MvcResult byManager = support.post(support.manager(), approve(claimNumber), null);

        expect(byManager, 200);
        assertThat((String) read(byManager, "$.status")).isEqualTo("APPROVED");
        assertThat(money(byManager, "$.approvedAmount")).isEqualByComparingTo("24900.00");
        assertThat((String) read(byManager, "$.decision.decidedBy")).isEqualTo(support.manager().userId());
    }

    @Test
    void anOfficerMayApproveUpToTheThresholdItselfButNotOneCentAbove() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "SG");
        Holder holder = support.newHolder("SG");
        String claimNumber = support.submitMotor(holder, new BigDecimal("25000.00"));
        expect(support.post(officer, path(claimNumber, "/pick-up"), null), 200);
        support.assess(claimNumber, officer, new BigDecimal("25000.00"), true, "APPROVE");

        MvcResult above = support.post(officer, approve(claimNumber), approval(new BigDecimal("10000.01"), null));
        MvcResult exactly = support.post(officer, approve(claimNumber), approval(new BigDecimal("10000.00"), null));

        expect(above, 422);
        assertThat(code(above)).isEqualTo("APPROVAL_LIMIT_EXCEEDED");
        expect(exactly, 200);
        assertThat(money(exactly, "$.approvedAmount")).isEqualByComparingTo("10000.00");
    }

    @Test
    void aManagerHasNoApprovalLimit() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "SG");
        String claimNumber = support.submitMotor(support.newHolder("SG"), new BigDecimal("90000.00"));
        expect(support.post(officer, path(claimNumber, "/pick-up"), null), 200);
        support.assess(claimNumber, officer, new BigDecimal("90000.00"), true, "APPROVE");

        MvcResult result = support.post(support.manager(), approve(claimNumber), null);

        expect(result, 200);
        assertThat(money(result, "$.approvedAmount")).isEqualByComparingTo("89900.00");
    }

    @Test
    void aClaimantAnOfficerOfAnotherMarketAndAnOfficerWhoIsNotAssignedCannotApprove() throws Exception {
        Account assigned = support.newAccount(Role.OFFICER, "IN");
        Account other = support.newAccount(Role.OFFICER, "IN");
        Account otherMarket = support.newAccount(Role.OFFICER, "SG");
        Holder holder = support.newHolder("IN");
        String claimNumber = support.assessed(holder, assigned);

        MvcResult claimant = support.post(holder.account(), approve(claimNumber), null);
        MvcResult notAssigned = support.post(other, approve(claimNumber), null);
        MvcResult foreign = support.post(otherMarket, approve(claimNumber), null);

        expect(claimant, 403);
        expect(notAssigned, 403);
        assertThat(code(notAssigned)).isEqualTo("ACCESS_DENIED");
        expect(foreign, 404);
        assertThat(support.statusOf(claimNumber, assigned)).isEqualTo("UNDER_REVIEW");
    }

    @Test
    void aManagerCanApproveAClaimThatBelongsToAnOfficer() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.assessed(support.newHolder("IN"), officer);

        expect(support.post(support.manager(), approve(claimNumber), null), 200);
    }

    @Test
    void aClaimThatIsNotUnderReviewOrEscalatedCannotBeApproved() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String submitted = support.submitMotor(holder);
        String pending = support.pendingInformation(holder, officer);

        for (String claimNumber : List.of(submitted, pending)) {
            MvcResult result = support.post(support.manager(), approve(claimNumber), null);
            expect(result, 409);
            assertThat(code(result)).isEqualTo("CLAIM_INVALID_TRANSITION");
        }
    }

    // ---- escalation ----------------------------------------------------------------------------------------------------------

    @Test
    void anOfficerEscalatesAClaimAndTheManagerTakesItOver() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String claimNumber = support.assessed(holder, officer);

        MvcResult result = support.post(officer, escalate(claimNumber),
                escalation("Assessed payout is above my approval limit."));

        expect(result, 200);
        assertThat((String) read(result, "$.status")).isEqualTo("ESCALATED");
        MvcResult timeline = support.get(officer, path(claimNumber, "/timeline"));
        assertThat(last(timeline, "entryType")).isEqualTo("ESCALATED");
        assertThat(last(timeline, "fromStatus")).isEqualTo("UNDER_REVIEW");
        assertThat(last(timeline, "toStatus")).isEqualTo("ESCALATED");
        assertThat(last(timeline, "message")).contains("above my approval limit");
        assertThat(eventTypesOf(claimNumber)).containsExactly("ClaimSubmitted", "ClaimAssigned", "ClaimEscalated");
    }

    @Test
    void anEscalatedClaimCannotBeApprovedOrRejectedByTheOfficerButByTheManager() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String toApprove = support.escalated(holder, officer);
        String toReject = support.escalated(holder, officer);

        MvcResult approveByOfficer = support.post(officer, approve(toApprove), null);
        MvcResult rejectByOfficer = support.post(officer, reject(toReject), rejectionBody());

        expect(approveByOfficer, 403);
        expect(rejectByOfficer, 403);
        assertThat(code(approveByOfficer)).isEqualTo("ACCESS_DENIED");
        assertThat((String) read(approveByOfficer, "$.detail")).contains("escalated");
        assertThat((String) read(rejectByOfficer, "$.detail")).contains("escalated");
        MvcResult approved = support.post(support.manager(), approve(toApprove), null);
        MvcResult rejected = support.post(support.manager(), reject(toReject), rejectionBody());
        expect(approved, 200);
        expect(rejected, 200);
        assertThat((String) read(approved, "$.status")).isEqualTo("APPROVED");
        assertThat((String) read(rejected, "$.status")).isEqualTo("REJECTED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "too short", "123456789"})
    void anEscalationNeedsRemarksOfAtLeastTenCharacters(String remarks) throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.underReview(support.newHolder("IN"), officer);

        MvcResult result = support.post(officer, escalate(claimNumber), escalation(remarks));

        expect(result, 400);
        assertThat((List<String>) read(result, "$.errors[*].field")).contains("remarks");
        assertThat(support.statusOf(claimNumber, officer)).isEqualTo("UNDER_REVIEW");
    }

    @Test
    void anEscalationWithoutRemarksIsRejected() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.underReview(support.newHolder("IN"), officer);

        MvcResult result = support.post(officer, escalate(claimNumber), Map.of());

        expect(result, 400);
        assertThat((String) read(result, "$.errors[0].field")).isEqualTo("remarks");
    }

    @Test
    void aManagerAClaimantAndAnOfficerWhoIsNotAssignedCannotEscalate() throws Exception {
        Account assigned = support.newAccount(Role.OFFICER, "IN");
        Account other = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String claimNumber = support.underReview(holder, assigned);
        Map<String, Object> body = escalation("Assessed payout is above my approval limit.");

        MvcResult manager = support.post(support.manager(), escalate(claimNumber), body);
        MvcResult claimant = support.post(holder.account(), escalate(claimNumber), body);
        MvcResult notAssigned = support.post(other, escalate(claimNumber), body);

        expect(manager, 403);
        expect(claimant, 403);
        expect(notAssigned, 403);
        assertThat(code(manager)).isEqualTo("ACCESS_DENIED");
        assertThat(support.statusOf(claimNumber, assigned)).isEqualTo("UNDER_REVIEW");
    }

    @Test
    void onlyAClaimUnderReviewCanBeEscalated() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String pending = support.pendingInformation(holder, officer);
        String escalated = support.escalated(holder, officer);
        String approved = support.approved(holder, officer);

        for (String claimNumber : List.of(pending, escalated, approved)) {
            MvcResult result = support.post(officer, escalate(claimNumber),
                    escalation("Assessed payout is above my approval limit."));
            expect(result, 409);
            assertThat(code(result)).isEqualTo("CLAIM_INVALID_TRANSITION");
        }
    }

    // ---- rejection ---------------------------------------------------------------------------------------------------------------

    @Test
    void aRejectionRecordsTheReasonAndTheRemarksAsTheExplanationForTheClaimant() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String claimNumber = support.underReview(holder, officer);

        MvcResult result = support.post(officer, reject(claimNumber), rejectionBody());

        expect(result, 200);
        assertThat((String) read(result, "$.status")).isEqualTo("REJECTED");
        assertThat((String) read(result, "$.decision.type")).isEqualTo("REJECTED");
        assertThat((String) read(result, "$.decision.reasonCode")).isEqualTo("NOT_COVERED");
        assertThat((String) read(result, "$.decision.remarks")).isEqualTo("Flood damage is not covered by this motor policy.");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("approvedAmount");
        MvcResult claimant = support.get(holder.account(), path(claimNumber, ""));
        assertThat((String) read(claimant, "$.decision.reasonCode")).isEqualTo("NOT_COVERED");
        MvcResult timeline = support.get(holder.account(), path(claimNumber, "/timeline"));
        assertThat(last(timeline, "message")).contains("NOT_COVERED")
                .contains("Flood damage is not covered");
        assertThat(eventTypesOf(claimNumber)).containsExactly("ClaimSubmitted", "ClaimAssigned", "ClaimRejected");
        String payload = jdbc.queryForObject("select payload from outbox_events where aggregate_id = ? "
                + "and event_type = 'ClaimRejected'", String.class, claimNumber);
        assertThat(payload).contains("NOT_COVERED");
    }

    @Test
    void aRejectionNeedsAReasonCodeAndRemarksOfAtLeastTenCharacters() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.underReview(support.newHolder("IN"), officer);

        MvcResult noReason = support.post(officer, reject(claimNumber), Map.of("remarks", "Not covered by the policy."));
        MvcResult noRemarks = support.post(officer, reject(claimNumber), Map.of("reasonCode", "NOT_COVERED"));
        MvcResult shortRemarks = support.post(officer, reject(claimNumber),
                Map.of("reasonCode", "NOT_COVERED", "remarks", "too short"));
        MvcResult unknownReason = support.post(officer, reject(claimNumber),
                Map.of("reasonCode", "BECAUSE", "remarks", "Not covered by the policy."));

        expect(noReason, 400);
        expect(noRemarks, 400);
        expect(shortRemarks, 400);
        expect(unknownReason, 400);
        assertThat((String) read(noReason, "$.errors[0].field")).isEqualTo("reasonCode");
        assertThat((String) read(noRemarks, "$.errors[0].field")).isEqualTo("remarks");
        assertThat((String) read(shortRemarks, "$.errors[0].field")).isEqualTo("remarks");
        assertThat(code(unknownReason)).isEqualTo("MALFORMED_REQUEST");
        assertThat(support.statusOf(claimNumber, officer)).isEqualTo("UNDER_REVIEW");
    }

    @Test
    void aManagerCanRejectAClaimThatBelongsToAnOfficerButAnotherOfficerCannot() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Account other = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String claimNumber = support.underReview(holder, officer);

        MvcResult byOther = support.post(other, reject(claimNumber), rejectionBody());
        MvcResult byClaimant = support.post(holder.account(), reject(claimNumber), rejectionBody());
        MvcResult byManager = support.post(support.manager(), reject(claimNumber), rejectionBody());

        expect(byOther, 403);
        expect(byClaimant, 403);
        expect(byManager, 200);
    }

    @Test
    void aClaimThatWaitsInTheQueueCannotBeRejected() throws Exception {
        String claimNumber = support.submitMotor(support.newHolder("IN"));

        MvcResult result = support.post(support.manager(), reject(claimNumber), rejectionBody());

        expect(result, 409);
        assertThat(code(result)).isEqualTo("CLAIM_INVALID_TRANSITION");
        assertThat((String) read(result, "$.detail")).contains("cannot be rejected");
    }

    // ---- nothing happens after a decision ---------------------------------------------------------------------------------------------

    @Test
    void anApprovedOrRejectedClaimAcceptsNoFurtherAction() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Account manager = support.manager();
        Holder holder = support.newHolder("IN");
        String approved = support.approved(holder, officer);
        String rejected = support.rejected(holder, officer);
        Map<String, Object> assessment = Map.of("assessedLossAmount", 1000, "coverageConfirmed", true,
                "recommendation", "APPROVE");
        Map<String, Object> question = Map.of("question", "Please send the repair estimate from the workshop.");

        for (String claimNumber : List.of(approved, rejected)) {
            int entries = timelineCount(claimNumber);
            List<String> events = eventTypesOf(claimNumber);
            List<MvcResult> attempts = List.of(
                    support.post(officer, path(claimNumber, "/pick-up"), null),
                    support.post(manager, path(claimNumber, "/assign"), Map.of("officerUserId", officer.userId())),
                    support.post(manager, path(claimNumber, "/assessments"), assessment),
                    support.post(manager, path(claimNumber, "/information-requests"), question),
                    support.post(officer, escalate(claimNumber), escalation("Assessed payout is above my limit.")),
                    support.post(manager, approve(claimNumber), null),
                    support.post(manager, reject(claimNumber), rejectionBody()));
            for (MvcResult attempt : attempts) {
                expect(attempt, 409);
                assertThat(code(attempt)).isEqualTo("CLAIM_INVALID_TRANSITION");
            }
            assertThat(timelineCount(claimNumber)).isEqualTo(entries);
            assertThat(eventTypesOf(claimNumber)).isEqualTo(events);
        }
    }

    // ---- what the claimant is shown --------------------------------------------------------------------------------------------------

    @Test
    void theClaimantSeesTheDecisionBlockButNeverTheLatestAssessmentOrAStaffUserId() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String claimNumber = support.approved(holder, officer);

        MvcResult claimant = support.get(holder.account(), path(claimNumber, ""));
        MvcResult staff = support.get(officer, path(claimNumber, ""));

        assertThat((String) read(claimant, "$.decision.type")).isEqualTo("APPROVED");
        assertThat((String) read(claimant, "$.decision.decidedBy")).isEqualTo("Claims team");
        assertThat(money(claimant, "$.approvedAmount")).isEqualByComparingTo("43000.00");
        String body = claimant.getResponse().getContentAsString();
        assertThat(body).doesNotContain("latestAssessment").doesNotContain(officer.userId())
                .doesNotContain("assignedOfficerUserId");
        assertThat((String) read(staff, "$.decision.decidedBy")).isEqualTo(officer.userId());
        assertThat(money(staff, "$.latestAssessment.recommendedPayout")).isEqualByComparingTo("43000.00");
    }

    @Test
    void theClaimantTimelineHoldsOnlyPublicEntriesAndNeverAStaffUserId() throws Exception {
        Account first = support.newAccount(Role.OFFICER, "IN");
        Account second = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String claimNumber = support.underReview(holder, first);
        support.assess(claimNumber, first, new BigDecimal("44000.00"), true, "APPROVE");
        expect(support.post(support.manager(), path(claimNumber, "/assign"), Map.of("officerUserId", second.userId())),
                200);
        long id = support.requestInformation(claimNumber, second);
        support.respond(claimNumber, id, holder.account());
        expect(support.post(second, approve(claimNumber), null), 200);

        MvcResult claimant = support.get(holder.account(), path(claimNumber, "/timeline"));
        MvcResult staff = support.get(support.manager(), path(claimNumber, "/timeline"));

        assertThat((List<String>) read(claimant, "$[*].visibility")).containsOnly("PUBLIC");
        assertThat((List<String>) read(claimant, "$[*].entryType")).containsExactly("SUBMITTED", "ASSIGNED",
                "INFORMATION_REQUESTED", "INFORMATION_PROVIDED", "APPROVED");
        List<String> actors = read(claimant, "$[*].actor");
        assertThat(actors).containsExactly(holder.account().userId(), "Claims team", "Claims team",
                holder.account().userId(), "Claims team");
        String body = claimant.getResponse().getContentAsString();
        assertThat(body).doesNotContain(first.userId()).doesNotContain(second.userId())
                .doesNotContain(support.manager().userId());
        // Staff see everything, oldest first, with the internal entries between the public ones.
        assertThat((List<String>) read(staff, "$[*].entryType")).containsExactly("SUBMITTED", "ASSIGNED",
                "ASSESSMENT_RECORDED", "REASSIGNED", "INFORMATION_REQUESTED", "INFORMATION_PROVIDED", "APPROVED");
        assertThat((List<String>) read(staff, "$[*].visibility")).containsExactly("PUBLIC", "PUBLIC", "INTERNAL",
                "INTERNAL", "PUBLIC", "PUBLIC", "PUBLIC");
    }

    @Test
    void theClaimantIsToldOnlyThatTheClaimWentToAManagerNotWhy() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String claimNumber = support.assessed(holder, officer);
        expect(support.post(officer, escalate(claimNumber), escalation("Suspected inflated repair estimate.")), 200);

        MvcResult claimant = support.get(holder.account(), path(claimNumber, "/timeline"));
        MvcResult staff = support.get(officer, path(claimNumber, "/timeline"));

        assertThat(last(claimant, "entryType")).isEqualTo("ESCALATED");
        assertThat(last(claimant, "message")).isEqualTo("Claim escalated to a manager for review.");
        assertThat(claimant.getResponse().getContentAsString()).doesNotContain("inflated");
        assertThat(last(staff, "message")).contains("Suspected inflated repair estimate.");
    }

    @Test
    void staffSeeTheWholeJourneyInOrderWithTheStatusChanges() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.approved(support.newHolder("IN"), officer);

        MvcResult timeline = support.get(officer, path(claimNumber, "/timeline"));

        assertThat((List<String>) read(timeline, "$[*].entryType")).containsExactly("SUBMITTED", "ASSIGNED",
                "ASSESSMENT_RECORDED", "APPROVED");
        assertThat((String) read(timeline, "$[0].toStatus")).isEqualTo("SUBMITTED");
        assertThat((String) read(timeline, "$[1].fromStatus")).isEqualTo("SUBMITTED");
        assertThat((String) read(timeline, "$[1].toStatus")).isEqualTo("UNDER_REVIEW");
        assertThat((String) read(timeline, "$[3].fromStatus")).isEqualTo("UNDER_REVIEW");
        assertThat((String) read(timeline, "$[3].toStatus")).isEqualTo("APPROVED");
        List<String> actors = read(timeline, "$[*].actor");
        assertThat(actors).containsOnly(officer.userId(), actors.get(0));
    }
}
