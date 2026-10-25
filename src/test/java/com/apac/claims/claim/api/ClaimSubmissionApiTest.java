package com.apac.claims.claim.api;

import static com.apac.claims.support.ClaimTestSupport.code;
import static com.apac.claims.support.ClaimTestSupport.expect;
import static com.apac.claims.support.ClaimTestSupport.freshDate;
import static com.apac.claims.support.ClaimTestSupport.read;
import static com.apac.claims.support.ClaimTestSupport.recentDate;
import static com.apac.claims.support.ClaimTestSupport.rejectionBody;
import static com.apac.claims.support.ClaimTestSupport.status;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import com.apac.claims.claim.domain.ClaimType;
import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.identity.domain.Role;
import com.apac.claims.support.AbstractClaimApiTest;
import com.apac.claims.support.ClaimTestSupport.Account;
import com.apac.claims.support.ClaimTestSupport.Holder;
import com.apac.claims.support.TestData;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * First notice of loss through the API: what a good submission stores, and the documented status and error code of
 * every way a submission can be wrong. Each test works with a claimant of its own unless it needs the seeded data.
 */
class ClaimSubmissionApiTest extends AbstractClaimApiTest {

    private static final String CLAIM_NUMBER_PATTERN = "^CLM-\\d{5,}$";

    private static List<String> errorFields(MvcResult result) throws Exception {
        return read(result, "$.errors[*].field");
    }

    // ---- what a good submission stores ----------------------------------------------------------------------------

    @Test
    void aMotorClaimOfASeededClaimantIsStoredWithTheNumberStatusAndPolicySnapshots() throws Exception {
        Account claimant = support.seeded(Role.CLAIMANT, "IN");

        MvcResult result = support.submit(claimant, support.motorBody("MOT-IN-0001", new BigDecimal("45000.00"),
                recentDate()), null);

        expect(result, 201);
        String claimNumber = read(result, "$.claimNumber");
        assertThat(claimNumber).matches(CLAIM_NUMBER_PATTERN);
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/api/v1/claims/" + claimNumber);
        assertThat(result.getResponse().getHeader("Idempotent-Replay")).isNull();
        assertThat((String) read(result, "$.status")).isEqualTo("SUBMITTED");
        assertThat((String) read(result, "$.claimType")).isEqualTo("MOTOR");
        assertThat((String) read(result, "$.marketCode")).isEqualTo("IN");
        assertThat((String) read(result, "$.currencyCode")).isEqualTo("INR");
        assertThat((String) read(result, "$.claimantUserId")).isEqualTo("claimant.in");
        assertThat(new BigDecimal(read(result, "$.claimedAmount").toString())).isEqualByComparingTo("45000.00");
        // Seeded motor policy: sum insured 3 x 500000, deductible 1% of 500000.
        assertThat(new BigDecimal(read(result, "$.policySumInsured").toString())).isEqualByComparingTo("1500000.00");
        assertThat(new BigDecimal(read(result, "$.policyDeductible").toString())).isEqualByComparingTo("5000.00");
        assertThat((String) read(result, "$.motor.vehicleRegistrationNumber")).isEqualTo("MH12AB1234");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("\"property\"")
                .doesNotContain("assignedOfficerUserId");
    }

    @Test
    void aPropertyClaimGetsAClpNumberAndTheSeededPropertyPolicySnapshots() throws Exception {
        Account claimant = support.seeded(Role.CLAIMANT, "IN");

        MvcResult result = support.submit(claimant, support.propertyBody("PRP-IN-0001", new BigDecimal("250000.00"),
                recentDate()), null);

        expect(result, 201);
        assertThat((String) read(result, "$.claimNumber")).matches("^CLP-\\d{5,}$");
        assertThat((String) read(result, "$.claimType")).isEqualTo("PROPERTY");
        assertThat(new BigDecimal(read(result, "$.policySumInsured").toString())).isEqualByComparingTo("5000000.00");
        assertThat((String) read(result, "$.property.propertyType")).isEqualTo("RESIDENTIAL");
        assertThat((String) read(result, "$.property.perilType")).isEqualTo("FLOOD");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("\"motor\"");
    }

