package com.apac.claims.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Common setup for repository tests: a JPA slice on H2 in PostgreSQL mode with the real Flyway migrations.
 * The database has its own name, so rows committed by other test classes (which use {@code claims_test}) can
 * never leak in, and the reference data stays exactly as migration V6 left it. {@code @DataJpaTest} rolls each
 * test back, which is the only reason these tests are transactional.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@DataJpaTest(properties = "spring.datasource.url=jdbc:h2:mem:claims_repo_test;MODE=PostgreSQL;"
        + "DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
public @interface RepositoryTest {
}
