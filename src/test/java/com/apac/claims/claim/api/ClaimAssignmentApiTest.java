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

import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.identity.domain.Role;
import com.apac.claims.support.AbstractClaimApiTest;
import com.apac.claims.support.ClaimTestSupport.Account;
import com.apac.claims.support.ClaimTestSupport.Holder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Who works on a claim: pick-up from the queue, manager assignment and reassignment, and the race of several people
 * reaching for the same waiting claim. The race tests use real threads and real transactions, so the version column of
 * the claim is what decides the winner.
 */
class ClaimAssignmentApiTest extends AbstractClaimApiTest {

    private static String assign(String claimNumber) {
        return path(claimNumber, "/assign");
    }

    private static Map<String, String> target(String userId) {
        return Map.of("officerUserId", userId);
    }

    private List<String> timelineTypes(Account viewer, String claimNumber) throws Exception {
        MvcResult result = support.get(viewer, path(claimNumber, "/timeline"));
        expect(result, 200);
        return read(result, "$[*].entryType");
    }

    // ---- pick-up -------------------------------------------------------------------------------------------------------

    @Test
    void anOfficerPicksUpAWaitingClaim() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.submitMotor(support.newHolder("IN"));

        MvcResult result = support.post(officer, path(claimNumber, "/pick-up"), null);

