package com.apac.claims.claim.api;

import static com.apac.claims.support.ClaimTestSupport.code;
import static com.apac.claims.support.ClaimTestSupport.expect;
import static com.apac.claims.support.ClaimTestSupport.path;
import static com.apac.claims.support.ClaimTestSupport.read;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import com.apac.claims.identity.domain.Role;
import com.apac.claims.support.AbstractClaimApiTest;
import com.apac.claims.support.ClaimTestSupport.Account;
import com.apac.claims.support.ClaimTestSupport.Holder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Who sees which claims: the scope of a claimant, an officer and a manager, the list filters, paging and sorting, and
 * the officer queue. Lists are checked against the claims of a claimant created for the test wherever an exact answer
 * is needed, because the database is shared with every other test.
 */
class ClaimVisibilityApiTest extends AbstractClaimApiTest {

    private static List<String> numbers(MvcResult page) throws Exception {
        return read(page, "$.content[*].claimNumber");
    }

    /** Every claim number of the queue of the account, read page by page. */
    private List<String> wholeQueue(Account staff) throws Exception {
        List<String> all = new ArrayList<>();
        for (int page = 0; ; page++) {
            MvcResult result = support.get(staff, "/api/v1/claims/queue?size=50&page=" + page);
            expect(result, 200);
            all.addAll(numbers(result));
            if (Boolean.TRUE.equals(read(result, "$.last"))) {
                return all;
            }
        }
    }

    // ---- claimant scope --------------------------------------------------------------------------------------------

    @Test
    void aClaimantSeesOnlyTheirOwnClaimsInTheList() throws Exception {
        Holder mine = support.newHolder("IN");
        Holder other = support.newHolder("IN");
        String first = support.submitMotor(mine);
        String second = support.submitProperty(mine);
        String foreign = support.submitMotor(other);

        MvcResult result = support.get(mine.account(), "/api/v1/claims");

        expect(result, 200);
        assertThat(numbers(result)).containsExactlyInAnyOrder(first, second).doesNotContain(foreign);
        assertThat(((Number) read(result, "$.totalElements")).longValue()).isEqualTo(2);
    }

    @Test
    void aClaimantGets404ForAnotherClaimantsClaimInEveryClaimEndpoint() throws Exception {
        Holder mine = support.newHolder("IN");
        Holder other = support.newHolder("IN");
        String foreign = support.submitMotor(other);

        for (String suffix : List.of("", "/timeline", "/information-requests")) {
            MvcResult result = support.get(mine.account(), path(foreign, suffix));
            expect(result, 404);
            assertThat(code(result)).isEqualTo("RESOURCE_NOT_FOUND");
        }
    }

    @Test
    void aClaimantThatCannotSeeAClaimGetsTheSameAnswerAsForAClaimThatDoesNotExist() throws Exception {
        Holder mine = support.newHolder("IN");
        String foreign = support.submitMotor(support.newHolder("IN"));

        MvcResult exists = support.get(mine.account(), path(foreign, ""));
        MvcResult missing = support.get(mine.account(), path("CLM-99999999", ""));

        expect(exists, 404);
        expect(missing, 404);
        assertThat((String) read(exists, "$.title")).isEqualTo((String) read(missing, "$.title"));
        assertThat((String) read(exists, "$.code")).isEqualTo((String) read(missing, "$.code"));
    }

    // ---- officer and manager scope ---------------------------------------------------------------------------------------

    @Test
    void anOfficerSeesTheClaimsOfTheirOwnMarketOnly() throws Exception {
        Account officerIn = support.newAccount(Role.OFFICER, "IN");
        String inClaim = support.submitMotor(support.newHolder("IN"));
        String sgClaim = support.submitMotor(support.newHolder("SG"));

        expect(support.get(officerIn, path(inClaim, "")), 200);
        MvcResult foreign = support.get(officerIn, path(sgClaim, ""));
        expect(foreign, 404);
        assertThat(code(foreign)).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(numbers(support.get(officerIn, "/api/v1/claims?size=50"))).contains(inClaim).doesNotContain(sgClaim);
        MvcResult otherMarket = support.get(officerIn, "/api/v1/claims?marketCode=SG");
        expect(otherMarket, 200);
        assertThat(numbers(otherMarket)).isEmpty();
    }

    @Test
    void anOfficerOfAnotherMarketCannotReadTheTimelineOrTheRequestsOfAClaim() throws Exception {
        Account officerSg = support.newAccount(Role.OFFICER, "SG");
        String inClaim = support.submitMotor(support.newHolder("IN"));

        for (String suffix : List.of("/timeline", "/information-requests", "/assessments")) {
            expect(support.get(officerSg, path(inClaim, suffix)), 404);
        }
    }

