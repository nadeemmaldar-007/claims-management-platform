package com.apac.claims.claim.api;

import static com.apac.claims.support.ClaimTestSupport.code;
import static com.apac.claims.support.ClaimTestSupport.expect;
import static com.apac.claims.support.ClaimTestSupport.path;
import static com.apac.claims.support.ClaimTestSupport.read;
import static com.apac.claims.support.ClaimTestSupport.rejectionBody;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.apac.claims.claim.domain.InformationRequest;
import com.apac.claims.identity.domain.Role;
import com.apac.claims.support.AbstractClaimApiTest;
import com.apac.claims.support.ClaimTestSupport.Account;
import com.apac.claims.support.ClaimTestSupport.Holder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Information requests: staff ask, the claimant answers in text, and the claim waits in PENDING_INFORMATION until no
 * request is left open. A request is always reached through its own claim, so an id from another claim is a 404.
 */
class InformationRequestApiTest extends AbstractClaimApiTest {

    private static final String QUESTION = "Please send the repair estimate from the workshop.";
    private static final String ANSWER = "The repair estimate is 42000 INR from Sunrise Auto Works.";

    private static String requests(String claimNumber) {
        return path(claimNumber, "/information-requests");
    }

    private static Map<String, Object> question(Object text, Object dueInDays) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("question", text);
        if (dueInDays != null) {
            body.put("dueInDays", dueInDays);
        }
        return body;
    }

    private MvcResult respond(Account claimant, String claimNumber, long id, String text) throws Exception {
        return support.post(claimant, requests(claimNumber) + "/" + id + "/respond", Map.of("responseText", text));
    }

    private MvcResult cancel(Account staff, String claimNumber, long id) throws Exception {
        return support.post(staff, requests(claimNumber) + "/" + id + "/cancel", null);
    }

    // ---- the round trip -----------------------------------------------------------------------------------------------------

    @Test
    void aRequestPutsTheClaimOnHoldAndTheAnswerReturnsItToReview() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String claimNumber = support.underReview(holder, officer);

        MvcResult created = support.post(officer, requests(claimNumber), question(QUESTION, null));

        expect(created, 201);
        assertThat(created.getResponse().getHeader("Location")).isEqualTo("/api/v1/claims/" + claimNumber
                + "/information-requests");
        long id = ((Number) read(created, "$.id")).longValue();
        assertThat((String) read(created, "$.status")).isEqualTo("OPEN");
        assertThat((Boolean) read(created, "$.overdue")).isFalse();
        assertThat((String) read(created, "$.question")).isEqualTo(QUESTION);
        assertThat((String) read(created, "$.requestedBy")).isEqualTo(officer.userId());
        assertThat(Instant.parse(read(created, "$.dueAt"))).isCloseTo(Instant.now().plus(Duration.ofDays(7)),
                within(1, ChronoUnit.MINUTES));
        MvcResult onHold = support.get(officer, path(claimNumber, ""));
        assertThat((String) read(onHold, "$.status")).isEqualTo("PENDING_INFORMATION");
        assertThat(((Number) read(onHold, "$.openInformationRequestCount")).longValue()).isEqualTo(1);

        MvcResult answered = respond(holder.account(), claimNumber, id, ANSWER);

        expect(answered, 200);
        assertThat((String) read(answered, "$.status")).isEqualTo("UNDER_REVIEW");
        assertThat(((Number) read(answered, "$.openInformationRequestCount")).longValue()).isZero();
        MvcResult list = support.get(officer, requests(claimNumber));
        assertThat((String) read(list, "$[0].status")).isEqualTo("RESPONDED");
        assertThat((String) read(list, "$[0].responseText")).isEqualTo(ANSWER);
        assertThat((String) read(list, "$[0].respondedBy")).isEqualTo(holder.account().userId());
        assertThat((String) read(list, "$[0].respondedAt")).isNotBlank();
        assertThat((List<String>) read(support.get(officer, path(claimNumber, "/timeline")), "$[*].entryType"))
                .containsExactly("SUBMITTED", "ASSIGNED", "INFORMATION_REQUESTED", "INFORMATION_PROVIDED");
        assertThat(eventTypesOf(claimNumber)).containsExactly("ClaimSubmitted", "ClaimAssigned",
                "InformationRequested", "InformationProvided");
    }

    @Test
    void theDueDateFollowsTheNumberOfDaysTheStaffMemberChose() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.underReview(support.newHolder("IN"), officer);

        MvcResult created = support.post(officer, requests(claimNumber), question(QUESTION, 3));

        expect(created, 201);
        assertThat(Instant.parse(read(created, "$.dueAt"))).isCloseTo(Instant.now().plus(Duration.ofDays(3)),
                within(1, ChronoUnit.MINUTES));
    }

    @Test
    void theTimelineEntriesCarryTheQuestionButHideTheStaffMemberFromTheClaimant() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String claimNumber = support.pendingInformation(holder, officer);

        MvcResult timeline = support.get(holder.account(), path(claimNumber, "/timeline"));

        assertThat((List<String>) read(timeline, "$[*].entryType")).containsExactly("SUBMITTED", "ASSIGNED",
                "INFORMATION_REQUESTED");
        assertThat((String) read(timeline, "$[2].message")).contains("repair estimate");
        assertThat((String) read(timeline, "$[2].actor")).isEqualTo("Claims team");
        assertThat(timeline.getResponse().getContentAsString()).doesNotContain(officer.userId());
    }

    // ---- reading ---------------------------------------------------------------------------------------------------------------

    @Test
    void aClaimantSeesTheirRequestsWithClaimsTeamAndStaffSeeTheUserId() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String claimNumber = support.pendingInformation(holder, officer);

        MvcResult claimant = support.get(holder.account(), requests(claimNumber));
        MvcResult staff = support.get(officer, requests(claimNumber));

        expect(claimant, 200);
        assertThat((String) read(claimant, "$[0].requestedBy")).isEqualTo("Claims team");
        assertThat((String) read(staff, "$[0].requestedBy")).isEqualTo(officer.userId());
    }

    @Test
    void anotherClaimantAndAnOfficerOfAnotherMarketCannotReadTheRequests() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.pendingInformation(support.newHolder("IN"), officer);

        expect(support.get(support.newHolder("IN").account(), requests(claimNumber)), 404);
        expect(support.get(support.newAccount(Role.OFFICER, "SG"), requests(claimNumber)), 404);
    }

    @Test
    void aRequestIsOverdueOnlyWhileItIsOpenAndPastItsDueTime() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String claimNumber = support.underReview(holder, officer);
        long id = support.requestInformation(claimNumber, officer);
        assertThat((Boolean) read(support.get(holder.account(), requests(claimNumber)), "$[0].overdue")).isFalse();

        InformationRequest request = informationRequests.findById(id).orElseThrow();
        request.setDueAt(Instant.now().minus(Duration.ofHours(1)));
        informationRequests.saveAndFlush(request);

        assertThat((Boolean) read(support.get(holder.account(), requests(claimNumber)), "$[0].overdue")).isTrue();
        assertThat((Boolean) read(support.get(officer, requests(claimNumber)), "$[0].overdue")).isTrue();
        // An overdue request can still be answered, and an answered one is no longer overdue.
        expect(respond(holder.account(), claimNumber, id, ANSWER), 200);
        assertThat((Boolean) read(support.get(officer, requests(claimNumber)), "$[0].overdue")).isFalse();
    }

    // ---- creating ----------------------------------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"", "too short", "123456789"})
    void aQuestionUnderTenCharactersIsRejected(String text) throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.underReview(support.newHolder("IN"), officer);

        MvcResult result = support.post(officer, requests(claimNumber), question(text, null));

        expect(result, 400);
        assertThat((List<String>) read(result, "$.errors[*].field")).contains("question");
        assertThat(support.statusOf(claimNumber, officer)).isEqualTo("UNDER_REVIEW");
    }

    @Test
    void aQuestionOverAThousandCharactersIsRejected() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.underReview(support.newHolder("IN"), officer);

        MvcResult result = support.post(officer, requests(claimNumber), question("q".repeat(1001), null));

        expect(result, 400);
        assertThat((String) read(result, "$.errors[0].field")).isEqualTo("question");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 31, 365})
    void aDueDateOutsideOneToThirtyDaysIsRejected(int days) throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.underReview(support.newHolder("IN"), officer);

        MvcResult result = support.post(officer, requests(claimNumber), question(QUESTION, days));

        expect(result, 400);
        assertThat((String) read(result, "$.errors[0].field")).isEqualTo("dueInDays");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 30})
    void theLimitsOfOneAndThirtyDaysAreAccepted(int days) throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.underReview(support.newHolder("IN"), officer);

        expect(support.post(officer, requests(claimNumber), question(QUESTION, days)), 201);
    }

    @Test
    void aRequestCanOnlyBeMadeWhileTheClaimIsUnderReview() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        Account manager = support.manager();
        String submitted = support.submitMotor(holder);
        String pending = support.pendingInformation(holder, officer);
        String escalated = support.escalated(holder, officer);
        String approved = support.approved(holder, officer);
        String rejected = support.rejected(holder, officer);

        for (String claimNumber : List.of(submitted, pending, escalated, approved, rejected)) {
            // A manager passes the authority check, so the status is what is refused.
            MvcResult result = support.post(manager, requests(claimNumber), question(QUESTION, null));
            expect(result, 409);
            assertThat(code(result)).isEqualTo("CLAIM_INVALID_TRANSITION");
        }
    }

    @Test
    void anOfficerWhoIsNotAssignedAndAClaimantCannotAskForInformation() throws Exception {
        Account assigned = support.newAccount(Role.OFFICER, "IN");
        Account other = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String claimNumber = support.underReview(holder, assigned);

        MvcResult byOther = support.post(other, requests(claimNumber), question(QUESTION, null));
        MvcResult byClaimant = support.post(holder.account(), requests(claimNumber), question(QUESTION, null));

        expect(byOther, 403);
        expect(byClaimant, 403);
        assertThat(code(byOther)).isEqualTo("ACCESS_DENIED");
        assertThat(support.statusOf(claimNumber, assigned)).isEqualTo("UNDER_REVIEW");
    }

    @Test
    void aManagerCanAskForInformationOnAClaimThatBelongsToAnOfficer() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.underReview(support.newHolder("IN"), officer);

        MvcResult result = support.post(support.manager(), requests(claimNumber), question(QUESTION, null));

        expect(result, 201);
        assertThat((String) read(result, "$.requestedBy")).isEqualTo(support.manager().userId());
    }

    // ---- answering ---------------------------------------------------------------------------------------------------------------

    @Test
    void aRequestCanBeAnsweredOnlyOnce() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String claimNumber = support.underReview(holder, officer);
        long id = support.requestInformation(claimNumber, officer);
        expect(respond(holder.account(), claimNumber, id, ANSWER), 200);
        int entries = timelineCount(claimNumber);

        MvcResult second = respond(holder.account(), claimNumber, id, "A second answer to the same question.");

        expect(second, 409);
        assertThat(code(second)).isEqualTo("INFORMATION_REQUEST_NOT_OPEN");
        assertThat(timelineCount(claimNumber)).isEqualTo(entries);
        assertThat((String) read(support.get(officer, requests(claimNumber)), "$[0].responseText")).isEqualTo(ANSWER);
    }

    @Test
    void anotherClaimantCannotAnswerAndTheRequestStaysOpen() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String claimNumber = support.underReview(holder, officer);
        long id = support.requestInformation(claimNumber, officer);

        MvcResult result = respond(support.newHolder("IN").account(), claimNumber, id, ANSWER);

        expect(result, 404);
        assertThat((String) read(support.get(officer, requests(claimNumber)), "$[0].status")).isEqualTo("OPEN");
    }

    @Test
    void staffCannotAnswerOnBehalfOfTheClaimant() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.underReview(support.newHolder("IN"), officer);
        long id = support.requestInformation(claimNumber, officer);

        MvcResult byOfficer = respond(officer, claimNumber, id, ANSWER);
        MvcResult byManager = respond(support.manager(), claimNumber, id, ANSWER);

        expect(byOfficer, 403);
        expect(byManager, 403);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "too short", "123456789"})
    void anAnswerUnderTenCharactersIsRejected(String text) throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String claimNumber = support.underReview(holder, officer);
        long id = support.requestInformation(claimNumber, officer);

        MvcResult result = respond(holder.account(), claimNumber, id, text);

        expect(result, 400);
        assertThat((List<String>) read(result, "$.errors[*].field")).contains("responseText");
        assertThat(support.statusOf(claimNumber, officer)).isEqualTo("PENDING_INFORMATION");
    }

    @Test
    void anAnswerOverTwoThousandCharactersIsRejected() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String claimNumber = support.underReview(holder, officer);
        long id = support.requestInformation(claimNumber, officer);

        MvcResult result = respond(holder.account(), claimNumber, id, "a".repeat(2001));

        expect(result, 400);
        assertThat((String) read(result, "$.errors[0].field")).isEqualTo("responseText");
    }

    // ---- request ids that belong to another claim ------------------------------------------------------------------------------------

    @Test
    void aRequestIdOfAnotherClaimIsNotFoundForTheClaimantAndForStaff() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String claimA = support.underReview(holder, officer);
        String claimB = support.underReview(holder, officer);
        long idOfA = support.requestInformation(claimA, officer);
        long idOfB = support.requestInformation(claimB, officer);

        // Each id works on its own claim only.
        expect(respond(holder.account(), claimA, idOfB, ANSWER), 404);
        expect(cancel(officer, claimA, idOfB), 404);
        expect(respond(holder.account(), claimB, idOfA, ANSWER), 404);
        expect(cancel(officer, claimB, idOfA), 404);
        assertThat(support.statusOf(claimA, officer)).isEqualTo("PENDING_INFORMATION");
        assertThat(support.statusOf(claimB, officer)).isEqualTo("PENDING_INFORMATION");
    }

    @Test
    void aRequestIdThatDoesNotExistIsNotFound() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String claimNumber = support.pendingInformation(holder, officer);

        expect(respond(holder.account(), claimNumber, 999_999_999L, ANSWER), 404);
        expect(cancel(officer, claimNumber, 999_999_999L), 404);
    }

    // ---- cancelling ----------------------------------------------------------------------------------------------------------------------

    @Test
    void cancellingTheOnlyOpenRequestReturnsTheClaimToReviewWithoutAnEvent() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.underReview(support.newHolder("IN"), officer);
        long id = support.requestInformation(claimNumber, officer);

        MvcResult result = cancel(officer, claimNumber, id);

        expect(result, 200);
        assertThat((String) read(result, "$.status")).isEqualTo("UNDER_REVIEW");
        assertThat((String) read(support.get(officer, requests(claimNumber)), "$[0].status")).isEqualTo("CANCELLED");
        MvcResult timeline = support.get(officer, path(claimNumber, "/timeline"));
        assertThat((List<String>) read(timeline, "$[*].entryType")).containsExactly("SUBMITTED", "ASSIGNED",
                "INFORMATION_REQUESTED", "INFORMATION_REQUEST_CANCELLED");
        assertThat(eventTypesOf(claimNumber)).containsExactly("ClaimSubmitted", "ClaimAssigned", "InformationRequested");
    }

    @Test
    void aCancelledRequestCanNeitherBeAnsweredNorCancelledAgain() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String claimNumber = support.underReview(holder, officer);
        long id = support.requestInformation(claimNumber, officer);
        expect(cancel(officer, claimNumber, id), 200);

        MvcResult answer = respond(holder.account(), claimNumber, id, ANSWER);
        MvcResult again = cancel(officer, claimNumber, id);

        expect(answer, 409);
        expect(again, 409);
        assertThat(code(answer)).isEqualTo("INFORMATION_REQUEST_NOT_OPEN");
        assertThat(code(again)).isEqualTo("INFORMATION_REQUEST_NOT_OPEN");
    }

    @Test
    void anAnsweredRequestCannotBeCancelled() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String claimNumber = support.underReview(holder, officer);
        long id = support.requestInformation(claimNumber, officer);
        support.respond(claimNumber, id, holder.account());

        MvcResult result = cancel(officer, claimNumber, id);

        expect(result, 409);
        assertThat(code(result)).isEqualTo("INFORMATION_REQUEST_NOT_OPEN");
    }

    @Test
    void aClaimantAndAnOfficerWhoIsNotAssignedCannotCancel() throws Exception {
        Account assigned = support.newAccount(Role.OFFICER, "IN");
        Account other = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String claimNumber = support.underReview(holder, assigned);
        long id = support.requestInformation(claimNumber, assigned);

        expect(cancel(holder.account(), claimNumber, id), 403);
        MvcResult byOther = cancel(other, claimNumber, id);
        expect(byOther, 403);
        assertThat(code(byOther)).isEqualTo("ACCESS_DENIED");
        assertThat(support.statusOf(claimNumber, assigned)).isEqualTo("PENDING_INFORMATION");
    }

    // ---- rejecting a claim that waits for information -----------------------------------------------------------------------------------

    @Test
    void rejectingAClaimThatWaitsForInformationCancelsItsOpenRequest() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String claimNumber = support.underReview(holder, officer);
        long id = support.requestInformation(claimNumber, officer);

        MvcResult result = support.post(officer, path(claimNumber, "/reject"), rejectionBody());

        expect(result, 200);
        assertThat((String) read(result, "$.status")).isEqualTo("REJECTED");
        assertThat(((Number) read(result, "$.openInformationRequestCount")).longValue()).isZero();
        assertThat((String) read(support.get(officer, requests(claimNumber)), "$[0].status")).isEqualTo("CANCELLED");
        MvcResult timeline = support.get(officer, path(claimNumber, "/timeline"));
        assertThat((List<String>) read(timeline, "$[*].entryType")).containsExactly("SUBMITTED", "ASSIGNED",
                "INFORMATION_REQUESTED", "INFORMATION_REQUEST_CANCELLED", "REJECTED");
        expect(respond(holder.account(), claimNumber, id, ANSWER), 409);
    }
}
