package com.apac.claims.claim.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.identity.domain.Role;
import com.apac.claims.identity.domain.UserRepository;
import com.apac.claims.market.domain.Market;
import com.apac.claims.market.domain.MarketRepository;
import com.apac.claims.support.RepositoryTest;
import com.apac.claims.support.TestData;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

/** Repositories of the tables that belong to a claim: assessments, information requests, timeline, idempotency. */
@RepositoryTest
class ClaimChildRepositoriesTest {

    @Autowired
    private ClaimRepository claims;
    @Autowired
    private ClaimAssessmentRepository assessments;
    @Autowired
    private InformationRequestRepository informationRequests;
    @Autowired
    private ClaimTimelineRepository timeline;
    @Autowired
    private IdempotencyKeyRepository idempotencyKeys;
    @Autowired
    private UserRepository users;
    @Autowired
    private MarketRepository markets;
    @Autowired
    private TestEntityManager entityManager;

    private Market india;
    private AppUser claimant;
    private AppUser officer;
    private Claim claim;
    private Claim otherClaim;

    @BeforeEach
    void createClaims() {
        india = markets.findByCode("IN").orElseThrow();
        claimant = users.saveAndFlush(TestData.user(Role.CLAIMANT, india));
        officer = users.saveAndFlush(TestData.user(Role.OFFICER, india));
        claim = claims.saveAndFlush(TestData.claim(claimant, india, ClaimType.MOTOR));
        otherClaim = claims.saveAndFlush(TestData.claim(claimant, india, ClaimType.MOTOR));
    }

    // ---- assessments -------------------------------------------------------------------------------------

    @Test
    void latestAssessmentIsTheOneRecordedLast() {
        ClaimAssessment first = assessments.save(TestData.assessment(claim, officer));
        ClaimAssessment second = assessments.saveAndFlush(TestData.assessment(claim, officer));

        assertThat(assessments.findFirstByClaimOrderByIdDesc(claim)).get().isEqualTo(second);
        assertThat(assessments.findByClaimOrderByIdDesc(claim)).containsExactly(second, first);
    }

    @Test
    void assessmentsOfAnotherClaimAreNotMixedIn() {
        ClaimAssessment mine = assessments.save(TestData.assessment(claim, officer));
        assessments.saveAndFlush(TestData.assessment(otherClaim, officer));

        assertThat(assessments.findByClaimOrderByIdDesc(claim)).containsExactly(mine);
    }

    @Test
    void aClaimWithoutAssessmentsHasNoLatestAssessment() {
        assertThat(assessments.findFirstByClaimOrderByIdDesc(claim)).isEmpty();
    }

    // ---- information requests ----------------------------------------------------------------------------

    @Test
    void openRequestsAreCountedPerClaimAndStatus() {
        informationRequests.save(TestData.informationRequest(claim, officer));
        informationRequests.save(TestData.informationRequest(claim, officer));
        InformationRequest answered = TestData.informationRequest(claim, officer);
        answered.setStatus(InformationRequestStatus.RESPONDED);
        informationRequests.save(answered);
        informationRequests.saveAndFlush(TestData.informationRequest(otherClaim, officer));

        assertThat(informationRequests.countByClaimAndStatus(claim, InformationRequestStatus.OPEN)).isEqualTo(2);
        assertThat(informationRequests.countByClaimAndStatus(claim, InformationRequestStatus.RESPONDED)).isEqualTo(1);
        assertThat(informationRequests.countByClaimAndStatus(claim, InformationRequestStatus.CANCELLED)).isZero();
    }

    @Test
    void aRequestIsOnlyFoundThroughItsOwnClaim() {
        InformationRequest request = informationRequests.saveAndFlush(TestData.informationRequest(claim, officer));

        assertThat(informationRequests.findByIdAndClaim(request.getId(), claim)).get().isEqualTo(request);
        assertThat(informationRequests.findByIdAndClaim(request.getId(), otherClaim)).isEmpty();
    }

    @Test
    void requestsOfAClaimAreListedInTheOrderTheyWereCreated() {
        InformationRequest first = informationRequests.save(TestData.informationRequest(claim, officer));
        InformationRequest second = informationRequests.saveAndFlush(TestData.informationRequest(claim, officer));

        assertThat(informationRequests.findByClaimOrderByIdAsc(claim)).containsExactly(first, second);
    }

