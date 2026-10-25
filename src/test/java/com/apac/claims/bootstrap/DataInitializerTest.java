package com.apac.claims.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.apac.claims.claim.domain.ClaimCounterRepository;
import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.identity.domain.Role;
import com.apac.claims.identity.domain.UserRepository;
import com.apac.claims.market.domain.MarketRepository;
import com.apac.claims.policy.domain.Policy;
import com.apac.claims.policy.domain.PolicyRepository;
import com.apac.claims.policy.domain.PolicyStatus;
import com.apac.claims.policy.domain.ProductType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs the whole application context on H2 and checks what the seeder left behind. Almost every test only reads.
 * The one exception renames a seeded account to prove the seeder never overwrites it, and restores the name in a
 * {@code finally} block; the surefire default of sequential execution keeps that from disturbing other tests.
 */
@SpringBootTest
@ActiveProfiles("test")
class DataInitializerTest {

    private static final String DEMO_EMAIL_SUFFIX = "@claims-demo.example";
    private static final Pattern DEMO_POLICY_NUMBER = Pattern.compile("^(MOT|PRP)-(IN|SG|AU|JP|MY|TH)-000[12]$");

    @Autowired
    private DataInitializer initializer;
    @Autowired
    private UserRepository users;
    @Autowired
    private PolicyRepository policies;
    @Autowired
    private MarketRepository markets;
    @Autowired
    private ClaimCounterRepository counters;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private List<AppUser> demoUsers() {
        return users.findAll().stream().filter(u -> u.getEmail().endsWith(DEMO_EMAIL_SUFFIX)).toList();
    }

    private List<Policy> demoPolicies() {
        return policies.findAll().stream()
                .filter(p -> DEMO_POLICY_NUMBER.matcher(p.getPolicyNumber()).matches()).toList();
    }

    @Test
    void startupLeavesSixMarketsTwoCountersThirteenUsersAndThirteenPolicies() {
        assertThat(markets.count()).isEqualTo(6);
        assertThat(counters.count()).isEqualTo(2);
        assertThat(demoUsers()).hasSize(13);
        assertThat(demoPolicies()).hasSize(13);
    }

    @Test
    void theThirteenAccountsAreTheSpecifiedOnes() {
        assertThat(demoUsers()).extracting(AppUser::getUserId).containsExactlyInAnyOrder(
                "manager.apac",
                "officer.in", "officer.sg", "officer.au", "officer.jp", "officer.my", "officer.th",
                "claimant.in", "claimant.sg", "claimant.au", "claimant.jp", "claimant.my", "claimant.th");
    }

    @ParameterizedTest
    @CsvSource({
            "manager.apac, Manager@123",
            "officer.in, Officer@123", "officer.sg, Officer@123", "officer.au, Officer@123",
            "officer.jp, Officer@123", "officer.my, Officer@123", "officer.th, Officer@123",
            "claimant.in, Claimant@123", "claimant.sg, Claimant@123", "claimant.au, Claimant@123",
            "claimant.jp, Claimant@123", "claimant.my, Claimant@123", "claimant.th, Claimant@123"
    })
    void passwordsAreStoredHashedAndMatchTheDocumentedOnes(String userId, String password) {
        AppUser user = users.findByUserId(userId).orElseThrow();

        assertThat(user.getPasswordHash()).isNotEqualTo(password).startsWith("$2");
        assertThat(passwordEncoder.matches(password, user.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches(password + "x", user.getPasswordHash())).isFalse();
    }

    @ParameterizedTest
    @CsvSource(value = {
            "manager.apac, MANAGER, NONE, APAC Manager",
            "officer.in, OFFICER, IN, Officer India",
            "officer.sg, OFFICER, SG, Officer Singapore",
            "officer.au, OFFICER, AU, Officer Australia",
            "officer.jp, OFFICER, JP, Officer Japan",
            "officer.my, OFFICER, MY, Officer Malaysia",
            "officer.th, OFFICER, TH, Officer Thailand",
            "claimant.in, CLAIMANT, IN, Claimant India",
            "claimant.sg, CLAIMANT, SG, Claimant Singapore",
            "claimant.au, CLAIMANT, AU, Claimant Australia",
            "claimant.jp, CLAIMANT, JP, Claimant Japan",
            "claimant.my, CLAIMANT, MY, Claimant Malaysia",
            "claimant.th, CLAIMANT, TH, Claimant Thailand"
    }, nullValues = "NONE")
    void everyAccountHasTheRightRoleMarketNameAndEmail(String userId, Role role, String marketCode, String fullName) {
        transactionTemplate.executeWithoutResult(status -> {
            AppUser user = users.findByUserId(userId).orElseThrow();

            assertThat(user.getRole()).isEqualTo(role);
            assertThat(user.getFullName()).isEqualTo(fullName);
            assertThat(user.getEmail()).isEqualTo(userId + DEMO_EMAIL_SUFFIX);
            assertThat(user.isActive()).isTrue();
            if (marketCode == null) {
                assertThat(user.getMarket()).isNull();
            } else {
                assertThat(user.getMarket().getCode()).isEqualTo(marketCode);
            }
        });
    }

    @Test
    void everyClaimantHoldsOneMotorAndOnePropertyPolicyInTheirOwnMarket() {
        transactionTemplate.executeWithoutResult(status -> {
            for (String code : List.of("IN", "SG", "AU", "JP", "MY", "TH")) {
                AppUser claimant = users.findByUserId("claimant." + code.toLowerCase()).orElseThrow();
                Policy motor = policies.findByPolicyNumber("MOT-" + code + "-0001").orElseThrow();
                Policy property = policies.findByPolicyNumber("PRP-" + code + "-0001").orElseThrow();

                assertThat(motor.getHolder()).isEqualTo(claimant);
                assertThat(motor.getProductType()).isEqualTo(ProductType.MOTOR);
                assertThat(motor.getMarket().getCode()).isEqualTo(code);
                assertThat(motor.getStatus()).isEqualTo(PolicyStatus.ACTIVE);
                assertThat(property.getHolder()).isEqualTo(claimant);
                assertThat(property.getProductType()).isEqualTo(ProductType.PROPERTY);
                assertThat(property.getMarket().getCode()).isEqualTo(code);
                assertThat(property.getStatus()).isEqualTo(PolicyStatus.ACTIVE);
                assertThat(motor.getInsuredItem()).isNotBlank();
                assertThat(property.getInsuredItem()).isNotBlank();
            }
        });
    }

    /** Expected figures are written out from the specification (3 x, 10 x and 1% of the threshold), not recomputed. */
    @ParameterizedTest
    @CsvSource({
            "IN, 1500000.00, 5000000.00, 5000.00",
            "SG, 30000.00, 100000.00, 100.00",
            "AU, 30000.00, 100000.00, 100.00",
            "JP, 3000000.00, 10000000.00, 10000.00",
            "MY, 75000.00, 250000.00, 250.00",
            "TH, 600000.00, 2000000.00, 2000.00"
    })
    void sumInsuredAndDeductibleFollowTheMarketThreshold(String code, String motorSum, String propertySum,
                                                         String deductible) {
        Policy motor = policies.findByPolicyNumber("MOT-" + code + "-0001").orElseThrow();
        Policy property = policies.findByPolicyNumber("PRP-" + code + "-0001").orElseThrow();

        assertThat(motor.getSumInsured()).isEqualByComparingTo(new BigDecimal(motorSum));
        assertThat(property.getSumInsured()).isEqualByComparingTo(new BigDecimal(propertySum));
        assertThat(motor.getDeductible()).isEqualByComparingTo(new BigDecimal(deductible));
        assertThat(property.getDeductible()).isEqualByComparingTo(new BigDecimal(deductible));
    }

    @Test
    void activePoliciesAreValidFromOneYearAgoToOneYearAhead() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        assertThat(demoPolicies()).filteredOn(p -> p.getStatus() == PolicyStatus.ACTIVE).hasSize(12)
                .allSatisfy(policy -> {
                    // One day of tolerance in case the context was created just before midnight UTC.
                    assertThat(policy.getValidFrom()).isBetween(today.minusYears(1).minusDays(1),
                            today.minusYears(1).plusDays(1));
                    assertThat(policy.getValidTo()).isBetween(today.plusYears(1).minusDays(1),
                            today.plusYears(1).plusDays(1));
                });
    }

