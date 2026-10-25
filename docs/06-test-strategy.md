# 06. Test Strategy

Status: **Implemented.** The tests of every prompt exist and run in `mvnw -B clean verify`; [requirements-traceability.md](requirements-traceability.md) names the test classes per requirement.
Date: 2026-10-25. Source of truth: [PROJECT_SPEC.md](PROJECT_SPEC.md) section 13.

## 1. Principles

- Every test runs on **H2 in PostgreSQL mode** (profile `test`) with no Docker and no running PostgreSQL ([ADR 0012](adr/0012-test-strategy-h2-postgresql-mode.md)). The same Flyway migrations run against H2.
- No `@Transactional` on tests: the code under test controls its own transactions, and an enclosing test transaction would hide commit-time behaviour such as the outbox and optimistic locking. The one exception is the repository tests: `@DataJpaTest` rolls each test back, which keeps them independent and lets them read lazy associations. They use their own in-memory database (`claims_repo_test`, same H2 settings), so the reference data stays exactly as migration V6 left it even when other test classes commit rows to `claims_test`. Tests that must see committed behaviour use `@SpringBootTest` and an explicit `TransactionTemplate` where they need a transaction.
- Each test creates unique data, so tests do not depend on order. Claim numbers are matched with regular expressions (`^CLM-\d{5,}$`, `^CLP-\d{5,}$`), never hard-coded.
- Tests log in through the real login endpoint to obtain tokens, so the security chain is exercised. API tests extend `AbstractApiTest` (full application on H2 with MockMvc, helpers for login, bearer headers, unique user ids and hand-made tokens) and share one Spring context.
- Test-only controllers that throw each exception type live in `src/test/java`, are `@Hidden` from OpenAPI and are active only under the profile `error-contract-test`, so they can neither ship nor leak into another test.
- A test is only worth keeping if it fails when the protection is removed. For two of the protections that matter most the main code was broken on purpose and the tests were run: disabling the explicit `AccessDeniedException` handler failed seven tests (403 became 500), and disabling the per-request account check failed three. The code was then restored.
- A check is never weakened or deleted to make a build pass. If an architecture rule cannot be met cheaply, a narrow exception is documented in an ADR.

## 2. Test pyramid

```mermaid
graph TD
    arch["Architecture tests: ArchUnit rules on the whole code base"]
    integ["Integration tests: SpringBootTest with MockMvc, full journeys through the API"]
    repo["Repository tests: DataJpaTest on H2 with the real Flyway migrations"]
    unit["Unit tests: pure classes and Mockito-based services, the broad base"]
    unit --> repo --> integ
    arch --- integ
```

| Level | Scope | What it proves |
|---|---|---|
| Unit | `ClaimWorkflow` (every allowed and forbidden transition), `MoneyRules`, assessment payout calculation, SLA and aging calculations, event envelope serialisation, services with Mockito for ports (for example a `PaymentGateway` failure keeps the claim APPROVED) | Rules are correct in isolation and fast to run |
| Repository | `@DataJpaTest` with `@AutoConfigureTestDatabase(replace = NONE)` and profile `test`: constraints, queries, and that the Flyway migrations validate against the entities | Schema and mapping agree; unique, check and foreign-key constraints work |
| Integration | `@SpringBootTest` with `@AutoConfigureMockMvc`: journeys through the API | The modules work together, including security and the error contract |
| Architecture | ArchUnit | The module and layer rules in the design hold, and keep holding |
| Smoke (outside Maven) | `scripts/SmokeTest.java` against a running instance | The packaged application works end to end, locally and in Docker Compose |

### Integration test topics