    @Test
    void aResponseIsStoredWithItsAuthorAndTime() {
        InformationRequest request = TestData.informationRequest(claim, officer);
        request.setStatus(InformationRequestStatus.RESPONDED);
        request.setResponseText("Sunrise Auto Works, Bengaluru.");
        request.setRespondedBy(claimant);
        request.setRespondedAt(Instant.parse("2026-10-05T12:00:00Z"));
        Long id = informationRequests.saveAndFlush(request).getId();
        entityManager.clear();

        InformationRequest found = informationRequests.findById(id).orElseThrow();

        assertThat(found.getResponseText()).isEqualTo("Sunrise Auto Works, Bengaluru.");
        assertThat(found.getRespondedBy().getUserId()).isEqualTo(claimant.getUserId());
        assertThat(found.getRespondedAt()).isEqualTo(Instant.parse("2026-10-05T12:00:00Z"));
    }

    // ---- timeline ----------------------------------------------------------------------------------------

    @Test
    void timelineIsReturnedOldestFirstEvenWhenInsertedOutOfOrder() {
        ClaimTimelineEntry later = TestData.timelineEntry(claim, TimelineEntryType.ASSIGNED, TimelineVisibility.PUBLIC);
        later.setOccurredAt(Instant.parse("2026-10-02T00:00:00Z"));
        ClaimTimelineEntry earlier = TestData.timelineEntry(claim, TimelineEntryType.SUBMITTED,
                TimelineVisibility.PUBLIC);
        earlier.setOccurredAt(Instant.parse("2026-10-01T00:00:00Z"));
        timeline.save(later);
        timeline.saveAndFlush(earlier);

        List<ClaimTimelineEntry> entries = timeline.findByClaimOrderByOccurredAtAscIdAsc(claim);

        assertThat(entries).containsExactly(earlier, later);
    }

    @Test
    void claimantsViewExcludesInternalEntries() {
        ClaimTimelineEntry publicEntry = timeline.save(
                TestData.timelineEntry(claim, TimelineEntryType.SUBMITTED, TimelineVisibility.PUBLIC));
        timeline.saveAndFlush(
                TestData.timelineEntry(claim, TimelineEntryType.ASSESSMENT_RECORDED, TimelineVisibility.INTERNAL));

        assertThat(timeline.findByClaimAndVisibilityOrderByOccurredAtAscIdAsc(claim, TimelineVisibility.PUBLIC))
                .containsExactly(publicEntry);
        assertThat(timeline.findByClaimOrderByOccurredAtAscIdAsc(claim)).hasSize(2);
    }

    @Test
    void theTimelineOfOneClaimDoesNotIncludeAnotherClaimsEntries() {
        ClaimTimelineEntry mine = timeline.save(
                TestData.timelineEntry(claim, TimelineEntryType.SUBMITTED, TimelineVisibility.PUBLIC));
        timeline.saveAndFlush(TestData.timelineEntry(otherClaim, TimelineEntryType.SUBMITTED,
                TimelineVisibility.PUBLIC));

        assertThat(timeline.findByClaimOrderByOccurredAtAscIdAsc(claim)).containsExactly(mine);
    }

    // ---- idempotency keys --------------------------------------------------------------------------------

    @Test
    void anIdempotencyKeyIsFoundForTheUserWhoUsedIt() {
        IdempotencyKey saved = idempotencyKeys.saveAndFlush(
                new IdempotencyKey(claimant, "key-12345678", claim.getClaimNumber()));

        assertThat(idempotencyKeys.findByUserAndIdempotencyKey(claimant, "key-12345678")).get().isEqualTo(saved);
        assertThat(saved.getClaimNumber()).isEqualTo(claim.getClaimNumber());
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void anIdempotencyKeyIsNotVisibleToAnotherUser() {
        idempotencyKeys.saveAndFlush(new IdempotencyKey(claimant, "key-12345678", claim.getClaimNumber()));
        AppUser stranger = users.saveAndFlush(TestData.user(Role.CLAIMANT, india));

        assertThat(idempotencyKeys.findByUserAndIdempotencyKey(stranger, "key-12345678")).isEmpty();
    }

    @Test
    void anUnknownIdempotencyKeyIsNotFound() {
        assertThat(idempotencyKeys.findByUserAndIdempotencyKey(claimant, "never-used-key")).isEmpty();
    }
}
