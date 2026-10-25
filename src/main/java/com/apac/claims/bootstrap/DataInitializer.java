package com.apac.claims.bootstrap;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.apac.claims.identity.domain.AppUser;
import com.apac.claims.identity.domain.Role;
import com.apac.claims.identity.domain.UserRepository;
import com.apac.claims.market.domain.Market;
import com.apac.claims.market.domain.MarketRepository;
import com.apac.claims.policy.domain.Policy;
import com.apac.claims.policy.domain.PolicyRepository;
import com.apac.claims.policy.domain.PolicyStatus;
import com.apac.claims.policy.domain.ProductType;
import com.apac.claims.shared.money.MoneyRules;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Seeds the demo accounts and policies at startup when {@code app.seed.demo-data} is true. It is idempotent: it
 * inserts only what is missing (users by user id, policies by policy number) and never updates or deletes, so
 * it is safe on every start. Markets come from migration V6, not from here. Sample claims are seeded later.
 */
@Component
@Order(1)
@ConditionalOnProperty(name = "app.seed.demo-data", havingValue = "true")
public class DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private static final String EMAIL_DOMAIN = "claims-demo.example";
    private static final String MANAGER_PASSWORD = "Manager@123";
    private static final String OFFICER_PASSWORD = "Officer@123";
    private static final String CLAIMANT_PASSWORD = "Claimant@123";
    private static final BigDecimal MOTOR_SUM_INSURED_FACTOR = BigDecimal.valueOf(3);
    private static final BigDecimal PROPERTY_SUM_INSURED_FACTOR = BigDecimal.valueOf(10);
    private static final BigDecimal DEDUCTIBLE_PERCENT = BigDecimal.ONE;

    /** Market codes in the order the markets are seeded; the demo users and policies follow the same order. */
    private static final List<String> MARKET_CODES = List.of("IN", "SG", "AU", "JP", "MY", "TH");

    /** Plausible vehicle registrations per market, ASCII only so no console or file encoding matters. */
    private static final Map<String, String> VEHICLE_REGISTRATIONS = Map.of(
            "IN", "MH12AB1234",
            "SG", "SJK1234A",
            "AU", "AB12CD",
            "JP", "Shinagawa 330 A 12-34",
            "MY", "WXY 1234",
            "TH", "1KK 1234");

    /** Plausible property addresses per market. */
    private static final Map<String, String> PROPERTY_ADDRESSES = Map.of(
            "IN", "Flat 402, Lakeview Apartments, Powai, Mumbai 400076",
            "SG", "12 Orchard Lane #05-08, Singapore 238840",
            "AU", "27 Banksia Street, Parramatta NSW 2150",
            "JP", "3-14-7 Minami-Aoyama, Minato-ku, Tokyo 107-0062",
            "MY", "No. 18, Jalan Bukit Bintang, 55100 Kuala Lumpur",
            "TH", "88/12 Sukhumvit Soi 24, Khlong Tan, Bangkok 10110");

    private final MarketRepository marketRepository;
    private final UserRepository userRepository;
    private final PolicyRepository policyRepository;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate transactionTemplate;

    public DataInitializer(MarketRepository marketRepository, UserRepository userRepository,
                           PolicyRepository policyRepository, PasswordEncoder passwordEncoder,
                           TransactionTemplate transactionTemplate) {
        this.marketRepository = marketRepository;
        this.userRepository = userRepository;
        this.policyRepository = policyRepository;
        this.passwordEncoder = passwordEncoder;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        Counts counts = transactionTemplate.execute(status -> seed());
        log.info("Demo data ready: markets={}, users={}, policies={} (inserted users={}, policies={})",
                counts.markets, counts.users, counts.policies, counts.insertedUsers, counts.insertedPolicies);
    }

    private Counts seed() {
        Counts counts = new Counts();
        Map<String, Market> markets = loadMarkets();
        counts.markets = marketRepository.count();

        ensureUser("manager.apac", "APAC Manager", Role.MANAGER, null, MANAGER_PASSWORD, counts);
        for (String code : MARKET_CODES) {
            ensureUser("officer." + code.toLowerCase(), "Officer " + markets.get(code).getCountryName(),
                    Role.OFFICER, markets.get(code), OFFICER_PASSWORD, counts);
        }
        Map<String, AppUser> claimants = new LinkedHashMap<>();
        for (String code : MARKET_CODES) {
            claimants.put(code, ensureUser("claimant." + code.toLowerCase(),
                    "Claimant " + markets.get(code).getCountryName(), Role.CLAIMANT, markets.get(code),
                    CLAIMANT_PASSWORD, counts));
        }

        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (String code : MARKET_CODES) {
            seedPoliciesFor(code, markets.get(code), claimants.get(code), today, counts);
        }
        seedLapsedPolicy(markets.get("IN"), claimants.get("IN"), today, counts);
        return counts;
    }

    private Map<String, Market> loadMarkets() {
        Map<String, Market> markets = new LinkedHashMap<>();
        for (String code : MARKET_CODES) {
            Market market = marketRepository.findByCode(code).orElseThrow(() -> new IllegalStateException(
                    "Market " + code + " is missing; the reference data migration V6 has not been applied"));
            markets.put(code, market);
        }
        return markets;
    }

    private AppUser ensureUser(String userId, String fullName, Role role, Market market, String rawPassword,
                               Counts counts) {
        counts.users++;
        return userRepository.findByUserId(userId).orElseGet(() -> {
            counts.insertedUsers++;
            // The password is hashed here and never logged.
            return userRepository.save(new AppUser(userId, passwordEncoder.encode(rawPassword), fullName,
                    userId + "@" + EMAIL_DOMAIN, role, market));
        });
    }

    private void seedPoliciesFor(String code, Market market, AppUser claimant, LocalDate today, Counts counts) {
        String currency = market.getCurrencyCode();
        BigDecimal threshold = market.getEscalationThreshold();
        BigDecimal deductible = MoneyRules.percentOf(threshold, DEDUCTIBLE_PERCENT, currency);
        BigDecimal motorSum = MoneyRules.normalize(threshold.multiply(MOTOR_SUM_INSURED_FACTOR), currency);
        BigDecimal propertySum = MoneyRules.normalize(threshold.multiply(PROPERTY_SUM_INSURED_FACTOR), currency);

        ensurePolicy("MOT-" + code + "-0001", market, ProductType.MOTOR, claimant, VEHICLE_REGISTRATIONS.get(code),
                motorSum, deductible, today.minusYears(1), today.plusYears(1), PolicyStatus.ACTIVE, counts);
        ensurePolicy("PRP-" + code + "-0001", market, ProductType.PROPERTY, claimant, PROPERTY_ADDRESSES.get(code),
                propertySum, deductible, today.minusYears(1), today.plusYears(1), PolicyStatus.ACTIVE, counts);
    }

    /** A second Indian motor policy that expired a year ago, so the "policy not active" path can be demonstrated. */
    private void seedLapsedPolicy(Market india, AppUser claimant, LocalDate today, Counts counts) {
        String currency = india.getCurrencyCode();
        BigDecimal threshold = india.getEscalationThreshold();
        ensurePolicy("MOT-IN-0002", india, ProductType.MOTOR, claimant, "KA05MN7788",
                MoneyRules.normalize(threshold.multiply(MOTOR_SUM_INSURED_FACTOR), currency),
                MoneyRules.percentOf(threshold, DEDUCTIBLE_PERCENT, currency),
                today.minusYears(2), today.minusYears(1), PolicyStatus.LAPSED, counts);
    }

    private void ensurePolicy(String policyNumber, Market market, ProductType productType, AppUser holder,
                              String insuredItem, BigDecimal sumInsured, BigDecimal deductible, LocalDate validFrom,
                              LocalDate validTo, PolicyStatus status, Counts counts) {
        counts.policies++;
        if (policyRepository.findByPolicyNumber(policyNumber).isEmpty()) {
            counts.insertedPolicies++;
            policyRepository.save(new Policy(policyNumber, market, productType, holder, insuredItem, sumInsured,
                    deductible, validFrom, validTo, status));
        }
    }

    /** Mutable tally for one run, only used to build the summary line. */
    private static final class Counts {
        long markets;
        int users;
        int policies;
        int insertedUsers;
        int insertedPolicies;
    }
}