    @Test
    void theLapsedIndianMotorPolicyExistsAndExpiredAYearAgo() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        transactionTemplate.executeWithoutResult(status -> {
            Policy lapsed = policies.findByPolicyNumber("MOT-IN-0002").orElseThrow();

            assertThat(lapsed.getStatus()).isEqualTo(PolicyStatus.LAPSED);
            assertThat(lapsed.getProductType()).isEqualTo(ProductType.MOTOR);
            assertThat(lapsed.getHolder().getUserId()).isEqualTo("claimant.in");
            assertThat(lapsed.getMarket().getCode()).isEqualTo("IN");
            assertThat(lapsed.getValidFrom()).isBetween(today.minusYears(2).minusDays(1),
                    today.minusYears(2).plusDays(1));
            assertThat(lapsed.getValidTo()).isBetween(today.minusYears(1).minusDays(1),
                    today.minusYears(1).plusDays(1));
            assertThat(lapsed.getValidTo()).isBefore(today);
        });
    }

    @Test
    void runningTheSeederAgainChangesNothing() {
        Map<String, String> usersBefore = demoUsers().stream()
                .collect(Collectors.toMap(AppUser::getUserId, u -> u.getId() + ":" + u.getPasswordHash()));
        Map<String, Long> policiesBefore = demoPolicies().stream()
                .collect(Collectors.toMap(Policy::getPolicyNumber, Policy::getId));
        long totalUsersBefore = users.count();
        long totalPoliciesBefore = policies.count();

        initializer.run(new DefaultApplicationArguments());

        assertThat(users.count()).isEqualTo(totalUsersBefore);
        assertThat(policies.count()).isEqualTo(totalPoliciesBefore);
        // Same ids and the same stored hash: nothing was re-inserted and no password was re-encoded.
        assertThat(demoUsers().stream()
                .collect(Collectors.toMap(AppUser::getUserId, u -> u.getId() + ":" + u.getPasswordHash())))
                .isEqualTo(usersBefore);
        assertThat(demoPolicies().stream()
                .collect(Collectors.toMap(Policy::getPolicyNumber, Policy::getId))).isEqualTo(policiesBefore);
    }

    @Test
    void theSeederDoesNotOverwriteAnAccountThatAlreadyExists() {
        AppUser officer = users.findByUserId("officer.in").orElseThrow();
        String hashBefore = officer.getPasswordHash();
        officer.setFullName("Renamed By A Test");
        users.saveAndFlush(officer);
        try {
            initializer.run(new DefaultApplicationArguments());

            AppUser after = users.findByUserId("officer.in").orElseThrow();
            assertThat(after.getFullName()).isEqualTo("Renamed By A Test");
            assertThat(after.getPasswordHash()).isEqualTo(hashBefore);
        } finally {
            officer.setFullName("Officer India");
            users.saveAndFlush(officer);
        }
    }

    @Test
    void noSampleClaimsAreCreatedByThisPrompt() {
        assertThat(counters.findAll()).allSatisfy(counter -> assertThat(counter.getNextValue()).isEqualTo(10001L));
    }
}
