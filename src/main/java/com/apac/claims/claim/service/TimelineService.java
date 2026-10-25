package com.apac.claims.claim.service;

import java.time.Clock;
import java.time.Instant;

import com.apac.claims.claim.domain.Claim;
import com.apac.claims.claim.domain.ClaimStatus;
import com.apac.claims.claim.domain.ClaimTimelineEntry;
import com.apac.claims.claim.domain.ClaimTimelineRepository;
import com.apac.claims.claim.domain.TimelineEntryType;
import com.apac.claims.claim.domain.TimelineVisibility;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only place that creates timeline entries, so the visibility rule exists once: assessments and reassignments
 * are INTERNAL, everything else is PUBLIC and may be read by the claimant. Callers must therefore keep anything
 * confidential, such as assessment notes and recommendations, out of the message of a PUBLIC entry. An entry can only
 * be recorded inside the transaction of the state change it describes.
 */
@Service
public class TimelineService {

    private static final int MAX_MESSAGE_LENGTH = 1000;

    private final ClaimTimelineRepository timeline;
    private final Clock clock;

    public TimelineService(ClaimTimelineRepository timeline, Clock clock) {
        this.timeline = timeline;
        this.clock = clock;
    }

    /** @param fromStatus the status before the action, or null when the status did not change or did not exist */
    @Transactional(propagation = Propagation.MANDATORY)
    public ClaimTimelineEntry record(Claim claim, TimelineEntryType type, ClaimStatus fromStatus, ClaimStatus toStatus,
                                     String actorUserId, String message) {
        ClaimTimelineEntry entry = new ClaimTimelineEntry(claim, type, fromStatus, toStatus, actorUserId,
                truncate(message), visibilityOf(type));
        entry.setOccurredAt(Instant.now(clock));
        return timeline.save(entry);
    }

    /** Spec section 4: ASSESSMENT_RECORDED and REASSIGNED are INTERNAL, all other entries PUBLIC. */
    public static TimelineVisibility visibilityOf(TimelineEntryType type) {
        return switch (type) {
            case ASSESSMENT_RECORDED, REASSIGNED -> TimelineVisibility.INTERNAL;
            default -> TimelineVisibility.PUBLIC;
        };
    }

    private static String truncate(String message) {
        return message.length() <= MAX_MESSAGE_LENGTH ? message : message.substring(0, MAX_MESSAGE_LENGTH);
    }
}