    @Test
    void twoClaimsOfOneTypeGetIncreasingNumbers() throws Exception {
        Holder holder = support.newHolder("IN");

        String first = support.submitMotor(holder);
        String second = support.submitMotor(holder);

        assertThat(Long.parseLong(second.substring(4))).isGreaterThan(Long.parseLong(first.substring(4)));
    }

    @Test
    void motorAndPropertyClaimsCountTheirNumbersIndependently() throws Exception {
        Holder holder = support.newHolder("IN");
        long motorBefore = nextCounterValue(ClaimType.MOTOR);
        long propertyBefore = nextCounterValue(ClaimType.PROPERTY);

        String motor = support.submitMotor(holder);
        assertThat(nextCounterValue(ClaimType.MOTOR)).isEqualTo(motorBefore + 1);
        assertThat(nextCounterValue(ClaimType.PROPERTY)).isEqualTo(propertyBefore);
        String property = support.submitProperty(holder);

        assertThat(motor).isEqualTo("CLM-" + motorBefore);
        assertThat(property).isEqualTo("CLP-" + propertyBefore);
        assertThat(nextCounterValue(ClaimType.PROPERTY)).isEqualTo(propertyBefore + 1);
    }

    @ParameterizedTest
    @CsvSource({"SG, SGD", "AU, AUD", "JP, JPY", "MY, MYR", "TH, THB"})
    void theClaimTakesTheCurrencyOfTheClaimantsMarket(String market, String currency) throws Exception {
        Holder holder = support.newHolder(market);

        MvcResult result = support.submit(holder.account(),
                support.motorBody(holder.motorPolicy(), new BigDecimal("500"), freshDate()), null);

        expect(result, 201);
        assertThat((String) read(result, "$.currencyCode")).isEqualTo(currency);
        assertThat((String) read(result, "$.marketCode")).isEqualTo(market);
    }

    @Test
    void theSnapshotsStayWhenThePolicyChangesAfterwards() throws Exception {
        Holder holder = support.newHolder("IN", new BigDecimal("1000000"), new BigDecimal("1000"));
        String claimNumber = support.submitMotor(holder);

        MvcResult update = support.put(support.manager(), "/api/v1/policies/" + holder.motorPolicy(),
                support.policyBody("MOTOR", "IN", holder.account().userId(), "KA01AB1234", new BigDecimal("2000000"),
                        new BigDecimal("9000"), LocalDate.now(ZoneOffset.UTC).minusYears(6),
                        LocalDate.now(ZoneOffset.UTC).plusYears(2), "ACTIVE"));
        expect(update, 200);

        MvcResult detail = support.get(holder.account(), "/api/v1/claims/" + claimNumber);
        assertThat(new BigDecimal(read(detail, "$.policySumInsured").toString())).isEqualByComparingTo("1000000");
        assertThat(new BigDecimal(read(detail, "$.policyDeductible").toString())).isEqualByComparingTo("1000");
    }

    @Test
    void aClaimForTomorrowInUtcIsAcceptedBecauseMarketsAheadOfUtcAlreadyLiveThere() throws Exception {
        Holder holder = support.newHolder("IN");
        LocalDate tomorrow = LocalDate.now(ZoneOffset.UTC).plusDays(1);

        MvcResult result = support.submit(holder.account(),
                support.motorBody(holder.motorPolicy(), new BigDecimal("1000.00"), tomorrow), null);

        expect(result, 201);
    }

    @Test
    void aClaimForTheDayAfterTomorrowIsRejected() throws Exception {
        Holder holder = support.newHolder("IN");

        MvcResult result = support.submit(holder.account(), support.motorBody(holder.motorPolicy(),
                new BigDecimal("1000.00"), LocalDate.now(ZoneOffset.UTC).plusDays(2)), null);

        expect(result, 400);
        assertThat(code(result)).isEqualTo("VALIDATION_FAILED");
        assertThat(errorFields(result)).containsExactly("incidentDate");
    }

