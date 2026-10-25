package com.apac.claims.claim.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import com.apac.claims.shared.exception.ErrorCode;
import com.apac.claims.shared.exception.InvalidTransitionException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import static com.apac.claims.claim.domain.ClaimAction.APPROVE;
import static com.apac.claims.claim.domain.ClaimAction.ASSIGN;
import static com.apac.claims.claim.domain.ClaimAction.ESCALATE;
import static com.apac.claims.claim.domain.ClaimAction.PICK_UP;
import static com.apac.claims.claim.domain.ClaimAction.RECEIVE_INFORMATION;
import static com.apac.claims.claim.domain.ClaimAction.REJECT;
import static com.apac.claims.claim.domain.ClaimAction.REQUEST_INFORMATION;
import static com.apac.claims.claim.domain.ClaimAction.SETTLE;
import static com.apac.claims.claim.domain.ClaimStatus.APPROVED;
import static com.apac.claims.claim.domain.ClaimStatus.ESCALATED;
import static com.apac.claims.claim.domain.ClaimStatus.PENDING_INFORMATION;
import static com.apac.claims.claim.domain.ClaimStatus.REJECTED;
import static com.apac.claims.claim.domain.ClaimStatus.SETTLED;
import static com.apac.claims.claim.domain.ClaimStatus.SUBMITTED;
import static com.apac.claims.claim.domain.ClaimStatus.UNDER_REVIEW;

/**
 * Every status against every action. The table below is written out independently of the implementation, straight
 * from the transition table in the specification (with the amendment that a manager can assign from SUBMITTED), so
 * the test checks the rules and does not merely repeat them.
 */
class ClaimWorkflowTest {

    /** One allowed transition of the specification. */
    private record Transition(ClaimStatus from, ClaimAction action, ClaimStatus to) {
    }

    private static final List<Transition> ALLOWED = List.of(
            new Transition(SUBMITTED, PICK_UP, UNDER_REVIEW),
            new Transition(SUBMITTED, ASSIGN, UNDER_REVIEW),
            new Transition(UNDER_REVIEW, REQUEST_INFORMATION, PENDING_INFORMATION),
            new Transition(UNDER_REVIEW, ESCALATE, ESCALATED),
            new Transition(UNDER_REVIEW, ClaimAction.APPROVE, APPROVED),
            new Transition(UNDER_REVIEW, REJECT, REJECTED),
            new Transition(UNDER_REVIEW, ASSIGN, UNDER_REVIEW),
            new Transition(PENDING_INFORMATION, RECEIVE_INFORMATION, UNDER_REVIEW),
            new Transition(PENDING_INFORMATION, REJECT, REJECTED),
            new Transition(PENDING_INFORMATION, ASSIGN, PENDING_INFORMATION),
            new Transition(ESCALATED, ClaimAction.APPROVE, APPROVED),
            new Transition(ESCALATED, REJECT, REJECTED),
            new Transition(ESCALATED, ASSIGN, ESCALATED),
            new Transition(APPROVED, SETTLE, SETTLED));

    static Stream<Arguments> everyStatusAndActionPair() {
        return Stream.of(ClaimStatus.values()).flatMap(status -> Stream.of(ClaimAction.values()).map(action -> {
            ClaimStatus expected = ALLOWED.stream()
                    .filter(t -> t.from() == status && t.action() == action)
                    .map(Transition::to).findFirst().orElse(null);
            return Arguments.of(status, action, expected);
        }));
    }

    @ParameterizedTest(name = "{0} + {1} -> {2}")
    @MethodSource("everyStatusAndActionPair")
    void exactlyTheAllowedPairsSucceedWithTheExpectedStatusAndEveryOtherPairIsRefused(
            ClaimStatus from, ClaimAction action, ClaimStatus expected) {
        if (expected != null) {
            assertThat(ClaimWorkflow.nextStatus(from, action)).isEqualTo(expected);
        } else {
            assertThatThrownBy(() -> ClaimWorkflow.nextStatus(from, action))
                    .isInstanceOf(InvalidTransitionException.class);
        }
    }

    @Test
    void theTableHasExactlyFourteenAllowedTransitions() {
        long allowed = Stream.of(ClaimStatus.values())
                .mapToLong(status -> ClaimWorkflow.allowedActions(status).size()).sum();

        assertThat(allowed).isEqualTo(14);
        assertThat(ALLOWED).hasSize(14);
    }

    @Test
    void aSubmittedClaimStartsInSubmitted() {
        assertThat(ClaimWorkflow.initialStatus()).isEqualTo(SUBMITTED);
    }

    @ParameterizedTest
    @EnumSource(ClaimStatus.class)
    void submitIsNeverATransitionBecauseNothingExistsBeforeIt(ClaimStatus from) {
        assertThatThrownBy(() -> ClaimWorkflow.nextStatus(from, ClaimAction.SUBMIT))
                .isInstanceOf(InvalidTransitionException.class);
    }

