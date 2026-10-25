package com.apac.claims.claim.domain;

import java.math.BigDecimal;
import java.time.Instant;

import com.apac.claims.identity.domain.AppUser;

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
 * An internal assessment of a claim. Several may exist per claim; the latest one counts. The deductible and the
 * recommended payout are computed by the system, never entered by the officer.
 */
@Entity
@Table(name = "claim_assessments")
public class ClaimAssessment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "claim_id", nullable = false)
    private Claim claim;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "assessed_by_id", nullable = false)
    private AppUser assessedBy;

    @Column(name = "assessed_loss_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal assessedLossAmount;

    @Column(name = "deductible_applied", nullable = false, precision = 19, scale = 2)
    private BigDecimal deductibleApplied;

    @Column(name = "recommended_payout", nullable = false, precision = 19, scale = 2)
    private BigDecimal recommendedPayout;

    @Column(name = "coverage_confirmed", nullable = false)
    private boolean coverageConfirmed;

    @Enumerated(EnumType.STRING)
    @Column(name = "recommendation", nullable = false, length = 20)
    private AssessmentRecommendation recommendation;

    @Column(name = "notes", length = 2000)
    private String notes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ClaimAssessment() {
    }

    public ClaimAssessment(Claim claim, AppUser assessedBy, BigDecimal assessedLossAmount,
                           BigDecimal deductibleApplied, BigDecimal recommendedPayout, boolean coverageConfirmed,
                           AssessmentRecommendation recommendation, String notes) {
        this.claim = claim;
        this.assessedBy = assessedBy;
        this.assessedLossAmount = assessedLossAmount;
        this.deductibleApplied = deductibleApplied;
        this.recommendedPayout = recommendedPayout;
        this.coverageConfirmed = coverageConfirmed;
        this.recommendation = recommendation;
        this.notes = notes;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public Claim getClaim() {
        return claim;
    }

    public void setClaim(Claim claim) {
        this.claim = claim;
    }

    public AppUser getAssessedBy() {
        return assessedBy;
    }

    public void setAssessedBy(AppUser assessedBy) {
        this.assessedBy = assessedBy;
    }

    public BigDecimal getAssessedLossAmount() {
        return assessedLossAmount;
    }

    public void setAssessedLossAmount(BigDecimal assessedLossAmount) {
        this.assessedLossAmount = assessedLossAmount;
    }

    public BigDecimal getDeductibleApplied() {
        return deductibleApplied;
    }

    public void setDeductibleApplied(BigDecimal deductibleApplied) {
        this.deductibleApplied = deductibleApplied;
    }

    public BigDecimal getRecommendedPayout() {
        return recommendedPayout;
    }

    public void setRecommendedPayout(BigDecimal recommendedPayout) {
        this.recommendedPayout = recommendedPayout;
    }

    public boolean isCoverageConfirmed() {
        return coverageConfirmed;
    }

    public void setCoverageConfirmed(boolean coverageConfirmed) {
        this.coverageConfirmed = coverageConfirmed;
    }

    public AssessmentRecommendation getRecommendation() {
        return recommendation;
    }

    public void setRecommendation(AssessmentRecommendation recommendation) {
        this.recommendation = recommendation;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