        expect(result, 200);
        assertThat((String) read(result, "$.status")).isEqualTo("UNDER_REVIEW");
        assertThat((String) read(result, "$.assignedOfficerUserId")).isEqualTo(officer.userId());
        MvcResult timeline = support.get(officer, path(claimNumber, "/timeline"));
        assertThat((List<String>) read(timeline, "$[*].entryType")).containsExactly("SUBMITTED", "ASSIGNED");
        assertThat((String) read(timeline, "$[1].fromStatus")).isEqualTo("SUBMITTED");
        assertThat((String) read(timeline, "$[1].toStatus")).isEqualTo("UNDER_REVIEW");
        assertThat((String) read(timeline, "$[1].actor")).isEqualTo(officer.userId());
        assertThat((String) read(timeline, "$[1].visibility")).isEqualTo("PUBLIC");
        assertThat(eventTypesOf(claimNumber)).containsExactly("ClaimSubmitted", "ClaimAssigned");
    }

    @Test
    void thePickUpRecordsWhenTheClaimWasAssigned() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.submitMotor(support.newHolder("IN"));

        expect(support.post(officer, path(claimNumber, "/pick-up"), null), 200);

        assertThat(claims.findByClaimNumber(claimNumber).orElseThrow().getAssignedAt()).isNotNull();
    }

    @Test
    void aSecondPickUpOfTheSameClaimIsRefusedAndChangesNothing() throws Exception {
        Account first = support.newAccount(Role.OFFICER, "IN");
        Account second = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.submitMotor(support.newHolder("IN"));
        expect(support.post(first, path(claimNumber, "/pick-up"), null), 200);
        int entries = timelineCount(claimNumber);

        MvcResult byOther = support.post(second, path(claimNumber, "/pick-up"), null);
        MvcResult byTheSameOfficer = support.post(first, path(claimNumber, "/pick-up"), null);

        expect(byOther, 409);
        expect(byTheSameOfficer, 409);
        assertThat(code(byOther)).isEqualTo("CLAIM_ALREADY_ASSIGNED");
        assertThat(code(byTheSameOfficer)).isEqualTo("CLAIM_ALREADY_ASSIGNED");
        assertThat(timelineCount(claimNumber)).isEqualTo(entries);
        assertThat(eventTypesOf(claimNumber)).containsExactly("ClaimSubmitted", "ClaimAssigned");
        MvcResult detail = support.get(first, path(claimNumber, ""));
        assertThat((String) read(detail, "$.assignedOfficerUserId")).isEqualTo(first.userId());
    }

    @Test
    void aClaimThatIsWaitingForInformationOrEscalatedCannotBePickedUpEither() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Account other = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String pending = support.pendingInformation(holder, officer);
        String escalated = support.escalated(holder, officer);

        for (String claimNumber : List.of(pending, escalated)) {
            MvcResult result = support.post(other, path(claimNumber, "/pick-up"), null);
            expect(result, 409);
            assertThat(code(result)).isEqualTo("CLAIM_ALREADY_ASSIGNED");
        }
    }

    @Test
    void aDecidedClaimCannotBePickedUpAndTheAnswerIsAnInvalidTransition() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String approved = support.approved(holder, officer);
        String rejected = support.rejected(holder, officer);

        for (String claimNumber : List.of(approved, rejected)) {
            MvcResult result = support.post(officer, path(claimNumber, "/pick-up"), null);
            expect(result, 409);
            assertThat(code(result)).isEqualTo("CLAIM_INVALID_TRANSITION");
            assertThat((String) read(result, "$.detail")).contains("cannot be picked up");
        }
    }

    @Test
    void aClaimantCannotPickUpAClaim() throws Exception {
        Holder holder = support.newHolder("IN");
        String claimNumber = support.submitMotor(holder);

        MvcResult result = support.post(holder.account(), path(claimNumber, "/pick-up"), null);

        expect(result, 403);
        assertThat(code(result)).isEqualTo("ACCESS_DENIED");
        assertThat(support.statusOf(claimNumber, holder.account())).isEqualTo("SUBMITTED");
    }

    @Test
    void anOfficerOfAnotherMarketDoesNotEvenSeeTheClaimAndCannotPickItUp() throws Exception {
        Account officerSg = support.newAccount(Role.OFFICER, "SG");
        Holder holder = support.newHolder("IN");
        String claimNumber = support.submitMotor(holder);

        MvcResult result = support.post(officerSg, path(claimNumber, "/pick-up"), null);

        expect(result, 404);
        assertThat(code(result)).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(support.statusOf(claimNumber, holder.account())).isEqualTo("SUBMITTED");
    }

    @Test
    void aManagerCanPickUpAClaimOfAnyMarket() throws Exception {
        Account manager = support.manager();
        String claimNumber = support.submitMotor(support.newHolder("JP"));

        MvcResult result = support.post(manager, path(claimNumber, "/pick-up"), null);

        expect(result, 200);
        assertThat((String) read(result, "$.assignedOfficerUserId")).isEqualTo(manager.userId());
        assertThat((String) read(result, "$.status")).isEqualTo("UNDER_REVIEW");
    }

    @Test
    void pickingUpAClaimThatDoesNotExistIsNotFoundAndWithoutATokenUnauthenticated() throws Exception {
        MvcResult missing = support.post(support.seeded(Role.OFFICER, "IN"), path("CLM-99999999", "/pick-up"), null);
        MvcResult anonymous = support.post(null, path("CLM-99999999", "/pick-up"), null);

        expect(missing, 404);
        expect(anonymous, 401);
    }

    // ---- the race ------------------------------------------------------------------------------------------------------

    /** Fires the same request from every account at the same moment and returns the answers in the order of the accounts. */
    private List<MvcResult> pickUpAtTheSameTime(String claimNumber, List<Account> accounts) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(accounts.size());
        try {
            CountDownLatch ready = new CountDownLatch(accounts.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (Account account : accounts) {
                Callable<MvcResult> call = () -> {
                    ready.countDown();
                    go.await();
                    return support.post(account, path(claimNumber, "/pick-up"), null);
                };
                futures.add(pool.submit(call));
            }
            ready.await();
            go.countDown();
            List<MvcResult> results = new ArrayList<>();
            for (Future<MvcResult> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void twoPickUpsAtTheSameTimeGiveExactlyOneSuccessAndOneAlreadyAssigned() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Account manager = support.manager();
        Holder holder = support.newHolder("IN");

        // One race can end with the loser seeing the new status instead of the version clash, so several rounds are run.
        for (int round = 0; round < 5; round++) {
            String claimNumber = support.submitMotor(holder);

            List<MvcResult> results = pickUpAtTheSameTime(claimNumber, List.of(officer, manager));

            List<Integer> statuses = results.stream().map(result -> result.getResponse().getStatus()).toList();
            assertThat(statuses).as("round %d", round).containsExactlyInAnyOrder(200, 409);
            MvcResult loser = results.get(0).getResponse().getStatus() == 409 ? results.get(0) : results.get(1);
            assertThat(code(loser)).isEqualTo("CLAIM_ALREADY_ASSIGNED");
            String winner = results.get(0).getResponse().getStatus() == 200 ? officer.userId() : manager.userId();
            MvcResult detail = support.get(manager, path(claimNumber, ""));
            assertThat((String) read(detail, "$.assignedOfficerUserId")).isEqualTo(winner);
            assertThat((String) read(detail, "$.status")).isEqualTo("UNDER_REVIEW");
            assertThat(timelineTypes(manager, claimNumber)).containsExactly("SUBMITTED", "ASSIGNED");
            assertThat(eventTypesOf(claimNumber)).containsExactly("ClaimSubmitted", "ClaimAssigned");
        }
    }

    @Test
    void sixOfficersReachingForOneClaimGiveOneSuccessAndFiveAlreadyAssigned() throws Exception {
        List<Account> officers = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            officers.add(support.newAccount(Role.OFFICER, "IN"));
        }
        String claimNumber = support.submitMotor(support.newHolder("IN"));

        List<MvcResult> results = pickUpAtTheSameTime(claimNumber, officers);

        List<Integer> statuses = results.stream().map(result -> result.getResponse().getStatus()).toList();
        assertThat(statuses.stream().filter(status -> status == 200)).hasSize(1);
        assertThat(statuses.stream().filter(status -> status == 409)).hasSize(5);
        for (MvcResult result : results) {
            if (result.getResponse().getStatus() == 409) {
                assertThat(code(result)).isEqualTo("CLAIM_ALREADY_ASSIGNED");
            }
        }
        assertThat(timelineTypes(support.manager(), claimNumber)).containsExactly("SUBMITTED", "ASSIGNED");
        assertThat(eventTypesOf(claimNumber)).containsExactly("ClaimSubmitted", "ClaimAssigned");
    }

    // ---- assignment by a manager -----------------------------------------------------------------------------------------

    @Test
    void aManagerAssignsAWaitingClaimToAnOfficerOfTheClaimsMarket() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String claimNumber = support.submitMotor(holder);

        MvcResult result = support.post(support.manager(), assign(claimNumber), target(officer.userId()));

        expect(result, 200);
        assertThat((String) read(result, "$.status")).isEqualTo("UNDER_REVIEW");
        assertThat((String) read(result, "$.assignedOfficerUserId")).isEqualTo(officer.userId());
        MvcResult timeline = support.get(support.manager(), path(claimNumber, "/timeline"));
        assertThat((List<String>) read(timeline, "$[*].entryType")).containsExactly("SUBMITTED", "ASSIGNED");
        assertThat((String) read(timeline, "$[1].actor")).isEqualTo(support.manager().userId());
        assertThat((String) read(timeline, "$[1].visibility")).isEqualTo("PUBLIC");
        assertThat((String) read(timeline, "$[1].message")).doesNotContain(officer.userId());
        assertThat(eventTypesOf(claimNumber)).containsExactly("ClaimSubmitted", "ClaimAssigned");
        String payload = jdbc.queryForObject("select payload from outbox_events where aggregate_id = ? "
                + "and event_type = 'ClaimAssigned'", String.class, claimNumber);
        assertThat(payload).contains(officer.userId());
        // The officer can now work on the claim: the claimant sees the new status but no officer id.
        MvcResult claimantView = support.get(holder.account(), path(claimNumber, ""));
        assertThat((String) read(claimantView, "$.status")).isEqualTo("UNDER_REVIEW");
        assertThat(claimantView.getResponse().getContentAsString()).doesNotContain(officer.userId());
    }

    @Test
    void aManagerReassignsAClaimUnderReviewAndTheStatusStays() throws Exception {
        Account first = support.newAccount(Role.OFFICER, "IN");
        Account second = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String claimNumber = support.underReview(holder, first);

        MvcResult result = support.post(support.manager(), assign(claimNumber), target(second.userId()));

        expect(result, 200);
        assertThat((String) read(result, "$.status")).isEqualTo("UNDER_REVIEW");
        assertThat((String) read(result, "$.assignedOfficerUserId")).isEqualTo(second.userId());
        MvcResult timeline = support.get(support.manager(), path(claimNumber, "/timeline"));
        assertThat((List<String>) read(timeline, "$[*].entryType")).containsExactly("SUBMITTED", "ASSIGNED", "REASSIGNED");
        assertThat((String) read(timeline, "$[2].visibility")).isEqualTo("INTERNAL");
        assertThat((String) read(timeline, "$[2].message")).contains(first.userId(), second.userId());
        // A reassignment is internal: no event, and the claimant never sees the entry.
        assertThat(eventTypesOf(claimNumber)).containsExactly("ClaimSubmitted", "ClaimAssigned");
        assertThat((List<String>) read(support.get(holder.account(), path(claimNumber, "/timeline")), "$[*].entryType"))
                .containsExactly("SUBMITTED", "ASSIGNED");
        // The new officer can act and the old one no longer can.
        expect(support.assess(claimNumber, second, new BigDecimal("1000.00"), true, "APPROVE"), 201);
        MvcResult oldOfficer = support.post(first, path(claimNumber, "/assessments"), Map.of(
                "assessedLossAmount", 1000, "coverageConfirmed", true, "recommendation", "APPROVE"));
        expect(oldOfficer, 403);
    }

    @Test
    void reassigningAClaimThatWaitsForInformationKeepsItPendingInformation() throws Exception {
        Account first = support.newAccount(Role.OFFICER, "IN");
        Account second = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.pendingInformation(support.newHolder("IN"), first);

        MvcResult result = support.post(support.manager(), assign(claimNumber), target(second.userId()));

        expect(result, 200);
        assertThat((String) read(result, "$.status")).isEqualTo("PENDING_INFORMATION");
        assertThat((String) read(result, "$.assignedOfficerUserId")).isEqualTo(second.userId());
    }

    @Test
    void reassigningAnEscalatedClaimKeepsItEscalated() throws Exception {
        Account first = support.newAccount(Role.OFFICER, "IN");
        Account second = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.escalated(support.newHolder("IN"), first);

        MvcResult result = support.post(support.manager(), assign(claimNumber), target(second.userId()));

        expect(result, 200);
        assertThat((String) read(result, "$.status")).isEqualTo("ESCALATED");
        assertThat((String) read(result, "$.assignedOfficerUserId")).isEqualTo(second.userId());
    }

    @Test
    void theOfficerUserIdIsNotCaseSensitiveAndSurroundingSpacesAreIgnored() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.submitMotor(support.newHolder("IN"));

        MvcResult result = support.post(support.manager(), assign(claimNumber),
                target("  " + officer.userId().toUpperCase() + " "));

        expect(result, 200);
        assertThat((String) read(result, "$.assignedOfficerUserId")).isEqualTo(officer.userId());
    }

    @Test
    void anOfficerOfAnotherMarketCannotBeAssigned() throws Exception {
        Account officerSg = support.newAccount(Role.OFFICER, "SG");
        Holder holder = support.newHolder("IN");
        String claimNumber = support.submitMotor(holder);

        MvcResult result = support.post(support.manager(), assign(claimNumber), target(officerSg.userId()));

        expect(result, 422);
        assertThat((String) read(result, "$.detail")).contains("officerUserId").contains("IN");
        assertThat(support.statusOf(claimNumber, holder.account())).isEqualTo("SUBMITTED");
    }

    @Test
    void anInactiveOfficerCannotBeAssigned() throws Exception {
        AppUser inactive = support.createUser(Role.OFFICER, "IN", false);
        String claimNumber = support.submitMotor(support.newHolder("IN"));

        MvcResult result = support.post(support.manager(), assign(claimNumber), target(inactive.getUserId()));

        expect(result, 422);
        assertThat((String) read(result, "$.detail")).contains("officerUserId");
    }

    @ParameterizedTest
    @ValueSource(strings = {"claimant.in", "manager.apac", "nobody.at.all"})
    void aTargetThatIsNotAnOfficerOrDoesNotExistIsRefusedWithTheSameMessage(String userId) throws Exception {
        String claimNumber = support.submitMotor(support.newHolder("IN"));

        MvcResult result = support.post(support.manager(), assign(claimNumber), target(userId));

        expect(result, 422);
        assertThat((String) read(result, "$.detail")).isEqualTo("officerUserId must be an active officer of market IN.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void aMissingOfficerUserIdIsABadRequest(String userId) throws Exception {
        String claimNumber = support.submitMotor(support.newHolder("IN"));

        MvcResult result = support.post(support.manager(), assign(claimNumber), target(userId));

        expect(result, 400);
        assertThat((String) read(result, "$.errors[0].field")).isEqualTo("officerUserId");
    }

    @Test
    void reassigningToTheOfficerWhoAlreadyHasTheClaimIsRefused() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.underReview(support.newHolder("IN"), officer);
        int entries = timelineCount(claimNumber);

        MvcResult result = support.post(support.manager(), assign(claimNumber), target(officer.userId()));

        expect(result, 422);
        assertThat(timelineCount(claimNumber)).isEqualTo(entries);
    }

    @Test
    void anOfficerAndAClaimantCannotAssign() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String claimNumber = support.submitMotor(holder);

        MvcResult byOfficer = support.post(officer, assign(claimNumber), target(officer.userId()));
        MvcResult byClaimant = support.post(holder.account(), assign(claimNumber), target(officer.userId()));

        expect(byOfficer, 403);
        expect(byClaimant, 403);
        assertThat(code(byOfficer)).isEqualTo("ACCESS_DENIED");
        assertThat(support.statusOf(claimNumber, holder.account())).isEqualTo("SUBMITTED");
    }

    @Test
    void aDecidedClaimCannotBeAssigned() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Account other = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String approved = support.approved(holder, officer);
        String rejected = support.rejected(holder, officer);

        for (String claimNumber : List.of(approved, rejected)) {
            MvcResult result = support.post(support.manager(), assign(claimNumber), target(other.userId()));
            expect(result, 409);
            assertThat(code(result)).isEqualTo("CLAIM_INVALID_TRANSITION");
        }
    }

    @Test
    void assigningAClaimThatDoesNotExistIsNotFound() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");

        MvcResult result = support.post(support.manager(), assign("CLM-99999999"), target(officer.userId()));

        expect(result, 404);
    }
}
