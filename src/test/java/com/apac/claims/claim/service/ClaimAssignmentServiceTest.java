package com.apac.claims.claim.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import com.apac.claims.claim.domain.Claim;
import com.apac.claims.claim.domain.ClaimRepository;
import com.apac.claims.claim.domain.ClaimStatus;
import com.apac.claims.claim.domain.ClaimType;
import com.apac.claims.claim.domain.TimelineEntryType;
import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.identity.domain.Role;
import com.apac.claims.identity.domain.UserRepository;
import com.apac.claims.market.domain.Market;
import com.apac.claims.shared.event.DomainEventPublisher;
import com.apac.claims.shared.exception.ApiException;
import com.apac.claims.shared.exception.BusinessRuleException;
import com.apac.claims.shared.exception.ClaimAlreadyAssignedException;
import com.apac.claims.shared.exception.ErrorCode;
import com.apac.claims.shared.exception.ForbiddenException;
import com.apac.claims.shared.exception.InvalidTransitionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The assignment service without a database: the paths that depend on timing are made deterministic here. A lost
 * version race is simulated by a repository that refuses the flush, which is exactly what the claim's version column
 * does when two officers reach for the same claim.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ClaimAssignmentServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");

    @Mock
    private ClaimRepository claims;
    @Mock
    private UserRepository users;
    @Mock
    private ClaimActionSupport support;
    @Mock
    private TimelineService timeline;
    @Mock
    private DomainEventPublisher eventPublisher;
    @Mock
    private ClaimEvents events;
    @Mock
    private ClaimMapper mapper;

    private Market market;
    private AppUser claimant;
    private AppUser officer;
    private AppUser manager;
    private ClaimAssignmentService service;

    @BeforeEach
    void setUp() {
        service = new ClaimAssignmentService(claims, users, support, timeline, eventPublisher, events, mapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
        market = new Market("IN", "India", "INR", new BigDecimal("500000.00"), true);
        ReflectionTestUtils.setField(market, "id", 1L);
        claimant = user("claimant.in", Role.CLAIMANT, 1L);
        officer = user("officer.in", Role.OFFICER, 2L);
        manager = new AppUser("manager.apac", "hash", "Manager", "manager@test.example", Role.MANAGER, null);
        ReflectionTestUtils.setField(manager, "id", 3L);
        when(support.reload(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private AppUser user(String userId, Role role, long id) {
        AppUser user = new AppUser(userId, "hash", userId, userId + "@test.example", role, market);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private Claim claim(ClaimStatus status) {
        Claim claim = new Claim("CLM-10001", ClaimType.MOTOR, status, "MOT-IN-0001", new BigDecimal("1500000.00"),
                new BigDecimal("5000.00"), claimant, market, "INR", new BigDecimal("45000.00"),
                LocalDate.of(2026, 10, 1), "MG Road", "Rear-ended at a traffic signal");
        when(support.load(eq("CLM-10001"), any())).thenReturn(claim);
        return claim;
    }

    // ---- pick-up --------------------------------------------------------------------------------------------------

    @Test
    void aPickUpTakesTheClaimStartsTheReviewAndRecordsTheTimeAndTheEvent() {
        Claim claim = claim(ClaimStatus.SUBMITTED);

        service.pickUp("CLM-10001", officer);

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.UNDER_REVIEW);
        assertThat(claim.getAssignedOfficer()).isSameAs(officer);
        assertThat(claim.getAssignedAt()).isEqualTo(NOW);
        verify(claims).saveAndFlush(claim);
        verify(timeline).record(eq(claim), eq(TimelineEntryType.ASSIGNED), eq(ClaimStatus.SUBMITTED),
                eq(ClaimStatus.UNDER_REVIEW), eq("officer.in"), any());
        verify(eventPublisher).publish(any());
    }

    @Test
    void aLostVersionRaceIsReportedAsClaimAlreadyAssignedAndNothingIsRecorded() {
        Claim claim = claim(ClaimStatus.SUBMITTED);
        when(claims.saveAndFlush(claim)).thenThrow(new ObjectOptimisticLockingFailureException(Claim.class, 1L));

        assertThatThrownBy(() -> service.pickUp("CLM-10001", officer))
                .isInstanceOf(ClaimAlreadyAssignedException.class)
                .satisfies(error -> assertThat(((ApiException) error).getErrorCode())
                        .isEqualTo(ErrorCode.CLAIM_ALREADY_ASSIGNED));
        verifyNoInteractions(timeline, eventPublisher);
    }

    @Test
    void aClaimThatIsAlreadyWithAnOfficerIsRefusedWithoutTouchingTheDatabase() {
        for (ClaimStatus status : new ClaimStatus[] {ClaimStatus.UNDER_REVIEW, ClaimStatus.PENDING_INFORMATION,
                ClaimStatus.ESCALATED}) {
            claim(status);

            assertThatThrownBy(() -> service.pickUp("CLM-10001", officer))
                    .as(status.name()).isInstanceOf(ClaimAlreadyAssignedException.class);
        }
        verify(claims, never()).saveAndFlush(any());
        verifyNoInteractions(timeline, eventPublisher);
    }

    @Test
    void anApprovedOrRejectedClaimIsAnInvalidTransitionNotAnAssignmentConflict() {
        for (ClaimStatus status : new ClaimStatus[] {ClaimStatus.APPROVED, ClaimStatus.REJECTED,
                ClaimStatus.SETTLED}) {
            claim(status);

            assertThatThrownBy(() -> service.pickUp("CLM-10001", officer))
                    .as(status.name()).isInstanceOf(InvalidTransitionException.class)
                    .hasMessageContaining("cannot be picked up");
        }
        verify(claims, never()).saveAndFlush(any());
    }

    @Test
    void aClaimantIsStoppedByTheStaffCheckBeforeAnythingElseHappens() {
        claim(ClaimStatus.SUBMITTED);
        doThrow(new ForbiddenException("staff only"))
                .when(support).requireStaff(claimant);

        assertThatThrownBy(() -> service.pickUp("CLM-10001", claimant))
                .isInstanceOf(ForbiddenException.class);
        verify(claims, never()).saveAndFlush(any());
    }

    // ---- assign ---------------------------------------------------------------------------------------------------

    @Test
    void anAssignFromTheQueueThatLosesTheRaceIsReportedAsClaimAlreadyAssigned() {
        Claim claim = claim(ClaimStatus.SUBMITTED);
        when(users.findByUserId("officer.in")).thenReturn(Optional.of(officer));
        when(claims.saveAndFlush(claim)).thenThrow(new ObjectOptimisticLockingFailureException(Claim.class, 1L));

        assertThatThrownBy(() -> service.assign("CLM-10001", "officer.in", manager))
                .isInstanceOf(ClaimAlreadyAssignedException.class);
        verifyNoInteractions(timeline, eventPublisher);
    }

    @Test
    void aReassignmentThatLosesTheRaceIsReportedAsClaimAlreadyAssignedToo() {
        Claim claim = claim(ClaimStatus.UNDER_REVIEW);
        AppUser previous = user("officer.old", Role.OFFICER, 9L);
        claim.setAssignedOfficer(previous);
        when(users.findByUserId("officer.in")).thenReturn(Optional.of(officer));
        when(claims.saveAndFlush(claim)).thenThrow(new ObjectOptimisticLockingFailureException(Claim.class, 1L));

        assertThatThrownBy(() -> service.assign("CLM-10001", "officer.in", manager))
                .isInstanceOf(ClaimAlreadyAssignedException.class);
        verifyNoInteractions(timeline, eventPublisher);
    }

    @Test
    void aReassignmentKeepsTheStatusAndWritesAnInternalEntryWithoutAnEvent() {
        Claim claim = claim(ClaimStatus.PENDING_INFORMATION);
        claim.setAssignedOfficer(user("officer.old", Role.OFFICER, 9L));
        when(users.findByUserId("officer.in")).thenReturn(Optional.of(officer));

        service.assign("CLM-10001", " Officer.IN ", manager);

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.PENDING_INFORMATION);
        assertThat(claim.getAssignedOfficer()).isSameAs(officer);
        assertThat(claim.getAssignedAt()).isEqualTo(NOW);
        verify(timeline).record(eq(claim), eq(TimelineEntryType.REASSIGNED), isNull(),
                eq(ClaimStatus.PENDING_INFORMATION), eq("manager.apac"),
                eq("Reassigned from officer.old to officer.in by manager.apac."));
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void anInactiveOfficerAnOfficerOfAnotherMarketAndANonOfficerAreRefusedWithTheSameMessage() {
        claim(ClaimStatus.SUBMITTED);
        AppUser inactive = user("officer.off", Role.OFFICER, 20L);
        inactive.setActive(false);
        Market other = new Market("SG", "Singapore", "SGD", new BigDecimal("10000.00"), true);
        ReflectionTestUtils.setField(other, "id", 2L);
        AppUser foreign = new AppUser("officer.sg", "hash", "x", "x@test.example", Role.OFFICER, other);
        ReflectionTestUtils.setField(foreign, "id", 21L);
        when(users.findByUserId("officer.off")).thenReturn(Optional.of(inactive));
        when(users.findByUserId("officer.sg")).thenReturn(Optional.of(foreign));
        when(users.findByUserId("claimant.in")).thenReturn(Optional.of(claimant));
        when(users.findByUserId("nobody")).thenReturn(Optional.empty());

        for (String target : new String[] {"officer.off", "officer.sg", "claimant.in", "nobody"}) {
            assertThatThrownBy(() -> service.assign("CLM-10001", target, manager))
                    .as(target).isInstanceOf(BusinessRuleException.class)
                    .hasMessage("officerUserId must be an active officer of market IN.");
        }
        verify(claims, never()).saveAndFlush(any());
    }

    @Test
    void aNullOfficerUserIdIsTreatedLikeAnUnknownOne() {
        claim(ClaimStatus.SUBMITTED);

        assertThatThrownBy(() -> service.assign("CLM-10001", null, manager))
                .isInstanceOf(BusinessRuleException.class);
    }
}
