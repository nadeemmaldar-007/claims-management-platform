package com.apac.claims.policy.api;

import static com.apac.claims.support.ClaimTestSupport.code;
import static com.apac.claims.support.ClaimTestSupport.expect;
import static com.apac.claims.support.ClaimTestSupport.freshDate;
import static com.apac.claims.support.ClaimTestSupport.read;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.identity.domain.Role;
import com.apac.claims.support.AbstractClaimApiTest;
import com.apac.claims.support.ClaimTestSupport.Account;
import com.apac.claims.support.TestData;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The policy endpoints: a claimant reads their own policies, staff read policies by number within their scope, and a
 * manager creates and updates policies (they stand in for the feed of a policy administration system). Every test
 * creates its own claimants and policy numbers.
 */
class PolicyApiTest extends AbstractClaimApiTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    private static String newPolicyNumber() {
        return "TPOL-" + TestData.unique().toUpperCase();
    }

    private Map<String, Object> body(String productType, String market, String holder) {
        return support.policyBody(productType, market, holder, "KA01AB1234", new BigDecimal("1000000.00"),
                new BigDecimal("5000.00"), TODAY.minusYears(1), TODAY.plusYears(1), "ACTIVE");
    }

    private MvcResult create(String number, Map<String, Object> body) throws Exception {
        return support.put(support.manager(), "/api/v1/policies/" + number, body);
    }

    private static List<String> fields(MvcResult result) throws Exception {
        return read(result, "$.errors[*].field");
    }

    // ---- reading -----------------------------------------------------------------------------------------------------------

    @Test
    void aClaimantListsOnlyTheirOwnPoliciesOrderedByNumber() throws Exception {
        var holder = support.newHolder("IN");
        support.newHolder("IN");

        MvcResult result = support.get(holder.account(), "/api/v1/policies/mine");

        expect(result, 200);
        List<String> numbers = read(result, "$[*].policyNumber");
        assertThat(numbers).containsExactly(holder.motorPolicy(), holder.propertyPolicy());
        assertThat((String) read(result, "$[0].holderUserId")).isEqualTo(holder.account().userId());
        assertThat((String) read(result, "$[0].currencyCode")).isEqualTo("INR");
    }

    @Test
    void aSeededClaimantSeesTheirActiveAndTheirLapsedPolicy() throws Exception {
        MvcResult result = support.get(support.seeded(Role.CLAIMANT, "IN"), "/api/v1/policies/mine");

        expect(result, 200);
        assertThat((List<String>) read(result, "$[*].policyNumber")).containsExactly("MOT-IN-0001", "MOT-IN-0002",
                "PRP-IN-0001");
        assertThat((List<String>) read(result, "$[*].status")).containsExactly("ACTIVE", "LAPSED", "ACTIVE");
    }

    @Test
    void anOfficerReadsAPolicyOfTheirOwnMarketAndTheNumberIsNotCaseSensitive() throws Exception {
        Account officer = support.seeded(Role.OFFICER, "IN");

        MvcResult upper = support.get(officer, "/api/v1/policies/MOT-IN-0001");
        MvcResult lower = support.get(officer, "/api/v1/policies/mot-in-0001");

        expect(upper, 200);
        expect(lower, 200);
        assertThat((String) read(upper, "$.policyNumber")).isEqualTo("MOT-IN-0001");
        assertThat((String) read(upper, "$.productType")).isEqualTo("MOTOR");
        assertThat(new BigDecimal(read(upper, "$.sumInsured").toString())).isEqualByComparingTo("1500000.00");
        assertThat((String) read(lower, "$.policyNumber")).isEqualTo("MOT-IN-0001");
    }

    @Test
    void anOfficerGets404ForAPolicyOfAnotherMarketExactlyAsForOneThatDoesNotExist() throws Exception {
        Account officer = support.seeded(Role.OFFICER, "IN");

        MvcResult foreign = support.get(officer, "/api/v1/policies/MOT-SG-0001");
        MvcResult missing = support.get(officer, "/api/v1/policies/MOT-ZZ-9999");

        expect(foreign, 404);
        expect(missing, 404);
        assertThat(code(foreign)).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat((String) read(foreign, "$.title")).isEqualTo((String) read(missing, "$.title"));
    }

    @Test
    void aManagerReadsThePoliciesOfEveryMarket() throws Exception {
        Account manager = support.manager();

        for (String number : List.of("MOT-IN-0001", "MOT-SG-0001", "PRP-JP-0001", "MOT-TH-0001")) {
            expect(support.get(manager, "/api/v1/policies/" + number), 200);
        }
    }

    @Test
    void aClaimantCannotReadAPolicyByNumberAndNobodyWithoutATokenCanReadAnything() throws Exception {
        MvcResult claimant = support.get(support.seeded(Role.CLAIMANT, "IN"), "/api/v1/policies/MOT-IN-0001");
        MvcResult anonymous = support.get(null, "/api/v1/policies/MOT-IN-0001");
        MvcResult officerMine = support.get(support.seeded(Role.OFFICER, "IN"), "/api/v1/policies/mine");

        expect(claimant, 403);
        expect(anonymous, 401);
        expect(officerMine, 403);
    }

    // ---- creating and updating ---------------------------------------------------------------------------------------------------

    @Test
    void aManagerCreatesAPolicyAndLaterUpdatesIt() throws Exception {
        Account claimant = support.newAccount(Role.CLAIMANT, "IN");
        String number = newPolicyNumber();

        MvcResult created = create(number, body("MOTOR", "IN", claimant.userId()));

        expect(created, 201);
        assertThat(created.getResponse().getHeader("Location")).isEqualTo("/api/v1/policies/" + number);
        assertThat((String) read(created, "$.policyNumber")).isEqualTo(number);
        assertThat((String) read(created, "$.holderUserId")).isEqualTo(claimant.userId());
        assertThat((String) read(created, "$.currencyCode")).isEqualTo("INR");
        assertThat((String) read(created, "$.status")).isEqualTo("ACTIVE");

        Map<String, Object> changed = support.policyBody("MOTOR", "IN", claimant.userId(), "KA02ZZ9999",
                new BigDecimal("2000000.00"), new BigDecimal("7500.00"), TODAY.minusYears(1), TODAY.plusYears(2),
                "CANCELLED");
        MvcResult updated = create(number, changed);

        expect(updated, 200);
        assertThat(updated.getResponse().getHeader("Location")).isNull();
        assertThat((String) read(updated, "$.insuredItem")).isEqualTo("KA02ZZ9999");
        assertThat(new BigDecimal(read(updated, "$.sumInsured").toString())).isEqualByComparingTo("2000000.00");
        assertThat((String) read(updated, "$.status")).isEqualTo("CANCELLED");
        assertThat((String) read(updated, "$.validTo")).isEqualTo(TODAY.plusYears(2).toString());
    }

    @Test
    void aLowerCasePolicyNumberInThePathIsStoredInUpperCase() throws Exception {
        Account claimant = support.newAccount(Role.CLAIMANT, "IN");
        String number = newPolicyNumber();

        MvcResult created = create(number.toLowerCase(), body("PROPERTY", "IN", claimant.userId()));

        expect(created, 201);
        assertThat((String) read(created, "$.policyNumber")).isEqualTo(number);
    }

    @Test
    void aNewlyCreatedPolicyCanBeUsedForAClaimAtOnce() throws Exception {
        Account claimant = support.newAccount(Role.CLAIMANT, "IN");
        String number = newPolicyNumber();
        expect(create(number, body("MOTOR", "IN", claimant.userId())), 201);

        MvcResult claim = support.submit(claimant, support.motorBody(number, new BigDecimal("1000.00"), freshDate()), null);

        expect(claim, 201);
        assertThat((String) read(claim, "$.policyNumber")).isEqualTo(number);
        assertThat(new BigDecimal(read(claim, "$.policySumInsured").toString())).isEqualByComparingTo("1000000.00");
    }

    @Test
    void lapsingAPolicyStopsNewClaimsUnderIt() throws Exception {
        var holder = support.newHolder("IN");
        expect(support.submit(holder.account(), support.motorBody(holder.motorPolicy(), new BigDecimal("1000.00"),
                freshDate()), null), 201);

        expect(create(holder.motorPolicy(), support.policyBody("MOTOR", "IN", holder.account().userId(), "KA01AB1234",
                new BigDecimal("1000000.00"), new BigDecimal("1000.00"), TODAY.minusYears(6), TODAY.plusYears(2),
                "LAPSED")), 200);

        MvcResult claim = support.submit(holder.account(), support.motorBody(holder.motorPolicy(),
                new BigDecimal("1000.00"), freshDate()), null);
        expect(claim, 422);
        assertThat(code(claim)).isEqualTo("POLICY_NOT_ACTIVE");
    }

    // ---- rules of the request (400) -------------------------------------------------------------------------------------------------

    @Test
    void aHolderWhoIsNotAClaimantIsRejected() throws Exception {
        Account officer = support.newAccount(Role.OFFICER, "IN");

        MvcResult result = create(newPolicyNumber(), body("MOTOR", "IN", officer.userId()));

        expect(result, 400);
        assertThat(code(result)).isEqualTo("VALIDATION_FAILED");
        assertThat(fields(result)).containsExactly("holderUserId");
    }

    @Test
    void anUnknownOrInactiveHolderIsRejected() throws Exception {
        AppUser inactive = support.createUser(Role.CLAIMANT, "IN", false);

        MvcResult unknown = create(newPolicyNumber(), body("MOTOR", "IN", "nobody.at.all"));
        MvcResult notActive = create(newPolicyNumber(), body("MOTOR", "IN", inactive.getUserId()));

        expect(unknown, 400);
        expect(notActive, 400);
        assertThat(fields(unknown)).containsExactly("holderUserId");
        assertThat(fields(notActive)).containsExactly("holderUserId");
    }

    @Test
    void aHolderOfAnotherMarketIsRejected() throws Exception {
        Account claimantSg = support.newAccount(Role.CLAIMANT, "SG");

        MvcResult result = create(newPolicyNumber(), body("MOTOR", "IN", claimantSg.userId()));

        expect(result, 400);
        assertThat(fields(result)).containsExactly("holderUserId");
        assertThat((String) read(result, "$.errors[0].message")).contains("IN");
    }

    @Test
    void anUnknownMarketIsRejected() throws Exception {
        Account claimant = support.newAccount(Role.CLAIMANT, "IN");

        MvcResult result = create(newPolicyNumber(), body("MOTOR", "ZZ", claimant.userId()));

        expect(result, 400);
        assertThat(fields(result)).containsExactly("marketCode");
    }

    @ParameterizedTest
    @CsvSource({"-1, 0", "0, 0", "-1, -1"})
    void aPolicyThatEndsOnOrBeforeItStartsIsRejected(int endOffsetDays, int startOffsetDays) throws Exception {
        Account claimant = support.newAccount(Role.CLAIMANT, "IN");
        Map<String, Object> body = support.policyBody("MOTOR", "IN", claimant.userId(), "KA01AB1234",
                new BigDecimal("1000000.00"), new BigDecimal("5000.00"), TODAY.plusDays(startOffsetDays),
                TODAY.plusDays(endOffsetDays), "ACTIVE");

        MvcResult result = create(newPolicyNumber(), body);

        expect(result, 400);
        assertThat(fields(result)).containsExactly("validTo");
    }

    @Test
    void aDeductibleAboveTheSumInsuredIsRejected() throws Exception {
        Account claimant = support.newAccount(Role.CLAIMANT, "IN");
        Map<String, Object> body = support.policyBody("MOTOR", "IN", claimant.userId(), "KA01AB1234",
                new BigDecimal("1000.00"), new BigDecimal("1000.01"), TODAY.minusYears(1), TODAY.plusYears(1), "ACTIVE");

        MvcResult result = create(newPolicyNumber(), body);

        expect(result, 400);
        assertThat(fields(result)).containsExactly("deductible");
    }

    @Test
    void anAmountWithTooManyDecimalPlacesForTheCurrencyIsRejected() throws Exception {
        Account claimant = support.newAccount(Role.CLAIMANT, "JP");
        Map<String, Object> body = support.policyBody("MOTOR", "JP", claimant.userId(), "NAGOYA 300 A 12-34",
                new BigDecimal("1000000.50"), new BigDecimal("5000"), TODAY.minusYears(1), TODAY.plusYears(1), "ACTIVE");

        MvcResult result = create(newPolicyNumber(), body);

        expect(result, 400);
        assertThat(fields(result)).containsExactly("sumInsured");
    }

    @Test
    void aSumInsuredOfZeroOrANegativeDeductibleIsRejected() throws Exception {
        Account claimant = support.newAccount(Role.CLAIMANT, "IN");
        Map<String, Object> zeroSum = support.policyBody("MOTOR", "IN", claimant.userId(), "KA01AB1234",
                BigDecimal.ZERO, BigDecimal.ZERO, TODAY.minusYears(1), TODAY.plusYears(1), "ACTIVE");
        Map<String, Object> negative = support.policyBody("MOTOR", "IN", claimant.userId(), "KA01AB1234",
                new BigDecimal("1000.00"), new BigDecimal("-1"), TODAY.minusYears(1), TODAY.plusYears(1), "ACTIVE");

        MvcResult sum = create(newPolicyNumber(), zeroSum);
        MvcResult deductible = create(newPolicyNumber(), negative);

        expect(sum, 400);
        expect(deductible, 400);
        assertThat(fields(sum)).contains("sumInsured");
        assertThat(fields(deductible)).containsExactly("deductible");
    }

    @Test
    void aPolicyNumberThatIsTooShortOrHasForbiddenCharactersIsRejected() throws Exception {
        Account claimant = support.newAccount(Role.CLAIMANT, "IN");

        MvcResult tooShort = create("AB-1", body("MOTOR", "IN", claimant.userId()));
        MvcResult badCharacters = create("POLICY_001", body("MOTOR", "IN", claimant.userId()));

        expect(tooShort, 400);
        expect(badCharacters, 400);
        assertThat(fields(tooShort)).containsExactly("policyNumber");
    }

    @Test
    void aMissingRequiredFieldIsRejected() throws Exception {
        Account claimant = support.newAccount(Role.CLAIMANT, "IN");
        Map<String, Object> body = body("MOTOR", "IN", claimant.userId());
        body.remove("status");
        body.remove("sumInsured");

        MvcResult result = create(newPolicyNumber(), body);

        expect(result, 400);
        assertThat(fields(result)).containsExactly("status", "sumInsured");
    }

    @Test
    void theTypeTheMarketAndTheHolderOfAnExistingPolicyCannotBeChanged() throws Exception {
        Account claimant = support.newAccount(Role.CLAIMANT, "IN");
        Account otherClaimant = support.newAccount(Role.CLAIMANT, "IN");
        Account claimantSg = support.newAccount(Role.CLAIMANT, "SG");
        String number = newPolicyNumber();
        expect(create(number, body("MOTOR", "IN", claimant.userId())), 201);

        MvcResult type = create(number, body("PROPERTY", "IN", claimant.userId()));
        MvcResult holder = create(number, body("MOTOR", "IN", otherClaimant.userId()));
        MvcResult market = create(number, body("MOTOR", "SG", claimantSg.userId()));

        expect(type, 400);
        expect(holder, 400);
        expect(market, 400);
        assertThat(fields(type)).containsExactly("productType");
        assertThat(fields(holder)).containsExactly("holderUserId");
        assertThat(fields(market)).containsExactlyInAnyOrder("holderUserId", "marketCode");
        MvcResult unchanged = support.get(support.manager(), "/api/v1/policies/" + number);
        assertThat((String) read(unchanged, "$.productType")).isEqualTo("MOTOR");
        assertThat((String) read(unchanged, "$.holderUserId")).isEqualTo(claimant.userId());
    }

    // ---- who may write ------------------------------------------------------------------------------------------------------------------

    @Test
    void aClaimantAndAnOfficerCannotCreateOrUpdateAPolicy() throws Exception {
        Account claimant = support.newAccount(Role.CLAIMANT, "IN");
        Account officer = support.newAccount(Role.OFFICER, "IN");
        String number = newPolicyNumber();
        Map<String, Object> body = body("MOTOR", "IN", claimant.userId());

        MvcResult byClaimant = support.put(claimant, "/api/v1/policies/" + number, body);
        MvcResult byOfficer = support.put(officer, "/api/v1/policies/" + number, body);
        MvcResult anonymous = support.put(null, "/api/v1/policies/" + number, body);

        expect(byClaimant, 403);
        expect(byOfficer, 403);
        expect(anonymous, 401);
        assertThat(code(byClaimant)).isEqualTo("ACCESS_DENIED");
        expect(support.get(support.manager(), "/api/v1/policies/" + number), 404);
    }
}
