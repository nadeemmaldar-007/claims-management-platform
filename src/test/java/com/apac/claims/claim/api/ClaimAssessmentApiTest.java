package com.apac.claims.claim.api;

import static com.apac.claims.support.ClaimTestSupport.code;
import static com.apac.claims.support.ClaimTestSupport.expect;
import static com.apac.claims.support.ClaimTestSupport.path;
import static com.apac.claims.support.ClaimTestSupport.read;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.apac.claims.identity.domain.Role;
import com.apac.claims.support.AbstractClaimApiTest;
import com.apac.claims.support.ClaimTestSupport.Account;
import com.apac.claims.support.ClaimTestSupport.Holder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Assessments: the payout is computed by the system from the claim's own policy snapshots, only the assigned officer or a
 * manager may record one, only while the claim is under review or escalated, and a claimant never reaches them. The
 * claims of these tests belong to a holder with a sum insured of 1000000 and a deductible of 1000.
 */
class ClaimAssessmentApiTest extends AbstractClaimApiTest {

    private static Map<String, Object> assessment(Object loss, Object coverage, Object recommendation) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("assessedLossAmount", loss);
        body.put("coverageConfirmed", coverage);
        body.put("recommendation", recommendation);
        body.put("notes", "Internal notes for staff only.");
        return body;
    }

    private static String assessments(String claimNumber) {
        return path(claimNumber, "/assessments");
    }

    private static BigDecimal money(MvcResult result, String jsonPath) throws Exception {
        return new BigDecimal(read(result, jsonPath).toString());
    }

    // ---- the computed payout -----------------------------------------------------------------------------------------

    @Test
    void theSystemComputesTheDeductibleAndThePayoutAndTheResponseCarriesALocation() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.underReview(support.newHolder("IN"), officer);

        MvcResult result = support.post(officer, assessments(claimNumber),
                assessment(new BigDecimal("44000.00"), true, "APPROVE"));

        expect(result, 201);
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/api/v1/claims/" + claimNumber + "/assessments");
        assertThat(money(result, "$.assessedLossAmount")).isEqualByComparingTo("44000.00");
        assertThat(money(result, "$.deductibleApplied")).isEqualByComparingTo("1000.00");
        assertThat(money(result, "$.recommendedPayout")).isEqualByComparingTo("43000.00");
        assertThat((Boolean) read(result, "$.coverageConfirmed")).isTrue();
        assertThat((String) read(result, "$.recommendation")).isEqualTo("APPROVE");
        assertThat((String) read(result, "$.assessedBy")).isEqualTo(officer.userId());
        assertThat((String) read(result, "$.notes")).isEqualTo("Internal notes for staff only.");
    }

    @Test
    void withoutConfirmedCoverageThePayoutIsZero() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.underReview(support.newHolder("IN"), officer);

        MvcResult result = support.post(officer, assessments(claimNumber),
                assessment(new BigDecimal("44000.00"), false, "REJECT"));

        expect(result, 201);
        assertThat(money(result, "$.recommendedPayout")).isEqualByComparingTo("0.00");
        assertThat(money(result, "$.deductibleApplied")).isEqualByComparingTo("1000.00");
    }

    @Test
    void aLossAboveTheSumInsuredIsCappedAtTheSumInsuredBeforeTheDeductible() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.underReview(support.newHolder("IN"), officer);

        MvcResult result = support.post(officer, assessments(claimNumber),
                assessment(new BigDecimal("2000000.00"), true, "APPROVE"));

        expect(result, 201);
        assertThat(money(result, "$.recommendedPayout")).isEqualByComparingTo("999000.00");
    }

    @Test
    void aLossBelowTheDeductibleGivesNoPayoutAndNeverANegativeOne() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.underReview(support.newHolder("IN"), officer);

        MvcResult result = support.post(officer, assessments(claimNumber),
                assessment(new BigDecimal("500.00"), true, "REJECT"));

        expect(result, 201);
        assertThat(money(result, "$.recommendedPayout")).isEqualByComparingTo("0.00");
    }

    @Test
    void aPayoutOrDeductibleSentByTheClientIsIgnored() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.underReview(support.newHolder("IN"), officer);
        Map<String, Object> body = assessment(new BigDecimal("44000.00"), true, "APPROVE");
        body.put("recommendedPayout", new BigDecimal("999999.00"));
        body.put("deductibleApplied", new BigDecimal("0.00"));

        MvcResult result = support.post(officer, assessments(claimNumber), body);

        expect(result, 201);
        assertThat(money(result, "$.recommendedPayout")).isEqualByComparingTo("43000.00");
        assertThat(money(result, "$.deductibleApplied")).isEqualByComparingTo("1000.00");
    }

    @Test
    void amountsOfAYenClaimHaveNoDecimalPlacesAndAFractionOfAYenIsRejected() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "JP");
        String claimNumber = support.underReview(support.newHolder("JP"), officer);

        MvcResult ok = support.post(officer, assessments(claimNumber), assessment(new BigDecimal("50000"), true, "APPROVE"));
        MvcResult fraction = support.post(officer, assessments(claimNumber),
                assessment(new BigDecimal("50000.5"), true, "APPROVE"));

        expect(ok, 201);
        assertThat(ok.getResponse().getContentAsString()).contains("\"recommendedPayout\":40000,")
                .contains("\"assessedLossAmount\":50000,");
        expect(fraction, 400);
        assertThat((String) read(fraction, "$.errors[0].field")).isEqualTo("assessedLossAmount");
    }

    @Test
    void theLatestAssessmentCountsAndTheListIsNewestFirst() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.underReview(support.newHolder("IN"), officer);
        support.assess(claimNumber, officer, new BigDecimal("10000.00"), true, "REJECT");
        support.assess(claimNumber, officer, new BigDecimal("20000.00"), true, "APPROVE");
        support.assess(claimNumber, officer, new BigDecimal("30000.00"), true, "APPROVE");

        MvcResult list = support.get(officer, assessments(claimNumber));
        MvcResult detail = support.get(officer, path(claimNumber, ""));

        expect(list, 200);
        List<Number> losses = read(list, "$[*].assessedLossAmount");
        assertThat(losses).extracting(Number::intValue).containsExactly(30000, 20000, 10000);
        assertThat(money(detail, "$.latestAssessment.assessedLossAmount")).isEqualByComparingTo("30000.00");
    }

    // ---- who may assess, and when -------------------------------------------------------------------------------------

    @Test
    void anOfficerWhoIsNotTheAssignedOfficerIsForbiddenAndNothingIsStored() throws Exception {
        Account assigned = support.newAccount(Role.OFFICER, "IN");
        Account other = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.underReview(support.newHolder("IN"), assigned);
        int entries = timelineCount(claimNumber);

        MvcResult result = support.post(other, assessments(claimNumber), assessment(new BigDecimal("1000.00"), true, "APPROVE"));

        expect(result, 403);
        assertThat(code(result)).isEqualTo("ACCESS_DENIED");
        assertThat(timelineCount(claimNumber)).isEqualTo(entries);
        assertThat((List<?>) read(support.get(assigned, assessments(claimNumber)), "$")).isEmpty();
    }

    @Test
    void aManagerCanAssessAClaimThatBelongsToAnOfficer() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.underReview(support.newHolder("IN"), officer);

        MvcResult result = support.post(support.manager(), assessments(claimNumber),
                assessment(new BigDecimal("1000.00"), true, "APPROVE"));

        expect(result, 201);
        assertThat((String) read(result, "$.assessedBy")).isEqualTo(support.manager().userId());
    }

    @Test
    void anEscalatedClaimCanStillBeAssessedByItsOfficerAndByAManager() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.escalated(support.newHolder("IN"), officer);

        expect(support.post(officer, assessments(claimNumber), assessment(new BigDecimal("1000.00"), true, "ESCALATE")), 201);
        expect(support.post(support.manager(), assessments(claimNumber),
                assessment(new BigDecimal("2000.00"), true, "APPROVE")), 201);
    }

    @Test
    void aClaimThatIsNotUnderReviewOrEscalatedCannotBeAssessed() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String submitted = support.submitMotor(holder);
        String pending = support.pendingInformation(holder, officer);
        String approved = support.approved(holder, officer);
        String rejected = support.rejected(holder, officer);

        for (String claimNumber : List.of(submitted, pending, approved, rejected)) {
            // A manager passes the authority check, so the status is what is refused.
            MvcResult result = support.post(support.manager(), assessments(claimNumber),
                    assessment(new BigDecimal("1000.00"), true, "APPROVE"));
            expect(result, 409);
            assertThat(code(result)).isEqualTo("CLAIM_INVALID_TRANSITION");
            assertThat((String) read(result, "$.detail")).contains("cannot be assessed");
        }
    }

    @Test
    void anOfficerCannotAssessAClaimNobodyHasPickedUpYet() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.submitMotor(support.newHolder("IN"));

        MvcResult result = support.post(officer, assessments(claimNumber), assessment(new BigDecimal("1000.00"), true, "APPROVE"));

        expect(result, 403);
    }

    @Test
    void aClaimantCannotReachTheAssessmentEndpointsEvenForTheirOwnClaim() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String claimNumber = support.assessed(holder, officer);

        MvcResult post = support.post(holder.account(), assessments(claimNumber),
                assessment(new BigDecimal("1000.00"), true, "APPROVE"));
        MvcResult get = support.get(holder.account(), assessments(claimNumber));

        expect(post, 403);
        expect(get, 403);
        assertThat(code(get)).isEqualTo("ACCESS_DENIED");
    }

    @Test
    void anOfficerOfAnotherMarketDoesNotSeeTheClaimsAssessments() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Account officerSg = support.newAccount(Role.OFFICER, "SG");
        String claimNumber = support.assessed(support.newHolder("IN"), officer);

        expect(support.get(officerSg, assessments(claimNumber)), 404);
        expect(support.post(officerSg, assessments(claimNumber), assessment(new BigDecimal("1000.00"), true, "APPROVE")), 404);
    }

    // ---- what the claimant never sees -----------------------------------------------------------------------------------

    @Test
    void theClaimantSeesNeitherTheAssessmentNorItsNotesNorTheTimelineEntry() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String claimNumber = support.assessed(holder, officer);

        MvcResult claimantDetail = support.get(holder.account(), path(claimNumber, ""));
        MvcResult claimantTimeline = support.get(holder.account(), path(claimNumber, "/timeline"));
        MvcResult staffDetail = support.get(officer, path(claimNumber, ""));
        MvcResult staffTimeline = support.get(officer, path(claimNumber, "/timeline"));

        assertThat(claimantDetail.getResponse().getContentAsString()).doesNotContain("latestAssessment")
                .doesNotContain("Internal notes").doesNotContain("recommendedPayout");
        assertThat(claimantTimeline.getResponse().getContentAsString()).doesNotContain("ASSESSMENT_RECORDED")
                .doesNotContain("Internal notes");
        assertThat((String) read(staffDetail, "$.latestAssessment.notes")).isEqualTo("Internal notes for staff only.");
        assertThat((List<String>) read(staffTimeline, "$[*].entryType")).contains("ASSESSMENT_RECORDED");
    }

    @Test
    void anAssessmentWritesAnInternalTimelineEntryWithoutTheNotesAndNoEvent() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.underReview(support.newHolder("IN"), officer);
        List<String> eventsBefore = eventTypesOf(claimNumber);

        support.assess(claimNumber, officer, new BigDecimal("44000.00"), true, "APPROVE");

        MvcResult timeline = support.get(officer, path(claimNumber, "/timeline"));
        assertThat((String) read(timeline, "$[2].entryType")).isEqualTo("ASSESSMENT_RECORDED");
        assertThat((String) read(timeline, "$[2].visibility")).isEqualTo("INTERNAL");
        assertThat((String) read(timeline, "$[2].message")).isEqualTo("Assessment recorded.");
        assertThat(eventTypesOf(claimNumber)).isEqualTo(eventsBefore);
    }

    // ---- validation ---------------------------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-100.00", "100000000.01"})
    void anAssessedLossOutsideTheAllowedRangeIsRejected(String loss) throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.underReview(support.newHolder("IN"), officer);

        MvcResult result = support.post(officer, assessments(claimNumber), assessment(new BigDecimal(loss), true, "APPROVE"));

        expect(result, 400);
        assertThat((String) read(result, "$.errors[0].field")).isEqualTo("assessedLossAmount");
    }

    @Test
    void aMissingCoverageFlagOrRecommendationIsRejected() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.underReview(support.newHolder("IN"), officer);

        MvcResult noCoverage = support.post(officer, assessments(claimNumber),
                assessment(new BigDecimal("1000.00"), null, "APPROVE"));
        MvcResult noRecommendation = support.post(officer, assessments(claimNumber),
                assessment(new BigDecimal("1000.00"), true, null));

        expect(noCoverage, 400);
        expect(noRecommendation, 400);
        assertThat((String) read(noCoverage, "$.errors[0].field")).isEqualTo("coverageConfirmed");
        assertThat((String) read(noRecommendation, "$.errors[0].field")).isEqualTo("recommendation");
    }

    @Test
    void anUnknownRecommendationIsAMalformedRequest() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.underReview(support.newHolder("IN"), officer);

        MvcResult result = support.post(officer, assessments(claimNumber),
                assessment(new BigDecimal("1000.00"), true, "MAYBE"));

        expect(result, 400);
        assertThat(code(result)).isEqualTo("MALFORMED_REQUEST");
    }

    @Test
    void notesLongerThanTwoThousandCharactersAreRejectedAndNotesMayBeLeftOut() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.underReview(support.newHolder("IN"), officer);
        Map<String, Object> tooLong = assessment(new BigDecimal("1000.00"), true, "APPROVE");
        tooLong.put("notes", "n".repeat(2001));
        Map<String, Object> without = assessment(new BigDecimal("1000.00"), true, "APPROVE");
        without.remove("notes");

        MvcResult rejected = support.post(officer, assessments(claimNumber), tooLong);
        MvcResult accepted = support.post(officer, assessments(claimNumber), without);

        expect(rejected, 400);
        assertThat((String) read(rejected, "$.errors[0].field")).isEqualTo("notes");
        expect(accepted, 201);
        assertThat(accepted.getResponse().getContentAsString()).doesNotContain("\"notes\"");
    }
}
