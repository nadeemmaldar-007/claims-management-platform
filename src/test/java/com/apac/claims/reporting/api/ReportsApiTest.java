package com.apac.claims.reporting.api;

import static com.apac.claims.support.ClaimTestSupport.code;
import static com.apac.claims.support.ClaimTestSupport.expect;
import static com.apac.claims.support.ClaimTestSupport.path;
import static com.apac.claims.support.ClaimTestSupport.read;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import com.apac.claims.claim.domain.ClaimStatus;
import com.apac.claims.identity.domain.Role;
import com.apac.claims.support.AbstractClaimApiTest;
import com.apac.claims.support.ClaimTestSupport.Account;
import com.apac.claims.support.ClaimTestSupport.Holder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The five reports through the API. The database is shared with every other test, so a test never asserts on a global
 * total: it creates its own officer and claims and asserts on the difference a step makes, or on the row of its own
 * officer. The ages that the aging and the late counts need are produced by moving the submission time of a claim of
 * the test back in the database.
 */
class ReportsApiTest extends AbstractClaimApiTest {

    private static final String REPORTS = "/api/v1/reports";

    private MvcResult report(Account who, String suffix) throws Exception {
        MvcResult result = support.get(who, REPORTS + suffix);
        expect(result, 200);
        return result;
    }

    private static BigDecimal number(Object value) {
        return new BigDecimal(value.toString());
    }

    /** One figure of the exposure row of a market and claim type, as the manager sees it. */
    private BigDecimal exposure(String market, String type, String field) throws Exception {
        List<Object> values = read(report(support.manager(), "/exposure"),
                "$.rows[?(@.marketCode=='" + market + "' && @.claimType=='" + type + "')]." + field);
        return number(values.get(0));
    }

    private Map<String, Object> officerRow(String report, String userId) throws Exception {
        List<Map<String, Object>> rows = read(report(support.manager(), "/" + report),
                "$.officers[?(@.userId=='" + userId + "')]");
        assertThat(rows).as("row of %s in %s", userId, report).hasSize(1);
        return rows.get(0);
    }

    private long marketQueue(String market, String field) throws Exception {
        List<Object> values = read(report(support.manager(), "/workload"),
                "$.markets[?(@.marketCode=='" + market + "')]." + field);
        return ((Number) values.get(0)).longValue();
    }

    private long statusCount(Account who, ClaimStatus status) throws Exception {
        return ((Number) read(report(who, "/overview"), "$.countsByStatus." + status.name())).longValue();
    }

    private long agingCount(Account who, String label) throws Exception {
        List<Object> values = read(report(who, "/aging"), "$.buckets[?(@.label=='" + label + "')].count");
        return ((Number) values.get(0)).longValue();
    }

    private void submittedHoursAgo(String claimNumber, long hours) {
        jdbc.update("update claims set created_at = ? where claim_number = ?",
                OffsetDateTime.now(ZoneOffset.UTC).minusHours(hours), claimNumber);
    }

    // ---- scope ---------------------------------------------------------------------------------------------------------

    @Test
    void anOfficerSeesTheirOwnMarketInEveryReportAndNothingElse() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");

        MvcResult overview = report(officer, "/overview");
        MvcResult workload = report(officer, "/workload");
        MvcResult aging = report(officer, "/aging");
        MvcResult performance = report(officer, "/performance");

