# ADR 0012: Test strategy: H2 in PostgreSQL mode, so Docker is not needed to build or test

- **Status:** Accepted
- **Date:** 2026-10-25

## Context

The build and every test must run on a machine without Docker. Testcontainers is excluded by the project constraints and would need Docker anyway. Reviewers should be able to run `mvnw -B clean verify` with only JDK 21 installed. The tests still need a real relational database to check constraints, Flyway migrations and queries.

## Decision

- All tests run on **H2 in PostgreSQL mode** with the profile `test`: `jdbc:h2:mem:claims_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1`, with the **same Flyway migrations** and `ddl-auto: validate`.
- Migrations use portable SQL only ([ADR 0003](0003-postgresql-flyway-hibernate-validate.md)), which is what makes this possible.
- Test levels: unit tests, repository tests (`@DataJpaTest`), integration tests (`@SpringBootTest` with MockMvc) and ArchUnit architecture tests. See [06-test-strategy.md](../06-test-strategy.md).
- Tests do not use `@Transactional`; they create unique data per test and match claim numbers with regular expressions.
- Quality gates: the build is green, line coverage is at least 75% (JaCoCo `check`), and there are at least 80 test methods.
- Application runs against real PostgreSQL 17 are done by hand during the final verification and through the smoke script.

## Alternatives considered

| Alternative | Why not |
|---|---|
| Testcontainers with PostgreSQL | Excluded by the constraints; needs Docker, which is not installed |
| An embedded PostgreSQL distribution | Downloads a native binary at test time and is fragile on locked-down machines |
| Tests against the developer's local PostgreSQL | Needs setup before every run and shares state between runs and between people |
| Mocking repositories only | Would not check mappings, constraints or migrations |

## Consequences

**Good**
- Tests are fast, self-contained and run anywhere JDK 21 is available.
- The same migrations are exercised by tests and by the real database.
- Constraints and queries are tested against an actual SQL engine.

**Bad**
- H2 is not PostgreSQL. Differences, for example in locking behaviour or in how some SQL is parsed, are not caught by the tests. The portable-SQL rule reduces the risk and the final verification on PostgreSQL 17 covers the rest, but there is no automated PostgreSQL test.
- Concurrency behaviour such as the pessimistic lock on claim counters is verified on H2 only.
- The Docker files cannot be tested at all on this machine.
