package com.apac.claims;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;

import com.apac.claims.bootstrap.DataInitializer;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/** The full application context starts on H2 with Flyway, Hibernate validation and the demo-data seeder. */
@SpringBootTest
@ActiveProfiles("test")
class ClaimsManagementPlatformApplicationTests {

    @Autowired
    private DataSource dataSource;
    @Autowired
    private Flyway flyway;
    @Autowired
    private Environment environment;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired(required = false)
    private DataInitializer dataInitializer;

    @Test
    void contextLoadsOnH2InPostgresqlMode() throws Exception {
        try (var connection = dataSource.getConnection()) {
            // The JDBC metadata URL drops the settings, so ask H2 itself which mode it runs in.
            assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:claims_test");
        }
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(h2Setting(jdbc, "MODE")).isEqualTo("PostgreSQL");
        assertThat(h2Setting(jdbc, "DATABASE_TO_LOWER")).isEqualToIgnoringCase("true");
    }

    private static String h2Setting(JdbcTemplate jdbc, String name) {
        return jdbc.queryForObject("SELECT setting_value FROM information_schema.settings WHERE setting_name = ?",
                String.class, name);
    }

    @Test
    void flywayAppliedAllSixMigrations() {
        assertThat(flyway.info().applied()).hasSize(6);
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    void hibernateValidatesTheSchemaInsteadOfCreatingIt() {
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    }

    @Test
    void theTestProfileDisablesTheRelayAndSampleClaimsButKeepsDemoData() {
        assertThat(environment.getProperty("app.outbox.relay.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("app.seed.sample-claims")).isEqualTo("false");
        assertThat(environment.getProperty("app.seed.demo-data")).isEqualTo("true");
        assertThat(dataInitializer).isNotNull();
    }

    @Test
    void passwordsAreEncodedWithBcrypt() {
        assertThat(passwordEncoder).isInstanceOf(BCryptPasswordEncoder.class);
    }
}