    @Test
    void anIncidentExactlyFiveYearsAgoIsStillAccepted() throws Exception {
        Holder holder = support.newHolder("IN");

        MvcResult result = support.submit(holder.account(), support.motorBody(holder.motorPolicy(),
                new BigDecimal("1000.00"), LocalDate.now(ZoneOffset.UTC).minusYears(5)), null);

        expect(result, 201);
    }

    @Test
    void aClaimForTheFullSumInsuredIsAccepted() throws Exception {
        Holder holder = support.newHolder("IN", new BigDecimal("50000.00"), new BigDecimal("500.00"));

        MvcResult result = support.submit(holder.account(), support.motorBody(holder.motorPolicy(),
                new BigDecimal("50000.00"), freshDate()), null);

        expect(result, 201);
    }

    // ---- request validation (400) ------------------------------------------------------------------------------------

    @Test
    void aMotorClaimWithoutTheMotorBlockIsRejected() throws Exception {
        Holder holder = support.newHolder("IN");
        Map<String, Object> body = support.motorBody(holder.motorPolicy(), new BigDecimal("1000.00"), freshDate());
        body.remove("motor");

        MvcResult result = support.submit(holder.account(), body, null);

        expect(result, 400);
        assertThat(code(result)).isEqualTo("VALIDATION_FAILED");
        assertThat(errorFields(result)).containsExactly("motor");
    }

    @Test
    void aPropertyClaimWithoutThePropertyBlockIsRejected() throws Exception {
        Holder holder = support.newHolder("IN");
        Map<String, Object> body = support.propertyBody(holder.propertyPolicy(), new BigDecimal("1000.00"),
                freshDate());
        body.remove("property");

        MvcResult result = support.submit(holder.account(), body, null);

        expect(result, 400);
        assertThat(errorFields(result)).containsExactly("property");
    }

    @Test
    void aMotorClaimWithAPropertyBlockInsteadOfTheMotorBlockReportsBothMistakes() throws Exception {
        Holder holder = support.newHolder("IN");
        Map<String, Object> body = support.motorBody(holder.motorPolicy(), new BigDecimal("1000.00"), freshDate());
        body.remove("motor");
        body.put("property", support.propertyBody(holder.propertyPolicy(), new BigDecimal("1.00"), freshDate())
                .get("property"));

        MvcResult result = support.submit(holder.account(), body, null);

        expect(result, 400);
        assertThat(errorFields(result)).containsExactly("motor", "property");
    }

    @Test
    void aClaimWithBothBlocksIsRejected() throws Exception {
        Holder holder = support.newHolder("IN");
        Map<String, Object> body = support.motorBody(holder.motorPolicy(), new BigDecimal("1000.00"), freshDate());
        body.put("property", support.propertyBody(holder.propertyPolicy(), new BigDecimal("1.00"), freshDate())
                .get("property"));

        MvcResult result = support.submit(holder.account(), body, null);

        expect(result, 400);
        assertThat(errorFields(result)).containsExactly("property");
    }

    @Test
    void anUnknownEnumValueIsAMalformedRequest() throws Exception {
        Holder holder = support.newHolder("IN");
        Map<String, Object> body = support.motorBody(holder.motorPolicy(), new BigDecimal("1000.00"), freshDate());
        @SuppressWarnings("unchecked")
        Map<String, Object> motor = (Map<String, Object>) body.get("motor");
        motor.put("damageType", "EXPLOSION");

        MvcResult result = support.submit(holder.account(), body, null);

        expect(result, 400);
        assertThat(code(result)).isEqualTo("MALFORMED_REQUEST");
    }

    @ParameterizedTest
    @ValueSource(strings = {"short", "123456789", ""})
    void aDescriptionUnderTenCharactersIsRejected(String description) throws Exception {
        Holder holder = support.newHolder("IN");
        Map<String, Object> body = support.motorBody(holder.motorPolicy(), new BigDecimal("1000.00"), freshDate());
        body.put("description", description);

        MvcResult result = support.submit(holder.account(), body, null);

        expect(result, 400);
        assertThat(errorFields(result)).contains("description");
    }

