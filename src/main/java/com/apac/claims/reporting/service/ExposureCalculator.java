package com.apac.claims.reporting.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.apac.claims.claim.domain.ClaimStatus;
import com.apac.claims.claim.domain.ClaimType;
import com.apac.claims.reporting.api.dto.ExposureRow;
import com.apac.claims.reporting.api.dto.ExposureTotal;
import com.apac.claims.reporting.api.dto.MarketOutstanding;

import org.springframework.stereotype.Component;

/**
 * Adds up the money at stake, per market and claim type. Open claims count with their claimed amount, approved claims
 * with their approved amount until they are paid, settled claims with their settled amount. Amounts are added in the
 * currency of their market, row by row; each figure is then converted to the base currency on its own, and the total is
 * the sum of the converted rows. Nothing in a local currency is ever added to anything of another market.
 */
@Component
public class ExposureCalculator {

    private final CurrencyConverter converter;

    public ExposureCalculator(CurrencyConverter converter) {
        this.converter = converter;
    }

    /** One row for every market and every claim type, also where there is nothing to add. */
    public List<ExposureRow> rows(List<ReportMarket> markets, List<ClaimFigures> claims) {
        List<ExposureRow> rows = new ArrayList<>();
        for (ReportMarket market : markets) {
            for (ClaimType type : ClaimType.values()) {
                List<ClaimFigures> mine = claims.stream()
                        .filter(claim -> claim.marketCode().equals(market.code()) && claim.claimType() == type)
                        .toList();
                BigDecimal open = sum(mine.stream().filter(ClaimFigures::open).map(ClaimFigures::claimedAmount));
                BigDecimal approved = sum(mine.stream().filter(claim -> claim.status() == ClaimStatus.APPROVED)
                        .map(ClaimFigures::approvedAmount));
                BigDecimal settled = sum(mine.stream().filter(claim -> claim.status() == ClaimStatus.SETTLED)
                        .map(ClaimFigures::settledAmount));
                BigDecimal outstanding = open.add(approved);
                String currency = market.currencyCode();
                rows.add(new ExposureRow(market.code(), type, currency, converter.local(open, currency),
                        converter.local(approved, currency), converter.local(settled, currency),
                        converter.local(outstanding, currency), converter.toBase(open, currency),
                        converter.toBase(approved, currency), converter.toBase(settled, currency),
                        converter.toBase(outstanding, currency)));
            }
        }
        return rows;
    }

    /** The sum of the converted rows, in the base currency only. */
    public ExposureTotal total(List<ExposureRow> rows) {
        return new ExposureTotal(
                sumBase(rows.stream().map(ExposureRow::openClaimedAmountBase)),
                sumBase(rows.stream().map(ExposureRow::approvedAwaitingPaymentBase)),
                sumBase(rows.stream().map(ExposureRow::settledTotalBase)),
                sumBase(rows.stream().map(ExposureRow::outstandingExposureBase)));
    }

    /** The outstanding exposure of each market: the local figures of its rows added up, and their base figures added up. */
    public List<MarketOutstanding> outstandingByMarket(List<ReportMarket> markets, List<ExposureRow> rows) {
        return markets.stream().map(market -> {
            List<ExposureRow> mine = rows.stream().filter(row -> row.marketCode().equals(market.code())).toList();
            return new MarketOutstanding(market.code(), market.currencyCode(),
                    converter.local(sum(mine.stream().map(ExposureRow::outstandingExposure)), market.currencyCode()),
                    sumBase(mine.stream().map(ExposureRow::outstandingExposureBase)));
        }).toList();
    }

    private BigDecimal sumBase(Stream<BigDecimal> amounts) {
        return converter.zeroBase().add(sum(amounts));
    }

    /** Adds the amounts that exist; an amount that is missing (a claim without an approved amount) counts as nothing. */
    static BigDecimal sum(Stream<BigDecimal> amounts) {
        return amounts.filter(amount -> amount != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
