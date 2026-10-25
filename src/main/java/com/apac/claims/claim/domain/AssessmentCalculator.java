package com.apac.claims.claim.domain;

import java.math.BigDecimal;
import java.util.Objects;

import com.apac.claims.shared.money.MoneyRules;

/**
 * Computes the payout an assessment recommends. The officer never enters it: the system derives it from the claim's
 * own policy snapshots, so two officers assessing the same loss get the same payout. Pure, no I/O.
 */
public final class AssessmentCalculator {

    private AssessmentCalculator() {
    }

    /**
     * {@code coverageConfirmed ? max(0, min(loss, sumInsured) - deductible) : 0}, rounded half up to the number of
     * decimal places of the currency.
     */
    public static BigDecimal recommendedPayout(boolean coverageConfirmed, BigDecimal assessedLoss,
                                               BigDecimal sumInsured, BigDecimal deductible, String currencyCode) {
        Objects.requireNonNull(assessedLoss, "assessedLoss");
        Objects.requireNonNull(sumInsured, "sumInsured");
        Objects.requireNonNull(deductible, "deductible");
        if (!coverageConfirmed) {
            return MoneyRules.normalize(BigDecimal.ZERO, currencyCode);
        }
        BigDecimal covered = assessedLoss.min(sumInsured);
        BigDecimal payout = covered.subtract(deductible).max(BigDecimal.ZERO);
        return MoneyRules.normalize(payout, currencyCode);
    }
}