    @Test
    void aDescriptionOverAThousandCharactersIsRejected() throws Exception {
        Holder holder = support.newHolder("IN");
        Map<String, Object> body = support.motorBody(holder.motorPolicy(), new BigDecimal("1000.00"), freshDate());
        body.put("description", "x".repeat(1001));

        MvcResult result = support.submit(holder.account(), body, null);

        expect(result, 400);
        assertThat(errorFields(result)).containsExactly("description");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-1", "-45000.50"})
    void anAmountThatIsNotAboveZeroIsRejected(String amount) throws Exception {
        Holder holder = support.newHolder("IN");

        MvcResult result = support.submit(holder.account(), support.motorBody(holder.motorPolicy(),
                new BigDecimal(amount), freshDate()), null);

        expect(result, 400);
        assertThat(code(result)).isEqualTo("VALIDATION_FAILED");
        assertThat(errorFields(result)).containsExactly("claimedAmount");
    }

    @Test
    void anAmountAboveOneHundredMillionIsRejected() throws Exception {
        Holder holder = support.newHolder("IN");

        MvcResult result = support.submit(holder.account(), support.motorBody(holder.motorPolicy(),
                new BigDecimal("100000000.01"), freshDate()), null);

        expect(result, 400);
        assertThat(errorFields(result)).containsExactly("claimedAmount");
    }

    @ParameterizedTest
    @CsvSource({"JP, 100.50", "JP, 100.1", "SG, 100.123", "IN, 0.001"})
    void anAmountWithMoreDecimalPlacesThanTheCurrencyAllowsIsRejected(String market, String amount) throws Exception {
        Holder holder = support.newHolder(market);

        MvcResult result = support.submit(holder.account(), support.motorBody(holder.motorPolicy(),
                new BigDecimal(amount), freshDate()), null);

        expect(result, 400);
        assertThat(errorFields(result)).containsExactly("claimedAmount");
    }

    @Test
    void anAmountOfWholeYenWithTrailingZerosIsAccepted() throws Exception {
        Holder holder = support.newHolder("JP");

        MvcResult result = support.submit(holder.account(), support.motorBody(holder.motorPolicy(),
                new BigDecimal("5000.00"), freshDate()), null);

        expect(result, 201);
        assertThat(new BigDecimal(read(result, "$.claimedAmount").toString())).isEqualByComparingTo("5000");
    }

    @Test
    void anIncidentDateFarInTheFutureIsRejected() throws Exception {
        Holder holder = support.newHolder("IN");

        MvcResult result = support.submit(holder.account(), support.motorBody(holder.motorPolicy(),
                new BigDecimal("1000.00"), LocalDate.now(ZoneOffset.UTC).plusYears(1)), null);

        expect(result, 400);
        assertThat(errorFields(result)).containsExactly("incidentDate");
    }

    @Test
    void anIncidentOlderThanFiveYearsIsRejected() throws Exception {
        Holder holder = support.newHolder("IN");

        MvcResult result = support.submit(holder.account(), support.motorBody(holder.motorPolicy(),
                new BigDecimal("1000.00"), LocalDate.now(ZoneOffset.UTC).minusYears(5).minusDays(1)), null);

        expect(result, 400);
        assertThat(errorFields(result)).containsExactly("incidentDate");
    }

    @ParameterizedTest
    @ValueSource(ints = {1979, 0, 2200})
    void aVehicleYearOutsideTheAllowedRangeIsRejected(int year) throws Exception {
        Holder holder = support.newHolder("IN");
        Map<String, Object> body = support.motorBody(holder.motorPolicy(), new BigDecimal("1000.00"), freshDate());
        @SuppressWarnings("unchecked")
        Map<String, Object> motor = (Map<String, Object>) body.get("motor");
        motor.put("vehicleYear", year);

        MvcResult result = support.submit(holder.account(), body, null);

        expect(result, 400);
        assertThat(errorFields(result)).containsExactly("motor.vehicleYear");
    }

    @Test
    void severalMistakesAreReportedTogetherSortedByField() throws Exception {
        Holder holder = support.newHolder("JP");
        Map<String, Object> body = support.motorBody(holder.motorPolicy(), new BigDecimal("10.5"),
                LocalDate.now(ZoneOffset.UTC).plusDays(30));
        body.remove("motor");

        MvcResult result = support.submit(holder.account(), body, null);

        expect(result, 400);
        assertThat(errorFields(result)).containsExactly("claimedAmount", "incidentDate", "motor");
    }

    // ---- policy rules (422) and duplicates (409) ----------------------------------------------------------------------

    @Test
    void aPolicyThatDoesNotExistIsRejected() throws Exception {
        Holder holder = support.newHolder("IN");

        MvcResult result = support.submit(holder.account(), support.motorBody("MOT-ZZ-9999", new BigDecimal("1000.00"),
                freshDate()), null);

        expect(result, 422);
        assertThat(code(result)).isEqualTo("POLICY_NOT_FOUND");
    }

    @Test
    void anotherClaimantsPolicyLooksExactlyLikeAPolicyThatDoesNotExist() throws Exception {
        Account claimant = support.seeded(Role.CLAIMANT, "IN");

        MvcResult result = support.submit(claimant, support.motorBody("MOT-SG-0001", new BigDecimal("1000.00"),
                recentDate()), null);

        expect(result, 422);
        assertThat(code(result)).isEqualTo("POLICY_NOT_FOUND");
    }

    @Test
    void aLapsedPolicyIsRejected() throws Exception {
        Account claimant = support.seeded(Role.CLAIMANT, "IN");

        // MOT-IN-0002 covers the year before last year; the incident lies inside that cover, so only the status counts.
        MvcResult result = support.submit(claimant, support.motorBody("MOT-IN-0002", new BigDecimal("1000.00"),
                LocalDate.now(ZoneOffset.UTC).minusDays(400)), null);

        expect(result, 422);
        assertThat(code(result)).isEqualTo("POLICY_NOT_ACTIVE");
    }

    @Test
    void anIncidentOutsideThePeriodOfCoverIsRejected() throws Exception {
        Account claimant = support.seeded(Role.CLAIMANT, "IN");

        // The seeded active policy starts one year before the seeding date.
        MvcResult result = support.submit(claimant, support.motorBody("MOT-IN-0001", new BigDecimal("1000.00"),
                LocalDate.now(ZoneOffset.UTC).minusYears(2)), null);

        expect(result, 422);
        assertThat(code(result)).isEqualTo("POLICY_NOT_ACTIVE");
    }

    @Test
    void aMotorClaimOnAPropertyPolicyIsRejected() throws Exception {
        Holder holder = support.newHolder("IN");

        MvcResult result = support.submit(holder.account(), support.motorBody(holder.propertyPolicy(),
                new BigDecimal("1000.00"), freshDate()), null);

        expect(result, 422);
        assertThat(code(result)).isEqualTo("POLICY_TYPE_MISMATCH");
    }

    @Test
    void aPolicyOfAnotherMarketThanTheClaimantsIsRejected() throws Exception {
        Holder holder = support.newHolder("IN");
        AppUser holderEntity = users.findByUserId(holder.account().userId()).orElseThrow();
        holderEntity.setMarket(markets.findByCode("SG").orElseThrow());
        users.saveAndFlush(holderEntity);

        MvcResult result = support.submit(holder.account(), support.motorBody(holder.motorPolicy(),
                new BigDecimal("1000.00"), freshDate()), null);

        expect(result, 422);
        assertThat(code(result)).isEqualTo("POLICY_MARKET_MISMATCH");
    }

    @Test
    void anAmountAboveTheSumInsuredIsRejected() throws Exception {
        Holder holder = support.newHolder("IN", new BigDecimal("50000.00"), new BigDecimal("500.00"));

        MvcResult result = support.submit(holder.account(), support.motorBody(holder.motorPolicy(),
                new BigDecimal("50000.01"), freshDate()), null);

        expect(result, 422);
        assertThat(code(result)).isEqualTo("AMOUNT_EXCEEDS_SUM_INSURED");
    }

    @Test
    void theSamePolicyDateAndTypeCannotBeClaimedTwice() throws Exception {
        Holder holder = support.newHolder("IN");
        LocalDate date = freshDate();
        expect(support.submit(holder.account(), support.motorBody(holder.motorPolicy(), new BigDecimal("1000.00"),
                date), null), 201);

        MvcResult second = support.submit(holder.account(), support.motorBody(holder.motorPolicy(),
                new BigDecimal("2000.00"), date), null);

        expect(second, 409);
        assertThat(code(second)).isEqualTo("DUPLICATE_CLAIM");
        assertThat(claimRowsOfPolicy(holder.motorPolicy())).isEqualTo(1);
    }

    @Test
    void theSameDayIsAllowedAgainOnceTheFirstClaimWasRejected() throws Exception {
        Holder holder = support.newHolder("IN");
        Account officer = support.newAccount(Role.OFFICER, "IN");
        LocalDate date = freshDate();
        MvcResult first = support.submit(holder.account(), support.motorBody(holder.motorPolicy(),
                new BigDecimal("1000.00"), date), null);
        expect(first, 201);
        String firstNumber = read(first, "$.claimNumber");
        expect(support.post(officer, "/api/v1/claims/" + firstNumber + "/pick-up", null), 200);
        expect(support.post(officer, "/api/v1/claims/" + firstNumber + "/reject", rejectionBody()), 200);

        MvcResult again = support.submit(holder.account(), support.motorBody(holder.motorPolicy(),
                new BigDecimal("1000.00"), date), null);

        expect(again, 201);
        assertThat((String) read(again, "$.claimNumber")).isNotEqualTo(firstNumber);
    }

    @Test
    void aMotorAndAPropertyClaimOfTheSameDayDoNotCollide() throws Exception {
        Holder holder = support.newHolder("IN");
        LocalDate date = freshDate();

        expect(support.submit(holder.account(), support.motorBody(holder.motorPolicy(), new BigDecimal("1000.00"),
                date), null), 201);
        expect(support.submit(holder.account(), support.propertyBody(holder.propertyPolicy(),
                new BigDecimal("1000.00"), date), null), 201);
    }

    @Test
    void aFailedSubmissionDoesNotConsumeAClaimNumber() throws Exception {
        Holder holder = support.newHolder("IN", new BigDecimal("50000.00"), new BigDecimal("500.00"));
        LocalDate date = freshDate();
        long before = nextCounterValue(ClaimType.MOTOR);

        expect(support.submit(holder.account(), support.motorBody(holder.motorPolicy(), new BigDecimal("-1"), date),
                null), 400);
        expect(support.submit(holder.account(), support.motorBody("MOT-ZZ-9999", new BigDecimal("1000.00"), date),
                null), 422);
        expect(support.submit(holder.account(), support.motorBody(holder.motorPolicy(), new BigDecimal("50000.01"),
                date), null), 422);
        assertThat(nextCounterValue(ClaimType.MOTOR)).isEqualTo(before);

        MvcResult ok = support.submit(holder.account(), support.motorBody(holder.motorPolicy(),
                new BigDecimal("1000.00"), date), null);
        expect(ok, 201);
        assertThat((String) read(ok, "$.claimNumber")).isEqualTo("CLM-" + before);
        expect(support.submit(holder.account(), support.motorBody(holder.motorPolicy(), new BigDecimal("1000.00"),
                date), null), 409);
        assertThat(nextCounterValue(ClaimType.MOTOR)).isEqualTo(before + 1);
    }

    // ---- idempotency ---------------------------------------------------------------------------------------------------

    @Test
    void aRepeatedKeyReturnsTheOriginalClaimWithTheReplayHeaderAndCreatesOneRow() throws Exception {
        Holder holder = support.newHolder("IN");
        Map<String, Object> body = support.motorBody(holder.motorPolicy(), new BigDecimal("1000.00"), freshDate());
        String key = "key-" + TestData.unique();

        MvcResult first = support.submit(holder.account(), body, key);
        MvcResult replay = support.submit(holder.account(), body, key);

        expect(first, 201);
        expect(replay, 200);
        assertThat(first.getResponse().getHeader("Idempotent-Replay")).isNull();
        assertThat(replay.getResponse().getHeader("Idempotent-Replay")).isEqualTo("true");
        assertThat((String) read(replay, "$.claimNumber")).isEqualTo((String) read(first, "$.claimNumber"));
        assertThat(claimRowsOfPolicy(holder.motorPolicy())).isEqualTo(1);
    }

    @Test
    void aReplayDoesNotConsumeAClaimNumberOrAddATimelineEntry() throws Exception {
        Holder holder = support.newHolder("IN");
        Map<String, Object> body = support.motorBody(holder.motorPolicy(), new BigDecimal("1000.00"), freshDate());
        String key = "key-" + TestData.unique();
        String claimNumber = read(support.submit(holder.account(), body, key), "$.claimNumber");
        long counter = nextCounterValue(ClaimType.MOTOR);
        int entries = timelineCount(claimNumber);

        expect(support.submit(holder.account(), body, key), 200);

        assertThat(nextCounterValue(ClaimType.MOTOR)).isEqualTo(counter);
        assertThat(timelineCount(claimNumber)).isEqualTo(entries);
        assertThat(eventTypesOf(claimNumber)).containsExactly("ClaimSubmitted");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 7, 65, 200})
    void anIdempotencyKeyThatIsTooShortOrTooLongIsRejected(int length) throws Exception {
        Holder holder = support.newHolder("IN");
        long before = nextCounterValue(ClaimType.MOTOR);

        MvcResult result = support.submit(holder.account(), support.motorBody(holder.motorPolicy(),
                new BigDecimal("1000.00"), freshDate()), "k".repeat(length));

        expect(result, 400);
        assertThat(errorFields(result)).containsExactly("Idempotency-Key");
        assertThat(nextCounterValue(ClaimType.MOTOR)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(ints = {8, 64})
    void anIdempotencyKeyOfEightOrSixtyFourCharactersIsAccepted(int length) throws Exception {
        Holder holder = support.newHolder("IN");

        MvcResult result = support.submit(holder.account(), support.motorBody(holder.motorPolicy(),
                new BigDecimal("1000.00"), freshDate()), "k".repeat(length));

        expect(result, 201);
    }

    @Test
    void theSameKeyFromAnotherUserIsIndependent() throws Exception {
        Holder one = support.newHolder("IN");
        Holder two = support.newHolder("IN");
        String key = "shared-key-" + TestData.unique();

        MvcResult first = support.submit(one.account(), support.motorBody(one.motorPolicy(),
                new BigDecimal("1000.00"), freshDate()), key);
        MvcResult second = support.submit(two.account(), support.motorBody(two.motorPolicy(),
                new BigDecimal("1000.00"), freshDate()), key);

        expect(first, 201);
        expect(second, 201);
        assertThat((String) read(second, "$.claimNumber")).isNotEqualTo((String) read(first, "$.claimNumber"));
    }

    // ---- who may submit ------------------------------------------------------------------------------------------------

    @Test
    void anOfficerAndAManagerCannotSubmitAClaim() throws Exception {
        Holder holder = support.newHolder("IN");
        Map<String, Object> body = support.motorBody(holder.motorPolicy(), new BigDecimal("1000.00"), freshDate());

        MvcResult officer = support.submit(support.seeded(Role.OFFICER, "IN"), body, null);
        MvcResult manager = support.submit(support.manager(), body, null);

        expect(officer, 403);
        expect(manager, 403);
        assertThat(code(officer)).isEqualTo("ACCESS_DENIED");
        assertThat(claimRowsOfPolicy(holder.motorPolicy())).isZero();
    }

    @Test
    void submittingWithoutATokenIsUnauthenticated() throws Exception {
        MvcResult result = support.submit(null, support.motorBody("MOT-IN-0001", new BigDecimal("1000.00"),
                recentDate()), null);

        expect(result, 401);
        assertThat(status(result)).isEqualTo(401);
        assertThat(code(result)).isEqualTo("UNAUTHENTICATED");
    }
}
