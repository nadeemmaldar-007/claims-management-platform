package com.apac.claims.claim.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import com.apac.claims.claim.domain.Claim;
import com.apac.claims.claim.domain.ClaimStatus;
import com.apac.claims.claim.domain.ClaimTimelineEntry;
import com.apac.claims.claim.domain.ClaimTimelineRepository;
import com.apac.claims.claim.domain.ClaimType;
import com.apac.claims.claim.domain.TimelineEntryType;
import com.apac.claims.claim.domain.TimelineVisibility;
import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.identity.domain.Role;
import com.apac.claims.market.domain.Market;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/** The one place that creates timeline entries: the visibility of every entry type, the clock and the length limit. */
class TimelineServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");

    private final ClaimTimelineRepository repository = mock(ClaimTimelineRepository.class);
    private final TimelineService service = new TimelineService(repository, Clock.fixed(NOW, ZoneOffset.UTC));

    private Claim claim() {
        Market market = new Market("IN", "India", "INR", new BigDecimal("500000.00"), true);
        AppUser claimant = new AppUser("claimant.in", "hash", "C", "c@test.example", Role.CLAIMANT, market);
        return new Claim("CLM-10001", ClaimType.MOTOR, ClaimStatus.SUBMITTED, "MOT-IN-0001",
                new BigDecimal("1500000.00"), new BigDecimal("5000.00"), claimant, market, "INR",
                new BigDecimal("45000.00"), LocalDate.of(2026, 10, 1), "MG Road", "Rear-ended at a traffic signal");
    }

    @ParameterizedTest
    @EnumSource(TimelineEntryType.class)
    void onlyAssessmentsAndReassignmentsAreInternal(TimelineEntryType type) {
        boolean internal = type == TimelineEntryType.ASSESSMENT_RECORDED || type == TimelineEntryType.REASSIGNED;

        assertThat(TimelineService.visibilityOf(type))
                .isEqualTo(internal ? TimelineVisibility.INTERNAL : TimelineVisibility.PUBLIC);
    }

    @Test
    void anEntryTakesItsTimeFromTheClockAndItsVisibilityFromItsType() {
        when(repository.save(any(ClaimTimelineEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ClaimTimelineEntry entry = service.record(claim(), TimelineEntryType.REASSIGNED, null,
                ClaimStatus.UNDER_REVIEW, "manager.apac", "Reassigned.");

        assertThat(entry.getOccurredAt()).isEqualTo(NOW);
        assertThat(entry.getVisibility()).isEqualTo(TimelineVisibility.INTERNAL);
        assertThat(entry.getActorUserId()).isEqualTo("manager.apac");
        assertThat(entry.getFromStatus()).isNull();
        assertThat(entry.getToStatus()).isEqualTo(ClaimStatus.UNDER_REVIEW);
    }

    @Test
    void aMessageLongerThanTheColumnIsCutInsteadOfFailingTheWholeAction() {
        when(repository.save(any(ClaimTimelineEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ClaimTimelineEntry entry = service.record(claim(), TimelineEntryType.REJECTED, ClaimStatus.UNDER_REVIEW,
                ClaimStatus.REJECTED, "officer.in", "m".repeat(1500));

        assertThat(entry.getMessage()).hasSize(1000);
    }

    @Test
    void aMessageOfExactlyTheLimitIsKeptWhole() {
        when(repository.save(any(ClaimTimelineEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ClaimTimelineEntry entry = service.record(claim(), TimelineEntryType.REJECTED, ClaimStatus.UNDER_REVIEW,
                ClaimStatus.REJECTED, "officer.in", "m".repeat(1000));

        assertThat(entry.getMessage()).hasSize(1000);
    }

    @Test
    void theEntryIsSavedOnce() {
        ArgumentCaptor<ClaimTimelineEntry> captor = ArgumentCaptor.forClass(ClaimTimelineEntry.class);
        when(repository.save(any(ClaimTimelineEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.record(claim(), TimelineEntryType.SUBMITTED, null, ClaimStatus.SUBMITTED, "claimant.in", "Submitted.");

        Mockito.verify(repository).save(captor.capture());
        assertThat(captor.getValue().getEntryType()).isEqualTo(TimelineEntryType.SUBMITTED);
    }
}
