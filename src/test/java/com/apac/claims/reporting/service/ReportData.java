package com.apac.claims.reporting.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import com.apac.claims.claim.domain.ClaimStatus;
import com.apac.claims.claim.domain.ClaimType;
import com.apac.claims.claim.domain.DecisionType;
import com.apac.claims.config.ReportingProperties;
import com.apac.claims.config.SlaProperties;

/**
 * Hand-made input for the calculator tests: a fixed "now", the configuration of the application, and a builder for the
 * figures of a claim so that each test only states what matters to it.
 */
final class ReportData {

    static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");

    private ReportData() {
    }

    static ReportingProperties rates() {
        return new ReportingProperties("USD", "STATIC_ILLUSTRATIVE", Map.of(
                "INR", new BigDecimal("0.012"), "SGD", new BigDecimal("0.74"), "AUD", new BigDecimal("0.65"),
                "JPY", new BigDecimal("0.0067"), "MYR", new BigDecimal("0.21"), "THB", new BigDecimal("0.029")));
    }

    static CurrencyConverter converter() {
        return new CurrencyConverter(rates());
    }

    static SlaProperties sla() {
        return new SlaProperties(48, 240, 7);
    }

    static Figures claim(String market, String currency, ClaimStatus status, String claimed) {
        return new Figures(market, currency, status, claimed);
    }

    /** A claim of the Indian market, in rupees, with the given status and claimed amount. */
    static Figures india(ClaimStatus status, String claimed) {
        return claim("IN", "INR", status, claimed);
    }

    /** Builds a {@link ClaimFigures}; the claim was submitted one hour before {@link #NOW} unless said otherwise. */
    static final class Figures {

        private final String market;
        private final String currency;
        private final ClaimStatus status;
        private final BigDecimal claimed;
        private ClaimType type = ClaimType.MOTOR;
        private Instant createdAt = NOW.minus(Duration.ofHours(1));
        private BigDecimal approved;
        private BigDecimal settled;
        private String officer;
        private Instant assignedAt;
        private Instant decidedAt;
        private String decidedBy;
        private DecisionType decision;
        private Instant settledAt;

        private Figures(String market, String currency, ClaimStatus status, String claimed) {
            this.market = market;
            this.currency = currency;
            this.status = status;
            this.claimed = new BigDecimal(claimed);
        }

        Figures type(ClaimType value) {
            this.type = value;
            return this;
        }

        Figures age(Duration age) {
            this.createdAt = NOW.minus(age);
            return this;
        }

        Figures approved(String amount) {
            this.approved = new BigDecimal(amount);
            return this;
        }

        Figures settled(String amount) {
            this.settled = new BigDecimal(amount);
            return this;
        }

        Figures officer(String userId) {
            this.officer = userId;
            return this;
        }

        /** Assigned {@code afterSubmission} after the claim was submitted. */
        Figures assignedAfter(Duration afterSubmission) {
            this.assignedAt = createdAt.plus(afterSubmission);
            return this;
        }

        /** Decided by {@code userId} {@code afterSubmission} after the claim was submitted. */
        Figures decidedBy(String userId, DecisionType type, Duration afterSubmission) {
            this.decidedBy = userId;
            this.decision = type;
            this.decidedAt = createdAt.plus(afterSubmission);
            return this;
        }

        Figures decidedAt(Instant time) {
            this.decidedAt = time;
            return this;
        }

        Figures settledAt(Instant time) {
            this.settledAt = time;
            return this;
        }

        Figures assignedAt(Instant time) {
            this.assignedAt = time;
            return this;
        }

        ClaimFigures build() {
            return new ClaimFigures(market, currency, type, status, createdAt, claimed, approved, settled, officer,
                    assignedAt, decidedAt, decidedBy, decision, settledAt);
        }
    }
}