    @Test
    void aManagerSeesTheClaimsOfEveryMarket() throws Exception {
        Account manager = support.manager();
        String inClaim = support.submitMotor(support.newHolder("IN"));
        String sgClaim = support.submitMotor(support.newHolder("SG"));
        String jpClaim = support.submitMotor(support.newHolder("JP"));

        for (String claimNumber : List.of(inClaim, sgClaim, jpClaim)) {
            expect(support.get(manager, path(claimNumber, "")), 200);
        }
        assertThat(numbers(support.get(manager, "/api/v1/claims?marketCode=SG&size=50"))).contains(sgClaim)
                .doesNotContain(inClaim, jpClaim);
        assertThat(numbers(support.get(manager, "/api/v1/claims?marketCode=jp&size=50"))).contains(jpClaim);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLM-99999999", "CLP-1", "NOT-A-CLAIM", "12345"})
    void anUnknownClaimNumberIsNotFoundForEveryRole(String claimNumber) throws Exception {
        for (Account account : List.of(support.seeded(Role.CLAIMANT, "IN"), support.seeded(Role.OFFICER, "IN"),
                support.manager())) {
            MvcResult result = support.get(account, path(claimNumber, ""));
            expect(result, 404);
            assertThat(code(result)).isEqualTo("RESOURCE_NOT_FOUND");
        }
    }

    @Test
    void aClaimNumberIsNotCaseSensitive() throws Exception {
        Holder holder = support.newHolder("IN");
        String claimNumber = support.submitMotor(holder);

        MvcResult result = support.get(holder.account(), path(claimNumber.toLowerCase(), ""));

        expect(result, 200);
        assertThat((String) read(result, "$.claimNumber")).isEqualTo(claimNumber);
    }

    // ---- filters ------------------------------------------------------------------------------------------------------

    @Test
    void assignedToMeListsOnlyTheClaimsOfTheCaller() throws Exception {
        Holder holder = support.newHolder("IN");
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String taken1 = support.underReview(holder, officer);
        String taken2 = support.underReview(holder, officer);
        String waiting = support.submitMotor(holder);

        MvcResult mine = support.get(officer, "/api/v1/claims?assignedTo=me");
        MvcResult byId = support.get(support.manager(), "/api/v1/claims?assignedTo=" + officer.userId().toUpperCase());
        MvcResult claimants = support.get(holder.account(), "/api/v1/claims?assignedTo=me");

        expect(mine, 200);
        assertThat(numbers(mine)).containsExactlyInAnyOrder(taken1, taken2).doesNotContain(waiting);
        assertThat(numbers(byId)).containsExactlyInAnyOrder(taken1, taken2);
        assertThat(numbers(claimants)).isEmpty();
    }

    @Test
    void theUnassignedFilterSeparatesWaitingClaimsFromClaimsWithAnOfficer() throws Exception {
        Holder holder = support.newHolder("IN");
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String waiting = support.submitMotor(holder);
        String taken = support.underReview(holder, officer);

        assertThat(numbers(support.get(holder.account(), "/api/v1/claims?unassigned=true"))).containsExactly(waiting);
        assertThat(numbers(support.get(holder.account(), "/api/v1/claims?unassigned=false"))).containsExactly(taken);
    }

    @Test
    void theStatusAndTypeFiltersNarrowTheList() throws Exception {
        Holder holder = support.newHolder("IN");
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String submitted = support.submitMotor(holder);
        String inReview = support.underReview(holder, officer);
        String property = support.submitProperty(holder);

        assertThat(numbers(support.get(holder.account(), "/api/v1/claims?status=UNDER_REVIEW"))).containsExactly(inReview);
        assertThat(numbers(support.get(holder.account(), "/api/v1/claims?status=SUBMITTED")))
                .containsExactlyInAnyOrder(submitted, property);
        assertThat(numbers(support.get(holder.account(), "/api/v1/claims?claimType=PROPERTY"))).containsExactly(property);
        assertThat(numbers(support.get(holder.account(), "/api/v1/claims?claimType=MOTOR&status=SUBMITTED")))
                .containsExactly(submitted);
    }

    @Test
    void anUnknownStatusOrTypeIsABadRequestThatListsTheAllowedValues() throws Exception {
        Holder holder = support.newHolder("IN");

        MvcResult status = support.get(holder.account(), "/api/v1/claims?status=DONE");
        MvcResult type = support.get(holder.account(), "/api/v1/claims?claimType=BOAT");

        expect(status, 400);
        expect(type, 400);
        assertThat(code(status)).isEqualTo("VALIDATION_FAILED");
        assertThat((String) read(status, "$.errors[0].field")).isEqualTo("status");
        assertThat((String) read(status, "$.errors[0].message")).contains("SUBMITTED", "UNDER_REVIEW");
    }

    @Test
    void theSubmissionDateRangeIsInclusiveAtBothEnds() throws Exception {
        Holder holder = support.newHolder("IN");
        String claimNumber = support.submitMotor(holder);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        assertThat(numbers(support.get(holder.account(), "/api/v1/claims?submittedFrom=" + today
                + "&submittedTo=" + today))).containsExactly(claimNumber);
        assertThat(numbers(support.get(holder.account(), "/api/v1/claims?submittedTo=" + today.minusDays(1))))
                .isEmpty();
        assertThat(numbers(support.get(holder.account(), "/api/v1/claims?submittedFrom=" + today.plusDays(1))))
                .isEmpty();
    }

    @Test
    void aDateRangeThatEndsBeforeItStartsIsABadRequest() throws Exception {
        MvcResult result = support.get(support.manager(), "/api/v1/claims?submittedFrom=2026-10-10&submittedTo=2026-10-01");

        expect(result, 400);
        assertThat((String) read(result, "$.errors[0].field")).isEqualTo("submittedFrom");
    }

    @Test
    void aBadDateIsABadRequest() throws Exception {
        MvcResult result = support.get(support.manager(), "/api/v1/claims?submittedFrom=yesterday");

        expect(result, 400);
        assertThat(code(result)).isEqualTo("VALIDATION_FAILED");
    }

    // ---- paging and sorting --------------------------------------------------------------------------------------------

    @Test
    void pagingSplitsTheListAndTheEnvelopeDescribesIt() throws Exception {
        Holder holder = support.newHolder("IN");
        for (int i = 0; i < 3; i++) {
            support.submitMotor(holder);
        }

        MvcResult first = support.get(holder.account(), "/api/v1/claims?size=2&page=0");
        MvcResult second = support.get(holder.account(), "/api/v1/claims?size=2&page=1");

        assertThat(numbers(first)).hasSize(2);
        assertThat(numbers(second)).hasSize(1);
        assertThat((Boolean) read(first, "$.last")).isFalse();
        assertThat((Boolean) read(second, "$.last")).isTrue();
        assertThat(((Number) read(first, "$.totalElements")).longValue()).isEqualTo(3);
        assertThat((Integer) read(first, "$.totalPages")).isEqualTo(2);
        assertThat(numbers(first)).doesNotContainAnyElementsOf(numbers(second));
    }

    @Test
    void theDefaultPageSizeIsTenAndALargerSizeIsCappedAtFifty() throws Exception {
        Holder holder = support.newHolder("IN");

        assertThat((Integer) read(support.get(holder.account(), "/api/v1/claims"), "$.size")).isEqualTo(10);
        assertThat((Integer) read(support.get(holder.account(), "/api/v1/claims?size=500"), "$.size")).isEqualTo(50);
    }

    @Test
    void aNegativePageOrASizeBelowOneIsABadRequest() throws Exception {
        Holder holder = support.newHolder("IN");

        MvcResult page = support.get(holder.account(), "/api/v1/claims?page=-1");
        MvcResult size = support.get(holder.account(), "/api/v1/claims?size=0");

        expect(page, 400);
        expect(size, 400);
        assertThat((String) read(page, "$.errors[0].field")).isEqualTo("page");
        assertThat((String) read(size, "$.errors[0].field")).isEqualTo("size");
    }

    @Test
    void theListCanBeSortedByAmountInBothDirections() throws Exception {
        Holder holder = support.newHolder("IN");
        String small = support.submitMotor(holder, new BigDecimal("100.00"));
        String large = support.submitMotor(holder, new BigDecimal("900.00"));
        String middle = support.submitMotor(holder, new BigDecimal("500.00"));

        assertThat(numbers(support.get(holder.account(), "/api/v1/claims?sort=claimedAmount,asc")))
                .containsExactly(small, middle, large);
        assertThat(numbers(support.get(holder.account(), "/api/v1/claims?sort=claimedAmount,desc")))
                .containsExactly(large, middle, small);
    }

    @Test
    void theDefaultOrderIsNewestFirst() throws Exception {
        Holder holder = support.newHolder("IN");
        String older = support.submitMotor(holder);
        String newer = support.submitMotor(holder);

        assertThat(numbers(support.get(holder.account(), "/api/v1/claims"))).containsExactly(newer, older);
    }

    @ParameterizedTest
    @ValueSource(strings = {"password", "claimant.passwordHash", "status,sideways", "claimedAmount,asc,extra"})
    void aSortFieldOutsideTheWhitelistOrAMalformedSortIsABadRequest(String sort) throws Exception {
        MvcResult result = support.get(support.manager(), "/api/v1/claims?sort=" + sort);

        expect(result, 400);
        assertThat((String) read(result, "$.errors[0].field")).isEqualTo("sort");
    }

    // ---- the queue -----------------------------------------------------------------------------------------------------

    @Test
    void aClaimantCannotReadTheQueue() throws Exception {
        MvcResult result = support.get(support.seeded(Role.CLAIMANT, "IN"), "/api/v1/claims/queue");

        expect(result, 403);
        assertThat(code(result)).isEqualTo("ACCESS_DENIED");
    }

    @Test
    void theQueueIsTheLiteralPathAndNotAClaimNumber() throws Exception {
        MvcResult result = support.get(support.seeded(Role.OFFICER, "IN"), "/api/v1/claims/queue");

        expect(result, 200);
        assertThat(result.getResponse().getContentAsString()).contains("\"content\"").doesNotContain("RESOURCE_NOT_FOUND");
    }

    @Test
    void theQueueHoldsOnlySubmittedUnassignedClaimsOfTheOfficersMarket() throws Exception {
        Account officerIn = support.newAccount(Role.OFFICER, "IN");
        Account officerSg = support.newAccount(Role.OFFICER, "SG");
        Holder inHolder = support.newHolder("IN");
        String waiting = support.submitMotor(inHolder);
        String taken = support.underReview(inHolder, officerIn);
        String sgWaiting = support.submitMotor(support.newHolder("SG"));

        List<String> inQueue = wholeQueue(officerIn);
        List<String> sgQueue = wholeQueue(officerSg);
        List<String> managerQueue = wholeQueue(support.manager());

        assertThat(inQueue).contains(waiting).doesNotContain(taken, sgWaiting);
        assertThat(sgQueue).contains(sgWaiting).doesNotContain(waiting, taken);
        assertThat(managerQueue).contains(waiting, sgWaiting).doesNotContain(taken);
    }

    @Test
    void theQueueIsOldestFirstAndEachItemShowsItsAgeAndWhetherItBreachesTheTarget() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        String old = support.submitMotor(holder);
        String recent = support.submitMotor(holder);
        // The target is 48 hours; the claim "was submitted" three days ago.
        jdbc.update("update claims set created_at = ? where claim_number = ?",
                OffsetDateTime.now(ZoneOffset.UTC).minusHours(73), old);

        List<String> queue = wholeQueue(officer);
        assertThat(queue).contains(old, recent);
        assertThat(queue.indexOf(old)).isLessThan(queue.indexOf(recent));

        MvcResult page = support.get(officer, "/api/v1/claims/queue?size=50&page=" + pageOf(queue, old));
        List<Number> ages = read(page, "$.content[?(@.claimNumber=='" + old + "')].ageHours");
        List<Boolean> breaches = read(page, "$.content[?(@.claimNumber=='" + old + "')].slaBreached");
        assertThat(ages.get(0).longValue()).isGreaterThanOrEqualTo(73);
        assertThat(breaches.get(0)).isTrue();

        MvcResult recentPage = support.get(officer, "/api/v1/claims/queue?size=50&page=" + pageOf(queue, recent));
        List<Number> recentAges = read(recentPage, "$.content[?(@.claimNumber=='" + recent + "')].ageHours");
        List<Boolean> recentBreaches = read(recentPage, "$.content[?(@.claimNumber=='" + recent + "')].slaBreached");
        assertThat(recentAges.get(0).longValue()).isZero();
        assertThat(recentBreaches.get(0)).isFalse();
    }

    @Test
    void aQueueItemShowsTheClaimButNeitherAnOfficerNorTheDecision() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.submitMotor(support.newHolder("IN"));
        List<String> queue = wholeQueue(officer);

        MvcResult page = support.get(officer, "/api/v1/claims/queue?size=50&page=" + pageOf(queue, claimNumber));

        String body = page.getResponse().getContentAsString();
        assertThat(body).contains(claimNumber).doesNotContain("assignedOfficerUserId").doesNotContain("decision");
        assertThat(((List<?>) read(page, "$.content[?(@.claimNumber=='" + claimNumber + "')].marketCode")).get(0))
                .isEqualTo("IN");
    }

    private static int pageOf(List<String> queue, String claimNumber) {
        return queue.indexOf(claimNumber) / 50;
    }
}