        assertThat((List<String>) read(overview, "$.outstandingExposureByMarket[*].marketCode")).containsExactly("IN");
        assertThat((List<String>) read(workload, "$.markets[*].marketCode")).containsExactly("IN");
        assertThat((List<String>) read(workload, "$.officers[*].marketCode")).isNotEmpty().containsOnly("IN");
        assertThat((List<String>) read(workload, "$.officers[*].userId")).contains(officer.userId());
        assertThat((List<String>) read(performance, "$.officers[*].marketCode")).isNotEmpty().containsOnly("IN");
        assertThat((String) read(aging, "$.buckets[0].currencyCode")).isEqualTo("INR");
    }

    @Test
    void aManagerSeesEveryMarketInTheOrderTheyWereSeeded() throws Exception {
        Account manager = support.manager();

        MvcResult overview = report(manager, "/overview");
        MvcResult exposure = report(manager, "/exposure");
        MvcResult workload = report(manager, "/workload");

        assertThat((List<String>) read(overview, "$.outstandingExposureByMarket[*].marketCode"))
                .containsExactly("IN", "SG", "AU", "JP", "MY", "TH");
        assertThat((List<String>) read(overview, "$.outstandingExposureByMarket[*].currencyCode"))
                .containsExactly("INR", "SGD", "AUD", "JPY", "MYR", "THB");
        assertThat((List<String>) read(exposure, "$.rows[*].marketCode")).hasSize(12)
                .containsExactly("IN", "IN", "SG", "SG", "AU", "AU", "JP", "JP", "MY", "MY", "TH", "TH");
        assertThat((List<String>) read(workload, "$.markets[*].marketCode"))
                .containsExactly("IN", "SG", "AU", "JP", "MY", "TH");
        assertThat((List<String>) read(workload, "$.officers[*].marketCode")).contains("IN", "SG", "JP");
    }

    @Test
    void aManagerCanNarrowEveryReportToOneMarketWithTheCodeInAnyCase() throws Exception {
        Account manager = support.manager();

        MvcResult overview = report(manager, "/overview?marketCode=sg");
        MvcResult exposure = report(manager, "/exposure?marketCode=SG");
        MvcResult aging = report(manager, "/aging?marketCode=SG");
        MvcResult workload = report(manager, "/workload?marketCode=SG");
        MvcResult performance = report(manager, "/performance?marketCode=SG");

        assertThat((List<String>) read(overview, "$.outstandingExposureByMarket[*].marketCode")).containsExactly("SG");
        assertThat((List<String>) read(exposure, "$.rows[*].marketCode")).containsExactly("SG", "SG");
        assertThat((List<String>) read(workload, "$.markets[*].marketCode")).containsExactly("SG");
        assertThat((List<String>) read(workload, "$.officers[*].marketCode")).isNotEmpty().containsOnly("SG");
        assertThat((List<String>) read(performance, "$.officers[*].marketCode")).isNotEmpty().containsOnly("SG");
        // One market: the aging also gives the amounts in its own currency.
        assertThat((String) read(aging, "$.buckets[0].currencyCode")).isEqualTo("SGD");
    }

    @Test
    void whenAManagerCoversSeveralMarketsTheAgingGivesNoLocalAmount() throws Exception {
        MvcResult aging = report(support.manager(), "/aging");

        assertThat((Object) read(aging, "$.buckets[0].outstandingExposureLocal")).isNull();
        assertThat((Object) read(aging, "$.buckets[0].currencyCode")).isNull();
    }

    @Test
    void anOfficerWhoAsksForAnotherMarketGetsAnEmptyReportAndLearnsNothing() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");

        MvcResult overview = report(officer, "/overview?marketCode=SG");
        MvcResult workload = report(officer, "/workload?marketCode=SG");
        MvcResult performance = report(officer, "/performance?marketCode=SG");
        MvcResult aging = report(officer, "/aging?marketCode=SG");

        assertThat((List<Object>) read(overview, "$.outstandingExposureByMarket")).isEmpty();
        assertThat(overview.getResponse().getContentAsString()).contains("\"countsByStatus\"");
        Map<String, Object> counts = read(overview, "$.countsByStatus");
        assertThat(counts.values().stream().map(value -> ((Number) value).longValue())).containsOnly(0L);
        assertThat((List<Object>) read(workload, "$.officers")).isEmpty();
        assertThat((List<Object>) read(workload, "$.markets")).isEmpty();
        assertThat((List<Object>) read(performance, "$.officers")).isEmpty();
        assertThat((List<Number>) read(aging, "$.buckets[*].count")).hasSize(4).containsOnly(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ZZ", "XX", "INDIA"})
    void anUnknownMarketCodeIsABadRequestForEveryone(String marketCode) throws Exception {
        for (Account who : List.of(support.manager(), support.seeded(Role.OFFICER, "IN"))) {
            MvcResult result = support.get(who, REPORTS + "/overview?marketCode=" + marketCode);

            expect(result, 400);
            assertThat((String) read(result, "$.errors[0].field")).isEqualTo("marketCode");
        }
    }

    // ---- who may call ----------------------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"/overview", "/exposure", "/aging", "/workload", "/performance"})
    void aClaimantGetsA403OnEveryReport(String suffix) throws Exception {
        MvcResult result = support.get(support.seeded(Role.CLAIMANT, "IN"), REPORTS + suffix);

        expect(result, 403);
        assertThat(code(result)).isEqualTo("ACCESS_DENIED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/overview", "/exposure", "/aging", "/workload", "/performance"})
    void withoutATokenEveryReportIsUnauthenticated(String suffix) throws Exception {
        expect(support.get(null, REPORTS + suffix), 401);
    }

    @Test
    void anOfficerGetsA403OnTheExposureReportBecauseItIsForManagers() throws Exception {
        MvcResult result = support.get(support.seeded(Role.OFFICER, "IN"), REPORTS + "/exposure");

        expect(result, 403);
        assertThat(code(result)).isEqualTo("ACCESS_DENIED");
    }

    // ---- the contents --------------------------------------------------------------------------------------------------------

    @Test
    void theRatesAreLabelledAsIllustrativeAndTheBaseCurrencyIsNamed() throws Exception {
        Account manager = support.manager();

        for (String suffix : List.of("/overview", "/exposure", "/aging")) {
            MvcResult result = report(manager, suffix);
            assertThat((String) read(result, "$.rateSource")).as(suffix).isEqualTo("STATIC_ILLUSTRATIVE");
            assertThat((String) read(result, "$.baseCurrency")).as(suffix).isEqualTo("USD");
        }
    }

    @Test
    void theOverviewAlwaysListsAllSevenStatusesAndBothTypesAndTheFourAgeBands() throws Exception {
        MvcResult overview = report(support.manager(), "/overview");

        Map<String, Object> byStatus = read(overview, "$.countsByStatus");
        Map<String, Object> byType = read(overview, "$.countsByType");
        assertThat(byStatus.keySet()).containsExactly("SUBMITTED", "UNDER_REVIEW", "PENDING_INFORMATION", "ESCALATED",
                "APPROVED", "REJECTED", "SETTLED");
        assertThat(byType.keySet()).containsExactly("MOTOR", "PROPERTY");
        assertThat((List<String>) read(overview, "$.aging[*].label"))
                .containsExactly("0-2 days", "3-7 days", "8-14 days", "15+ days");
        assertThat(((Number) read(overview, "$.queueSize")).longValue()).isGreaterThanOrEqualTo(0);
        // Money is a JSON number with its decimal places, so the base total is written with two of them.
        assertThat(overview.getResponse().getContentAsString())
                .containsPattern("\"outstandingExposureBase\":-?\\d+\\.\\d{2},");
    }

    // ---- a claim moves through the reports -----------------------------------------------------------------------------------

    @Test
    void aClaimThatIsSubmittedPickedUpAssessedApprovedAndSettledShowsUpInEveryReport() throws Exception {
        Account manager = support.manager();
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        BigDecimal open0 = exposure("IN", "MOTOR", "openClaimedAmount");
        BigDecimal approved0 = exposure("IN", "MOTOR", "approvedAwaitingPayment");
        BigDecimal settled0 = exposure("IN", "MOTOR", "settledTotal");
        BigDecimal outstanding0 = exposure("IN", "MOTOR", "outstandingExposure");
        BigDecimal outstandingBase0 = exposure("IN", "MOTOR", "outstandingExposureBase");
        long queue0 = marketQueue("IN", "unassignedInQueue");
        long submitted0 = statusCount(manager, ClaimStatus.SUBMITTED);

        // Submitted: 45000.00 INR is open exposure and the claim is in the queue.
        String claimNumber = support.submitMotor(holder);
        assertThat(exposure("IN", "MOTOR", "openClaimedAmount").subtract(open0)).isEqualByComparingTo("45000.00");
        assertThat(exposure("IN", "MOTOR", "outstandingExposure").subtract(outstanding0))
                .isEqualByComparingTo("45000.00");
        assertThat(exposure("IN", "MOTOR", "outstandingExposureBase").subtract(outstandingBase0))
                .isEqualByComparingTo("540.00");
        assertThat(marketQueue("IN", "unassignedInQueue")).isEqualTo(queue0 + 1);
        assertThat(statusCount(manager, ClaimStatus.SUBMITTED)).isEqualTo(submitted0 + 1);
        assertThat(officerRow("workload", officer.userId()).get("openAssigned")).isEqualTo(0);

        // Picked up: out of the queue, onto the officer's desk.
        expect(support.post(officer, path(claimNumber, "/pick-up"), null), 200);
        assertThat(marketQueue("IN", "unassignedInQueue")).isEqualTo(queue0);
        Map<String, Object> picked = officerRow("workload", officer.userId());
        assertThat(picked.get("openAssigned")).isEqualTo(1);
        assertThat(picked.get("underReview")).isEqualTo(1);
        assertThat(((Number) picked.get("oldestOpenAgeHours")).longValue()).isZero();
        assertThat(picked.get("slaBreaches")).isEqualTo(0);

        // Approved for 43000.00: no longer open, now waiting for payment.
        support.assess(claimNumber, officer, new BigDecimal("44000.00"), true, "APPROVE");
        expect(support.post(officer, path(claimNumber, "/approve"), null), 200);
        assertThat(exposure("IN", "MOTOR", "openClaimedAmount")).isEqualByComparingTo(open0);
        assertThat(exposure("IN", "MOTOR", "approvedAwaitingPayment").subtract(approved0))
                .isEqualByComparingTo("43000.00");
        assertThat(exposure("IN", "MOTOR", "outstandingExposureBase").subtract(outstandingBase0))
                .isEqualByComparingTo("516.00");
        assertThat(exposure("IN", "MOTOR", "outstandingExposure").subtract(outstanding0))
                .isEqualByComparingTo("43000.00");
        Map<String, Object> approved = officerRow("workload", officer.userId());
        assertThat(approved.get("openAssigned")).isEqualTo(0);
        assertThat(approved.get("approvedAwaitingSettlement")).isEqualTo(1);
        assertThat(approved.get("oldestOpenAgeHours")).isNull();

        // Settled: the money has left, it is now settled total.
        expect(support.post(officer, path(claimNumber, "/settle"), Map.of("paymentMethod", "BANK_TRANSFER")), 200);
        assertThat(exposure("IN", "MOTOR", "approvedAwaitingPayment")).isEqualByComparingTo(approved0);
        assertThat(exposure("IN", "MOTOR", "settledTotal").subtract(settled0)).isEqualByComparingTo("43000.00");
        assertThat(officerRow("workload", officer.userId()).get("approvedAwaitingSettlement")).isEqualTo(0);

        // The performance row of the officer: one decision, an approval, settled, and the times.
        Map<String, Object> row = officerRow("performance", officer.userId());
        assertThat(row.get("decidedCount")).isEqualTo(1);
        assertThat(row.get("approvedCount")).isEqualTo(1);
        assertThat(row.get("rejectedCount")).isEqualTo(0);
        assertThat(number(row.get("approvalRate"))).isEqualByComparingTo("1.0000");
        assertThat(row.get("settledCount")).isEqualTo(1);
        assertThat(number(row.get("totalApprovedAmount"))).isEqualByComparingTo("43000.00");
        assertThat(row.get("currency")).isEqualTo("INR");
        assertThat(number(row.get("avgHoursToFirstReview")).signum()).isGreaterThanOrEqualTo(0);
        assertThat(number(row.get("avgHoursToDecision")).signum()).isGreaterThanOrEqualTo(0);
        assertThat(row.get("marketCode")).isEqualTo("IN");
    }

    @Test
    void theBaseCurrencyFiguresFollowTheConfiguredRate() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        BigDecimal approvedBase0 = exposure("IN", "MOTOR", "approvedAwaitingPaymentBase");
        BigDecimal settledBase0 = exposure("IN", "MOTOR", "settledTotalBase");
        String claimNumber = support.approved(holder, officer);

        // 43000.00 INR x 0.012 = 516.00 USD.
        assertThat(exposure("IN", "MOTOR", "approvedAwaitingPaymentBase").subtract(approvedBase0))
                .isEqualByComparingTo("516.00");
        expect(support.post(officer, path(claimNumber, "/settle"), Map.of("paymentMethod", "CHEQUE")), 200);
        assertThat(exposure("IN", "MOTOR", "approvedAwaitingPaymentBase")).isEqualByComparingTo(approvedBase0);
        assertThat(exposure("IN", "MOTOR", "settledTotalBase").subtract(settledBase0)).isEqualByComparingTo("516.00");
    }

    @Test
    void aYenClaimIsShownWithoutDecimalPlacesInItsMarketAndInUsdWithTwo() throws Exception {
        Holder holder = support.newHolder("JP");
        MvcResult before = report(support.manager(), "/exposure");
        support.submitMotor(holder, new BigDecimal("50000"));

        MvcResult after = report(support.manager(), "/exposure");

        String json = after.getResponse().getContentAsString();
        assertThat(json).contains("\"currencyCode\":\"JPY\"");
        List<Object> open = read(after, "$.rows[?(@.marketCode=='JP' && @.claimType=='MOTOR')].openClaimedAmount");
        List<Object> openBefore = read(before, "$.rows[?(@.marketCode=='JP' && @.claimType=='MOTOR')].openClaimedAmount");
        assertThat(number(open.get(0)).subtract(number(openBefore.get(0)))).isEqualByComparingTo("50000");
        assertThat(open.get(0).toString()).doesNotContain(".");
        List<Object> base = read(after, "$.rows[?(@.marketCode=='JP' && @.claimType=='MOTOR')].openClaimedAmountBase");
        List<Object> baseBefore = read(before,
                "$.rows[?(@.marketCode=='JP' && @.claimType=='MOTOR')].openClaimedAmountBase");
        // 50000 JPY x 0.0067 = 335.00 USD
        assertThat(number(base.get(0)).subtract(number(baseBefore.get(0)))).isEqualByComparingTo("335.00");
    }

    // ---- age and lateness --------------------------------------------------------------------------------------------------------

    @Test
    void theOpenClaimsAreSortedIntoTheAgeBandsByWholeDaysSinceSubmission() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        long fresh0 = agingCount(officer, "0-2 days");
        long week0 = agingCount(officer, "3-7 days");
        long fortnight0 = agingCount(officer, "8-14 days");
        long old0 = agingCount(officer, "15+ days");
        String twoDays = support.submitMotor(holder);
        String threeDays = support.submitMotor(holder);
        String tenDays = support.submitMotor(holder);
        String twentyDays = support.submitMotor(holder);
        submittedHoursAgo(twoDays, 2 * 24 + 23);
        submittedHoursAgo(threeDays, 3 * 24);
        submittedHoursAgo(tenDays, 10 * 24);
        submittedHoursAgo(twentyDays, 20 * 24);

        assertThat(agingCount(officer, "0-2 days")).isEqualTo(fresh0 + 1);
        assertThat(agingCount(officer, "3-7 days")).isEqualTo(week0 + 1);
        assertThat(agingCount(officer, "8-14 days")).isEqualTo(fortnight0 + 1);
        assertThat(agingCount(officer, "15+ days")).isEqualTo(old0 + 1);
    }

    @Test
    void aQueuedClaimThatWaitedMoreThanFortyEightHoursIsALateQueueClaimAndAClaimOverTenDaysOldIsALateOpenClaim()
            throws Exception {
        Account manager = support.manager();
        Account officer = support.newAccount(Role.OFFICER, "IN");
        Holder holder = support.newHolder("IN");
        long queueLate0 = marketQueue("IN", "queueSlaBreaches");
        long overviewLate0 = ((Number) read(report(manager, "/overview"), "$.queueSlaBreaches")).longValue();
        long openLate0 = ((Number) read(report(manager, "/overview"), "$.openClaimSlaBreaches")).longValue();
        String claimNumber = support.submitMotor(holder);

        submittedHoursAgo(claimNumber, 49);
        assertThat(marketQueue("IN", "queueSlaBreaches")).isEqualTo(queueLate0 + 1);
        assertThat(((Number) read(report(manager, "/overview"), "$.queueSlaBreaches")).longValue())
                .isEqualTo(overviewLate0 + 1);

        expect(support.post(officer, path(claimNumber, "/pick-up"), null), 200);
        assertThat(marketQueue("IN", "queueSlaBreaches")).isEqualTo(queueLate0);
        submittedHoursAgo(claimNumber, 11 * 24);
        Map<String, Object> row = officerRow("workload", officer.userId());
        assertThat(row.get("slaBreaches")).isEqualTo(1);
        assertThat(((Number) row.get("oldestOpenAgeHours")).longValue()).isGreaterThanOrEqualTo(264);
        assertThat(((Number) read(report(manager, "/overview"), "$.openClaimSlaBreaches")).longValue())
                .isEqualTo(openLate0 + 1);
    }

    // ---- the performance period -------------------------------------------------------------------------------------------------

    @Test
    void theDefaultPeriodIsTheThirtyDaysThatEndToday() throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        MvcResult result = report(support.manager(), "/performance");

        assertThat((String) read(result, "$.to")).isEqualTo(today.toString());
        assertThat((String) read(result, "$.from")).isEqualTo(today.minusDays(29).toString());
    }

    @Test
    void theDatesOfThePeriodAreInclusiveSoADecisionOfTodayIsInsideTodayAndOutsideYesterday() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        support.approved(support.newHolder("IN"), officer);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        MvcResult onlyToday = report(support.manager(), "/performance?from=" + today + "&to=" + today);
        MvcResult untilYesterday = report(support.manager(), "/performance?to=" + today.minusDays(1));

        List<Map<String, Object>> inside = read(onlyToday, "$.officers[?(@.userId=='" + officer.userId() + "')]");
        List<Map<String, Object>> outside = read(untilYesterday,
                "$.officers[?(@.userId=='" + officer.userId() + "')]");
        assertThat(inside.get(0).get("decidedCount")).isEqualTo(1);
        assertThat(outside.get(0).get("decidedCount")).isEqualTo(0);
        assertThat(outside.get(0).get("approvalRate")).isNull();
    }

    @Test
    void aPeriodThatEndsBeforeItStartsIsABadRequest() throws Exception {
        MvcResult result = support.get(support.manager(), REPORTS + "/performance?from=2099-01-01&to=2098-01-01");

        expect(result, 400);
        assertThat(code(result)).isEqualTo("VALIDATION_FAILED");
        assertThat((String) read(result, "$.errors[0].field")).isEqualTo("from");
    }

    @Test
    void aStartInTheFutureWithoutAnEndIsAlsoABadRequestAndABadDateIs() throws Exception {
        MvcResult future = support.get(support.manager(), REPORTS + "/performance?from=2099-01-01");
        MvcResult bad = support.get(support.manager(), REPORTS + "/performance?from=yesterday");

        expect(future, 400);
        expect(bad, 400);
        assertThat(code(bad)).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void onlyOfficersAppearAndADecisionOfAManagerIsCountedForNobody() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String claimNumber = support.escalated(support.newHolder("IN"), officer);
        expect(support.post(support.manager(), path(claimNumber, "/approve"), null), 200);

        MvcResult performance = report(support.manager(), "/performance");
        MvcResult workload = report(support.manager(), "/workload");

        assertThat((List<Object>) read(performance, "$.officers[?(@.userId=='manager.apac')]")).isEmpty();
        assertThat((List<Object>) read(workload, "$.officers[?(@.userId=='manager.apac')]")).isEmpty();
        Map<String, Object> row = officerRow("performance", officer.userId());
        assertThat(row.get("decidedCount")).isEqualTo(0);
        assertThat(officerRow("workload", officer.userId()).get("approvedAwaitingSettlement")).isEqualTo(1);
    }

    @Test
    void anOfficerWithNoClaimsStillHasARowWithZerosInTheWorkloadAndThePerformance() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");

        Map<String, Object> workload = officerRow("workload", officer.userId());
        Map<String, Object> performance = officerRow("performance", officer.userId());

        assertThat(workload.get("openAssigned")).isEqualTo(0);
        assertThat(workload.get("oldestOpenAgeHours")).isNull();
        assertThat(workload.get("fullName")).isNotNull();
        assertThat(performance.get("decidedCount")).isEqualTo(0);
        assertThat(performance.get("approvalRate")).isNull();
        assertThat(performance.get("avgHoursToFirstReview")).isNull();
        assertThat(performance.get("avgHoursToDecision")).isNull();
        assertThat(number(performance.get("totalApprovedAmount")).signum()).isZero();
    }

    @Test
    void anInactiveOfficerHasNoRow() throws Exception {
        String inactive = support.createUser(Role.OFFICER, "IN", false).getUserId();

        MvcResult workload = report(support.manager(), "/workload");

        assertThat((List<Object>) read(workload, "$.officers[?(@.userId=='" + inactive + "')]")).isEmpty();
    }
}