    // ---- the manager's assign, including the amendment ---------------------------------------------------

    @Test
    void assigningFromSubmittedStartsTheReviewLikeAPickUp() {
        assertThat(ClaimWorkflow.nextStatus(SUBMITTED, ASSIGN)).isEqualTo(UNDER_REVIEW);
    }

    @ParameterizedTest
    @EnumSource(value = ClaimStatus.class, names = {"UNDER_REVIEW", "PENDING_INFORMATION", "ESCALATED"})
    void assigningInAWorkingStatusIsAReassignmentThatKeepsTheStatus(ClaimStatus status) {
        assertThat(ClaimWorkflow.nextStatus(status, ASSIGN)).isEqualTo(status);
    }

    @ParameterizedTest
    @EnumSource(value = ClaimStatus.class, names = {"APPROVED", "REJECTED", "SETTLED"})
    void aDecidedClaimCannotBeAssigned(ClaimStatus status) {
        assertThatThrownBy(() -> ClaimWorkflow.nextStatus(status, ASSIGN))
                .isInstanceOf(InvalidTransitionException.class);
    }

    // ---- final statuses ------------------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = ClaimStatus.class, names = {"REJECTED", "SETTLED"})
    void nothingCanHappenToAFinalClaim(ClaimStatus status) {
        assertThat(ClaimWorkflow.allowedActions(status)).isEmpty();
        assertThat(ClaimWorkflow.isFinal(status)).isTrue();
        for (ClaimAction action : ClaimAction.values()) {
            assertThatThrownBy(() -> ClaimWorkflow.nextStatus(status, action))
                    .isInstanceOf(InvalidTransitionException.class);
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = ClaimStatus.class, names = {"SUBMITTED", "UNDER_REVIEW", "PENDING_INFORMATION", "ESCALATED"})
    void theFourWorkingStatusesAreOpen(ClaimStatus status) {
        assertThat(ClaimWorkflow.isOpen(status)).isTrue();
        assertThat(ClaimWorkflow.isFinal(status)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = ClaimStatus.class, names = {"APPROVED", "REJECTED", "SETTLED"})
    void decidedStatusesAreNotOpen(ClaimStatus status) {
        assertThat(ClaimWorkflow.isOpen(status)).isFalse();
    }

    @Test
    void approvedIsNotFinalBecauseItStillWaitsForPayment() {
        assertThat(ClaimWorkflow.isFinal(APPROVED)).isFalse();
        assertThat(ClaimWorkflow.allowedActions(APPROVED)).containsExactly(SETTLE);
    }

    @Test
    void allowedActionsPerStatusMatchTheTable() {
        assertThat(ClaimWorkflow.allowedActions(SUBMITTED)).containsExactlyInAnyOrder(PICK_UP, ASSIGN);
        assertThat(ClaimWorkflow.allowedActions(UNDER_REVIEW))
                .containsExactlyInAnyOrder(REQUEST_INFORMATION, ESCALATE, ClaimAction.APPROVE, REJECT, ASSIGN);
        assertThat(ClaimWorkflow.allowedActions(PENDING_INFORMATION))
                .containsExactlyInAnyOrder(RECEIVE_INFORMATION, REJECT, ASSIGN);
        assertThat(ClaimWorkflow.allowedActions(ESCALATED))
                .containsExactlyInAnyOrder(ClaimAction.APPROVE, REJECT, ASSIGN);
    }

    @Test
    void changingTheReturnedSetDoesNotChangeTheWorkflow() {
        Set<ClaimAction> returned = ClaimWorkflow.allowedActions(SUBMITTED);

        returned.add(SETTLE);

        assertThat(ClaimWorkflow.allowedActions(SUBMITTED)).containsExactlyInAnyOrder(PICK_UP, ASSIGN);
        assertThat(EnumSet.copyOf(returned)).contains(SETTLE);
    }

    // ---- the message -----------------------------------------------------------------------------------------

    @Test
    void aRefusedTransitionIsA409WithTheInvalidTransitionCodeAndPlainWords() {
        assertThatThrownBy(() -> ClaimWorkflow.nextStatus(APPROVED, PICK_UP))
                .isInstanceOfSatisfying(InvalidTransitionException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CLAIM_INVALID_TRANSITION);
                    assertThat(e.getStatus().value()).isEqualTo(409);
                    assertThat(e).hasMessage("A claim that is approved cannot be picked up.");
                });
    }

    @Test
    void theMessageTurnsUnderscoresIntoSpaces() {
        assertThatThrownBy(() -> ClaimWorkflow.nextStatus(PENDING_INFORMATION, ClaimAction.APPROVE))
                .hasMessage("A claim that is pending information cannot be approved.");
        assertThatThrownBy(() -> ClaimWorkflow.nextStatus(UNDER_REVIEW, ClaimAction.SUBMIT))
                .hasMessage("A claim that is under review cannot be submitted.");
    }
}
