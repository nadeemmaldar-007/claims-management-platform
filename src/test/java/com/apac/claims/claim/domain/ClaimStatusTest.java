package com.apac.claims.claim.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ClaimStatusTest {

    @ParameterizedTest
    @EnumSource(value = ClaimStatus.class, names = {"SUBMITTED", "UNDER_REVIEW", "PENDING_INFORMATION", "ESCALATED"})
    void theFourWorkingStatusesAreOpen(ClaimStatus status) {
        assertThat(status.isOpen()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = ClaimStatus.class, names = {"APPROVED", "REJECTED", "SETTLED"})
    void decidedStatusesAreNotOpen(ClaimStatus status) {
        assertThat(status.isOpen()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = ClaimStatus.class, names = {"REJECTED", "SETTLED"})
    void rejectedAndSettledAreFinal(ClaimStatus status) {
        assertThat(status.isFinal()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = ClaimStatus.class, names = {"SUBMITTED", "UNDER_REVIEW", "PENDING_INFORMATION", "ESCALATED",
            "APPROVED"})
    void everythingElseCanStillMove(ClaimStatus status) {
        assertThat(status.isFinal()).isFalse();
    }

    @Test
    void approvedIsNeitherOpenNorFinalBecauseItWaitsForPayment() {
        assertThat(ClaimStatus.APPROVED.isOpen()).isFalse();
        assertThat(ClaimStatus.APPROVED.isFinal()).isFalse();
    }
}
