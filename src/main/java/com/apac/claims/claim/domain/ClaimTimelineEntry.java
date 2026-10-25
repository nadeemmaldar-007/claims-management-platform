package com.apac.claims.claim.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/**
 * One line of a claim's audit trail. It is append-only: nothing in the application updates or deletes it.
 * The actor is kept as a user id value, not a foreign key, so the trail stays readable if an account changes.
 */
@Entity
@Table(name = "claim_timeline_entries")
public class ClaimTimelineEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "claim_id", nullable = false)
    private Claim claim;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 40)
    private TimelineEntryType entryType;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 30)
    private ClaimStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", length = 30)
    private ClaimStatus toStatus;

    @Column(name = "actor_user_id", nullable = false, length = 30)
    private String actorUserId;

    @Column(name = "message", nullable = false, length = 1000)
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 20)
    private TimelineVisibility visibility;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected ClaimTimelineEntry() {
    }

    public ClaimTimelineEntry(Claim claim, TimelineEntryType entryType, ClaimStatus fromStatus,
                              ClaimStatus toStatus, String actorUserId, String message,
                              TimelineVisibility visibility) {
        this.claim = claim;
        this.entryType = entryType;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actorUserId = actorUserId;
        this.message = message;
        this.visibility = visibility;
    }

    @PrePersist
    void onCreate() {
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public Claim getClaim() {
        return claim;
    }

    public TimelineEntryType getEntryType() {
        return entryType;
    }

    public ClaimStatus getFromStatus() {
        return fromStatus;
    }

    public ClaimStatus getToStatus() {
        return toStatus;
    }

    public String getActorUserId() {
        return actorUserId;
    }

    public String getMessage() {
        return message;
    }

    public TimelineVisibility getVisibility() {
        return visibility;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }
}