End-to-end journeys; security (401, 403, 404); validation and the error contract (including `AccessDeniedException` returning 403, not 500); idempotency key; pick-up race (the second pick-up gets 409); information request round trip; escalation and manager decision; settlement and gateway failure; outbox relay and idempotent notification delivery (the relay's one-batch method is called directly); reporting scope and numbers; OpenAPI content (`bearerAuth`, login `role` enum with three values, `/api/v1/claims/queue`); the literal route `/claims/queue` winning over `/claims/{claimNumber}`; and the contract test comparing the committed `src/main/resources/openapi/claims-api.yaml` with the live `/v3/api-docs` (same paths and methods in both directions).

### Architecture rules

- Layer rules inside modules (`LayerRulesTest`), with the three named exceptions of [ADR 0014](adr/0014-api-records-and-kernel-api-in-the-layer-rules.md): a service may use the records of its own `api.dto`, every module may use the kernel `shared.api`, and `bootstrap` may use `claim.api.dto`.
- Modules are free of cycles, and every module depends only on what the table of the specification allows (`ModuleDependencyRulesTest`, table-driven).
- No module depends on another module's `api` or `infrastructure` package.
- `claim` does not depend on `policy`, `notification`, `reporting` or `integration`.
- `domain` does not depend on `org.springframework.web`, `jakarta.servlet`, or any `api`, `service` or `infrastructure` package.
- `security` depends on no feature module.
- No field injection; controllers are `@RestController` classes in `..api..` named `*Controller`.
- No standard streams, no `java.util.logging`, no generic exceptions (ArchUnit `GeneralCodingRules`).

## 3. Tools

| Tool | Use |
|---|---|
| JUnit 5 | Test engine (through `spring-boot-starter-test`) |
| Mockito | Mocks for ports in service unit tests |
| Spring Boot Test, MockMvc, `spring-security-test` | Integration tests through the real security chain |
| H2 in PostgreSQL mode | Database for all tests: `jdbc:h2:mem:claims_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1` |
| ArchUnit (`archunit-junit5` 1.3.0) | Architecture rules |
| JaCoCo 0.8.12 | Coverage report and the `check` rule that fails the build below 75% line coverage |

`@MockBean` is not used (it is deprecated in Spring Boot 3.4 and later); tests use `@MockitoBean` where a bean must be replaced.

## 4. Quality gates

| Gate | Threshold | Enforced by |
|---|---|---|
| Build and tests | `mvnw -B clean verify` is green on JDK 21 | Maven |
| Line coverage | At least 75% for the bundle, excluding DTO records, `config`, the main class and `package-info` | JaCoCo `check` rule in `pom.xml` (since prompt 5) |
| Test count | At least 80 test methods in total | Counted from the source: 913 test methods in 80 classes (878 JUnit methods and 35 ArchUnit rules) |
| Architecture | All ArchUnit rules pass | The ArchUnit test class, part of `verify` |
| Contract | Committed OpenAPI file and live API document agree | `OpenApiContractTest` |
| Working tree | `git status` is clean after each stage | Checked by hand at the end of each prompt |

## 5. How to run everything

Use JDK 21 (`JAVA_HOME` pointing to it) and the Maven Wrapper. In PowerShell or cmd:

```
.\mvnw.cmd -B clean verify
```

In Git Bash: `./mvnw -B clean verify`.

| Goal | Command |
|---|---|
| Whole build with tests and the coverage report | `.\mvnw.cmd -B clean verify` |
| Tests only | `.\mvnw.cmd -B test` |
| One test class | `.\mvnw.cmd -B test -Dtest=ClaimWorkflowTest` |
| Coverage report | open `target/site/jacoco/index.html` after `verify` |
| Smoke test against a running instance | `java scripts/SmokeTest.java http://localhost:8080` |

The smoke script prints PASS or FAIL per step and exits with 1 on any failure. It covers health, login for each role, a wrong role (401), claim submission and idempotent replay, pick-up, assessment, information request and response, escalation, approval limit (422), manager approval, settlement, notifications, reports, scope (404) and validation (400).

## 6. What is deliberately not tested, and why

| Not tested | Reason |
|---|---|
| The `Dockerfile` and `docker-compose.yml` by running them | Docker is not installed on the development machine. They are reviewed line by line, and the journal says so |
| Behaviour that exists only on real PostgreSQL (lock timeouts, `SKIP LOCKED`) | Tests use H2 in PostgreSQL mode. The final verification runs the application against the local PostgreSQL 17, but no automated test targets it |
| Multiple relay instances | One relay instance is a documented limit |
| Performance and load | Out of scope for the assessment; no performance requirement is stated |
| A real payment, policy or email system | These are simulated behind ports; the tests cover the ports and the simulated adapters |
| A frontend or the Swagger UI rendering | No frontend exists; the OpenAPI document is tested instead of the page |
| Generated or trivial code (DTO records, configuration records, `package-info`) | No logic to test; excluded from the coverage measure |
| Time-of-day dependent behaviour with the real clock | Time-sensitive code (SLA, aging, overdue) takes a `Clock` so tests control time |

## 7. Tests added by prompt

| Prompt | Tests added |
|---|---|
| 2 Data layer | Repository tests for constraints and queries, migration validation, application context test |
| 3 Security and identity | Security and error-contract integration tests, JWT tests, auth and user endpoints, OpenAPI configuration |
| 4 Claims core | `ClaimWorkflow`, payout, claim number and event unit tests; integration tests for submission and validation, visibility and scope, pick-up and assignment (with real-thread races), assessments, information requests, decisions, the outbox write side, policies and the OpenAPI document; unit tests for the mapper, scope, authority, timeline and the lost-race translation; the first ArchUnit rules |
| 5 Integration and reporting | Outbox relay tests (retry, backoff, `FAILED`, ordering), notification handler, service, channel and API tests, the simulated payment gateway and settlement including the gateway failure, the report calculators and the report API (scope and numbers), the sample-claims seeder, the complete ArchUnit rules (35), the JaCoCo gate, and the smoke script extension |
| 6 Packaging and finish | `OpenApiContractTest` (the committed contract against the live document, and a control that shows the comparison reports both sides); the smoke script run twice against a fresh PostgreSQL database |

## 8. Results

Recorded in prompt 6 with JDK 21. The build figures are those of the final `clean verify`; the smoke script and the log are from the run on a database that had just been dropped and created again:

| Check | Result |
|---|---|
| `mvnw -B clean verify` | BUILD SUCCESS in 4 min 4 s: 1398 tests, 0 failures, 0 errors, 0 skipped |
| JaCoCo line coverage | 95.58% (2379 of 2489 lines), gate 75%, "All coverage checks have been met" |
| ArchUnit | 35 rules, all passing (`ModuleDependencyRulesTest` 14, `LayerRulesTest` 15, `CodingRulesTest` 6) |
| Test classes | 80: 33 unit, 12 repository, 32 integration, 3 architecture |
| Smoke script | 165 of 165 steps passed in each of two consecutive runs against the packaged jar on PostgreSQL 17.6; the second run is saved in [smoke-test-output.txt](smoke-test-output.txt) |
| Application log of that run | 0 ERROR lines, no stack trace, no password, token or request body; 8 WARN lines, all of them the deliberate failed logins of the script (user id and requested role only) |

