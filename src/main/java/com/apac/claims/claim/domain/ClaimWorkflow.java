package com.apac.claims.claim.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import com.apac.claims.shared.exception.InvalidTransitionException;

/**
 * The claim state machine as one table: which action is allowed in which status, and where it leads. It is pure
 * (no Spring, no I/O), so every combination can be tested exhaustively. Who may perform an action is a separate
 * question that the services answer; this class only knows the statuses.
 *
 * <p>Submission has no "from" status, so it is {@link #initialStatus()} and not a transition.
 * {@link ClaimAction#ASSIGN} from SUBMITTED starts the review like a pick-up; from UNDER_REVIEW,
 * PENDING_INFORMATION or ESCALATED it keeps the status, because that is a reassignment.
 */
public final class ClaimWorkflow {

    private static final Map<ClaimStatus, Map<ClaimAction, ClaimStatus>> TRANSITIONS = buildTable();

    private ClaimWorkflow() {
    }

    private static Map<ClaimStatus, Map<ClaimAction, ClaimStatus>> buildTable() {
        Map<ClaimStatus, Map<ClaimAction, ClaimStatus>> table = new EnumMap<>(ClaimStatus.class);
        for (ClaimStatus status : ClaimStatus.values()) {
            table.put(status, new EnumMap<>(ClaimAction.class));
        }
        table.get(ClaimStatus.SUBMITTED).put(ClaimAction.PICK_UP, ClaimStatus.UNDER_REVIEW);
        table.get(ClaimStatus.SUBMITTED).put(ClaimAction.ASSIGN, ClaimStatus.UNDER_REVIEW);

        table.get(ClaimStatus.UNDER_REVIEW).put(ClaimAction.REQUEST_INFORMATION, ClaimStatus.PENDING_INFORMATION);
        table.get(ClaimStatus.UNDER_REVIEW).put(ClaimAction.ESCALATE, ClaimStatus.ESCALATED);
        table.get(ClaimStatus.UNDER_REVIEW).put(ClaimAction.APPROVE, ClaimStatus.APPROVED);
        table.get(ClaimStatus.UNDER_REVIEW).put(ClaimAction.REJECT, ClaimStatus.REJECTED);
        table.get(ClaimStatus.UNDER_REVIEW).put(ClaimAction.ASSIGN, ClaimStatus.UNDER_REVIEW);

        table.get(ClaimStatus.PENDING_INFORMATION).put(ClaimAction.RECEIVE_INFORMATION, ClaimStatus.UNDER_REVIEW);
        table.get(ClaimStatus.PENDING_INFORMATION).put(ClaimAction.REJECT, ClaimStatus.REJECTED);
        table.get(ClaimStatus.PENDING_INFORMATION).put(ClaimAction.ASSIGN, ClaimStatus.PENDING_INFORMATION);

        table.get(ClaimStatus.ESCALATED).put(ClaimAction.APPROVE, ClaimStatus.APPROVED);
        table.get(ClaimStatus.ESCALATED).put(ClaimAction.REJECT, ClaimStatus.REJECTED);
        table.get(ClaimStatus.ESCALATED).put(ClaimAction.ASSIGN, ClaimStatus.ESCALATED);

        table.get(ClaimStatus.APPROVED).put(ClaimAction.SETTLE, ClaimStatus.SETTLED);
        return table;
    }

    /** The status of a newly submitted claim. */
    public static ClaimStatus initialStatus() {
        return ClaimStatus.SUBMITTED;
    }

    /**
     * @return the status after the action
     * @throws InvalidTransitionException when the action is not allowed in the given status; the message names the
     *         status and the action in plain words
     */
    public static ClaimStatus nextStatus(ClaimStatus from, ClaimAction action) {
        ClaimStatus next = TRANSITIONS.get(from).get(action);
        if (next == null) {
            throw new InvalidTransitionException("A claim that is " + words(from) + " cannot be "
                    + action.passivePhrase() + ".");
        }
        return next;
    }

    public static boolean isOpen(ClaimStatus status) {
        return status.isOpen();
    }

    public static boolean isFinal(ClaimStatus status) {
        return status.isFinal();
    }

    /** The actions that are allowed in the status; empty for a final status. */
    public static Set<ClaimAction> allowedActions(ClaimStatus status) {
        Set<ClaimAction> allowed = TRANSITIONS.get(status).keySet();
        return allowed.isEmpty() ? EnumSet.noneOf(ClaimAction.class) : EnumSet.copyOf(allowed);
    }

    private static String words(ClaimStatus status) {
        return status.name().toLowerCase().replace('_', ' ');
    }
}
