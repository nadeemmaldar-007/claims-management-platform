package com.apac.claims.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.apac.claims.claim.domain.ClaimCounter;
import com.apac.claims.claim.domain.ClaimCounterRepository;
import com.apac.claims.claim.domain.ClaimType;
import com.apac.claims.market.domain.Market;
import com.apac.claims.market.domain.MarketRepository;
import com.apac.claims.support.RepositoryTest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves that Flyway applied all six migrations to a real SQL engine, that Hibernate validated the entities
 * against the result (the context would not start otherwise), and that the reference data is as specified.
 */
@RepositoryTest
class FlywayMigrationRepositoryTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MarketRepository markets;

    @Autowired
    private ClaimCounterRepository counters;

    @Autowired
    private Environment environment;

    @Test
    void allSixMigrationsWereAppliedSuccessfullyInOrder() {
        List<Map<String, Object>> history = jdbc.queryForList(
                "SELECT version, success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");

        assertThat(history).hasSize(6);
        assertThat(history).extracting(row -> String.valueOf(row.get("version")))
                .containsExactly("1", "2", "3", "4", "5", "6");
        assertThat(history).allSatisfy(row -> assertThat(row.get("success")).isEqualTo(true));
    }

    @Test
    void hibernateRunsInValidateModeSoTheSchemaIsOwnedByFlywayOnly() {
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    }

    @ParameterizedTest
    @ValueSource(strings = {"markets", "app_users", "policies", "claim_counters", "claims", "claim_assessments",
            "information_requests", "claim_timeline_entries", "idempotency_keys", "outbox_events",
            "payment_instructions", "notifications"})
    void everyTableOfTheSchemaExists(String table) {
        Integer found = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_name) = ?", Integer.class, table);

        assertThat(found).as("table %s", table).isEqualTo(1);
    }

    @Test
    void theSixMarketsAreSeededInTheSpecifiedOrder() {
        List<Market> all = markets.findAllByOrderByIdAsc();

        assertThat(all).extracting(Market::getCode).containsExactly("IN", "SG", "AU", "JP", "MY", "TH");
        assertThat(all).allSatisfy(market -> assertThat(market.isActive()).isTrue());
    }

    @ParameterizedTest
    @CsvSource({
            "IN, India, INR, 500000.00",
            "SG, Singapore, SGD, 10000.00",
            "AU, Australia, AUD, 10000.00",
            "JP, Japan, JPY, 1000000.00",
            "MY, Malaysia, MYR, 25000.00",
            "TH, Thailand, THB, 200000.00"
    })
    void eachMarketHasTheSpecifiedCurrencyAndEscalationThreshold(String code, String country, String currency,
                                                                 String threshold) {
        Market market = markets.findByCode(code).orElseThrow();

        assertThat(market.getCountryName()).isEqualTo(country);
        assertThat(market.getCurrencyCode()).isEqualTo(currency);
        assertThat(market.getEscalationThreshold()).isEqualByComparingTo(new BigDecimal(threshold));
    }

    @Test
    void bothClaimCountersStartAt10001() {
        for (ClaimType type : ClaimType.values()) {
            ClaimCounter counter = counters.findById(type).orElseThrow();

            assertThat(counter.getNextValue()).as("counter %s", type).isEqualTo(10001L);
        }
        assertThat(counters.count()).isEqualTo(2);
    }
}
