package com.apac.claims.reporting.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.apac.claims.reporting.api.dto.AgingBucket;

import org.springframework.stereotype.Component;

/**
 * Sorts the open claims into four bands by their age in whole days since submission: 0-2, 3-7, 8-14 and 15 or more.
 * The age is {@code Duration.between(createdAt, now).toDays()}, so a claim that is 2 days and 23 hours old is in the
 * first band and one that is exactly 3 days old is in the second. The exposure of a band is the claimed amount of its
 * claims, converted market by market into the base currency.
 */
@Component
public class AgingCalculator {

    static final List<String> LABELS = List.of("0-2 days", "3-7 days", "8-14 days", "15+ days");

    private final CurrencyConverter converter;

    public AgingCalculator(CurrencyConverter converter) {
        this.converter = converter;
    }

    /** The band of a claim of the given age in whole days: 0 to 3. */
    static int bandOf(long days) {
        if (days <= 2) {
            return 0;
        }
        if (days <= 7) {
            return 1;
        }
        return days <= 14 ? 2 : 3;
    }

    /**
     * @param onlyMarket the market of the report when it covers exactly one, so that the local amount can be given;
     *                   null when the report covers several markets
     * @return all four bands in order, also the empty ones
     */
    public List<AgingBucket> buckets(List<ClaimFigures> claims, Instant now, ReportMarket onlyMarket) {
        List<ClaimFigures> open = claims.stream().filter(ClaimFigures::open).toList();
        List<AgingBucket> buckets = new ArrayList<>();
        for (int band = 0; band < LABELS.size(); band++) {
            int current = band;
            List<ClaimFigures> inBand = open.stream()
                    .filter(claim -> bandOf(Duration.between(claim.createdAt(), now).toDays()) == current)
                    .toList();
            BigDecimal local = onlyMarket == null ? null : converter.local(
                    ExposureCalculator.sum(inBand.stream().map(ClaimFigures::claimedAmount)),
                    onlyMarket.currencyCode());
            buckets.add(new AgingBucket(LABELS.get(band), inBand.size(), baseTotal(inBand), local,
                    onlyMarket == null ? null : onlyMarket.currencyCode()));
        }
        return buckets;
    }

    /** The claimed amounts added up per currency, each sum converted once, and the converted sums added up. */
    private BigDecimal baseTotal(List<ClaimFigures> claims) {
        Map<String, BigDecimal> perCurrency = claims.stream().collect(Collectors.groupingBy(
                ClaimFigures::currencyCode,
                Collectors.reducing(BigDecimal.ZERO, ClaimFigures::claimedAmount, BigDecimal::add)));
        BigDecimal total = converter.zeroBase();
        for (Map.Entry<String, BigDecimal> entry : perCurrency.entrySet()) {
            total = total.add(converter.toBase(entry.getValue(), entry.getKey()));
        }
        return total;
    }
}
