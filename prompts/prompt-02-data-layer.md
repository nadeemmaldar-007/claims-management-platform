# PROMPT 2 OF 7: DATA LAYER (SHARED KERNEL, CONFIGURATION, FLYWAY MIGRATIONS, ENTITIES, SEED DATA, TESTS)

You are the same senior Java / Spring Boot engineer continuing the **claims-management-platform** project inside IntelliJ IDEA on Windows 11. Before doing anything else, read `docs/PROJECT_SPEC.md` (the single source of truth), `.claude/ai-journal.md` (what has been done), and `docs/02-architecture.md`. Then carry out this prompt. In this prompt you build the **data layer only**: no controllers, no security rules, no business services. Controllers and services come in prompts 3 to 6.

Working rules (unchanged from prompt 1):
- Keep a task list. Work through the stages in order without waiting for approval between stages. Ask the developer only in the situations in section 9.
- Never claim something works unless you ran it and saw the real output. Never weaken, skip or delete a test to make a build pass.
- Use ONLY the Maven Wrapper (`.\mvnw.cmd` in PowerShell or cmd, `./mvnw` in Git Bash) with `-B`, and always with `JAVA_HOME` set to the JDK 21 folder recorded in `.claude/ai-journal.md` (set it in every terminal session and check `java -version` prints 21 first). The system `java` is JDK 25: never use it.
- Environment unchanged: Docker is not installed (never run `docker`); Kafka is not used; never add a remote or push; never change global git config; PostgreSQL 17 at `localhost:5432`, user `postgres`, password `postgres`, database `claims_db` (created by the developer in pgAdmin).
- Stack unchanged: Java 21, Spring Boot 3.5.6, no Lombok, DTOs as records, entities as plain classes with explicit getters and setters and a protected no-argument constructor.

---

## 1. STAGE 0: SANITY CHECK OF THE STATE LEFT BY PROMPT 1 (no new feature code)

1. Confirm that `prompts/prompt-02-data-layer.md` exists. If it does not (this prompt was pasted into the chat), save the text of this prompt there verbatim.
2. Run `git status` and `git log --oneline`. Expected: a clean tree and four commits from prompt 1 (initialise repository and prompt log; specification and AI journal; design documents; scaffold). If there are uncommitted leftovers from prompt 1, review them and commit them with an accurate message first. If something from prompt 1 is missing (for example `NOTES.md`, `docs/adr/`, the `.mvn/wrapper` files, the `pom.xml` dependencies listed in spec section 11), create it now exactly as prompt 1 specified and mention it in the journal.
3. Run `mvnw -B clean verify` with JDK 21. It must end in BUILD SUCCESS before you add anything. If not, fix the cause first.
4. Make sure `prompts/README.md` exists (create it if not): an ordered list of the prompt files with one line each. Add the line for prompt 2.

## 2. STAGE 1: SHARED KERNEL (commit)

Package `com.apac.claims.shared` (module rules in spec section 3). Write these classes with Javadoc:

- `shared.exception.ErrorCode`: an enum with exactly the codes in spec section 7 (`VALIDATION_FAILED`, `MALFORMED_REQUEST`, `UNAUTHENTICATED`, `ACCESS_DENIED`, `RESOURCE_NOT_FOUND`, `DUPLICATE_RESOURCE`, `CLAIM_INVALID_TRANSITION`, `CLAIM_ALREADY_ASSIGNED`, `CONCURRENT_MODIFICATION`, `DUPLICATE_CLAIM`, `POLICY_NOT_FOUND`, `POLICY_NOT_ACTIVE`, `POLICY_TYPE_MISMATCH`, `POLICY_MARKET_MISMATCH`, `AMOUNT_EXCEEDS_SUM_INSURED`, `ASSESSMENT_REQUIRED`, `APPROVAL_LIMIT_EXCEEDED`, `INFORMATION_REQUEST_NOT_OPEN`, `PAYMENT_GATEWAY_UNAVAILABLE`, `INTERNAL_ERROR`).
- `shared.exception.ApiException` (abstract, extends `RuntimeException`): carries an `ErrorCode` and an `org.springframework.http.HttpStatus`. Concrete subclasses in the same package: `ResourceNotFoundException` (404, `RESOURCE_NOT_FOUND`), `DuplicateResourceException` (409, `DUPLICATE_RESOURCE`), `ConflictException(ErrorCode, message)` (409), `BusinessRuleException(ErrorCode, message)` (422), `ValidationFailedException` (400, `VALIDATION_FAILED`, carries a list of `FieldError(field, message)` records), `UpstreamServiceException(ErrorCode, message)` (502). All business exceptions in every later module extend `ApiException`, so the one `GlobalExceptionHandler` written in prompt 3 never has to know feature modules.
- `shared.api.PageResponse<T>`: a record `(List<T> content, int page, int size, long totalElements, int totalPages, boolean last)` with a static `from(org.springframework.data.domain.Page<T>)` and a static `of(Page<S>, Function<S,T>)` mapper. Never serialize `PageImpl` directly.
- `shared.money.MoneyRules`: `int scaleFor(String currencyCode)` using `java.util.Currency.getInstance(code).getDefaultFractionDigits()`; `BigDecimal normalize(BigDecimal amount, String currencyCode)` (set scale with `RoundingMode.HALF_UP`); `void requireValidScale(BigDecimal amount, String currencyCode, String fieldName)` that throws `ValidationFailedException` when the amount has more decimal places than the currency allows (for example 10.5 JPY); `BigDecimal percentOf(BigDecimal base, BigDecimal percent, String currencyCode)`. Reject null, zero-or-negative checks stay in the callers. An unknown currency code throws `IllegalArgumentException` with a clear message.
- `shared.event`: `DomainEvent` as a record `(String eventId, String eventType, String aggregateType, String aggregateId, Instant occurredAt, int version, Map<String, Object> payload)` with a static factory `DomainEvent.of(eventType, aggregateType, aggregateId, payload)` that generates a UUID `eventId`, `Instant.now()` and `version` 1 (use an injectable `Clock` where it matters); `DomainEventPublisher` (`void publish(DomainEvent event)`); `DomainEventHandler` (`void handle(DomainEvent event)`).

Do NOT write the global exception handler or the correlation id filter yet (prompt 3).

Verify with `mvnw -B clean compile`. Commit: `feat(shared): add error codes, api exceptions, money rules, event contracts and page response`.

## 3. STAGE 2: TYPED CONFIGURATION AND PASSWORD ENCODER (commit)

1. In `config`, create `@ConfigurationProperties` records bound to the `app.*` keys in `application.yml` (spec section 12), annotated `@Validated`: `AppProperties` (or one record per group: `JwtProperties` for `app.security.jwt`, `SeedProperties` for `app.seed`, `SlaProperties` for `app.sla`, `ReportingProperties` for `app.reporting` including `Map<String, BigDecimal> rates`, `OutboxProperties` for `app.outbox` with a nested `relay` record, `PaymentProperties` for `app.integration.payment`, `NotificationProperties` for `app.notification`). Use sensible validation (`@NotBlank`, `@Min`, `@NotEmpty`). Registration is by the existing `@ConfigurationPropertiesScan`.
2. In `security`, create only `PasswordEncoderConfig`: a `@Configuration` exposing a `PasswordEncoder` bean (`BCryptPasswordEncoder`). The rest of security is prompt 3. (Until then Spring Boot's default security applies and may log a generated password; this is expected and is replaced in prompt 3.)

Verify with `mvnw -B clean compile`. Commit: `feat(config): add typed configuration properties and password encoder`.

## 4. STAGE 3: FLYWAY MIGRATIONS (commit after the tests in stage 6 pass; keep the work in the tree until then)

Write the six migrations in `src/main/resources/db/migration` exactly as spec section 4 lists them: `V1__markets_and_identity.sql`, `V2__policies.sql`, `V3__claims.sql`, `V4__integration.sql`, `V5__notifications.sql`, `V6__reference_data.sql`. Remove `.gitkeep` from that folder.

Rules (the SQL must run unchanged on PostgreSQL 17 and on H2 in PostgreSQL mode):
- Keywords in upper case, identifiers in `snake_case`, every table and constraint has an explicit name: `pk_<table>`, `fk_<table>_<column>`, `uq_<table>_<column>`, `ck_<table>_<column>`, `ix_<table>_<columns>`.
- Types only from this list: `BIGINT GENERATED BY DEFAULT AS IDENTITY` for ids, `BIGINT`, `INTEGER`, `VARCHAR(n)`, `NUMERIC(19,2)`, `BOOLEAN`, `DATE`, `TIMESTAMP WITH TIME ZONE`. No `TEXT`, `JSONB`, `CHAR`, `UUID`, sequences, partial indexes, `NOW()` defaults or any vendor function. Timestamps have no database default (the application sets them).
- `NOT NULL` on every column except those the spec marks nullable (motor columns, property columns, decision columns, settlement columns, `assigned_officer_id`, `assigned_at`, `approved_amount`, `market_id` for managers, `police_report_number`, `from_status`, `to_status`, `processed_at`, `last_error`, `read_at`, response columns of information requests, and similar optional fields).
- Enum columns are `VARCHAR` with a `CHECK (col IN (...))` listing exactly the Java enum constants (create the Java enums in stage 4 with the same spellings). Suggested lengths: statuses 30, `entry_type` 40, reason codes 40, other enums 20.
- Suggested lengths: `code` 2, `currency_code` 3, `country_name` 60, `user_id` 30, `password_hash` 100, `full_name` 100, `email` 150, `policy_number` 30, `claim_number` 20, `insured_item` 200, `incident_location` 200, `description` 1000, `property_address` 300, `vehicle_*` 50 (registration 20), `message` 1000, `question` 1000, `response_text` 2000, `notes` 2000, `payload` 8000, `last_error` 1000, `idempotency_key` 64, ids that are UUID strings 36.
- `version BIGINT NOT NULL DEFAULT 0` on `claims`.
- Foreign keys exactly as in the spec: feature tables reference `app_users`, `markets` and their own parents. No foreign key from `claims.policy_number` to `policies` (modules own their tables; the spec's data-ownership rule). `claim_assessments`, `information_requests` and `claim_timeline_entries` reference `claims(id)`.
- Indexes and unique constraints exactly as in the spec (including the check `role = 'MANAGER' OR market_id IS NOT NULL`, the unique `(user_id, idempotency_key)`, the unique `(source_event_id, recipient_id)`, and the index `(status, next_attempt_at)`).
- `V6__reference_data.sql`: insert the six markets in the order and with the values of spec section 5, and the two `claim_counters` rows (`MOTOR`, `PROPERTY`) with `next_value = 10001`. Plain `INSERT` statements.
- Never edit a migration after it has been applied to the developer's `claims_db`; if a mistake is found later, add a new migration, except that during this prompt (before the first real PostgreSQL run in stage 7) you may still correct V1 to V6 freely. If a migration was already applied to `claims_db` and needs correcting, reset the schema (`DROP SCHEMA public CASCADE; CREATE SCHEMA public;`) in `claims_db` and tell the developer in the report.

## 5. STAGE 4: ENTITIES, ENUMS AND REPOSITORIES (commit after stage 6)

Create the domain classes in the `domain` package of each module, with Javadoc, matching the migrations column for column (spec section 4). Mapping rules: `@Entity` and `@Table(name = ...)` explicit; `@Column(name = ..., nullable = ..., length/precision/scale = ...)` explicit; `@Enumerated(EnumType.STRING)`; `@GeneratedValue(strategy = GenerationType.IDENTITY)` for identity ids; `Instant` for timestamps, `LocalDate` for dates, `BigDecimal` for money (`precision = 19, scale = 2`); all associations `@ManyToOne(fetch = FetchType.LAZY)`; `@Version Long version` on `Claim`; `@PrePersist` and `@PreUpdate` set `createdAt` and `updatedAt`; protected no-arg constructor plus a public constructor for required fields; explicit getters and setters; no Lombok; `equals` and `hashCode` left as the default (identity) unless you can justify a business key.

| Module | Classes (package `com.apac.claims.<module>.domain`) |
|---|---|
| market | `Market`, `MarketRepository` (`Optional<Market> findByCode(String)`, `existsByCode`) |
| identity | `AppUser`, `Role` (CLAIMANT, OFFICER, MANAGER), `UserRepository` (`findByUserId`, `existsByUserId`, `existsByEmail`, `findByRoleAndMarketAndActiveTrue`, `findByRoleAndActiveTrue`, a paged `findAll` with a `Specification` capability by extending `JpaSpecificationExecutor`) |
| policy | `Policy`, `ProductType` (MOTOR, PROPERTY), `PolicyStatus` (ACTIVE, LAPSED, CANCELLED), `PolicyRepository` (`findByPolicyNumber`, `findByHolder`) |
| claim | `Claim`, `ClaimType`, `ClaimStatus` (with `boolean isOpen()` and `isFinal()`), `DamageType`, `PropertyType`, `PerilType`, `DecisionType`, `RejectionReason`, `PaymentMethod`, `ClaimAssessment`, `AssessmentRecommendation`, `InformationRequest`, `InformationRequestStatus`, `ClaimTimelineEntry`, `TimelineEntryType`, `TimelineVisibility`, `IdempotencyKey`, `ClaimCounter`, and repositories `ClaimRepository` (extends `JpaSpecificationExecutor<Claim>`; `findByClaimNumber`; `existsByPolicyNumberAndIncidentDateAndClaimTypeAndStatusNot`), `ClaimAssessmentRepository` (latest assessment for a claim), `InformationRequestRepository`, `ClaimTimelineRepository`, `IdempotencyKeyRepository`, `ClaimCounterRepository` |
| integration | `OutboxEvent` (implements `Persistable<String>` because its id is assigned by the application; use a `@Transient` new flag so `save` does an insert and not a merge), `OutboxStatus` (PENDING, PROCESSED, FAILED), `PaymentInstruction`, `PaymentStatus` (INITIATED, COMPLETED, FAILED), repositories `OutboxEventRepository` (find the PENDING events whose `nextAttemptAt` has passed, ordered by `occurredAt`, limited by a `Pageable`), `PaymentInstructionRepository` (`findByClaimNumber`) |
| notification | `Notification`, `NotificationType` (CLAIM_SUBMITTED, NEW_CLAIM_IN_QUEUE, CLAIM_ASSIGNED, INFORMATION_REQUESTED, INFORMATION_PROVIDED, CLAIM_ESCALATED, CLAIM_APPROVED, CLAIM_REJECTED, CLAIM_SETTLED), `NotificationRepository` (paged by recipient with an unread filter, `countByRecipientAndReadFlagFalse`, `existsBySourceEventIdAndRecipient`) |

`ClaimCounterRepository` must contain the pessimistic lock query used by the claim number generator in prompt 4:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select c from ClaimCounter c where c.claimType = :type")
Optional<ClaimCounter> findForUpdate(@Param("type") ClaimType type);
```

Do NOT write JPQL with optional parameters of the form `(:x is null or c.field = :x)`; on PostgreSQL it fails with "could not determine data type of parameter". Use `Specification` objects (later) or separate methods.

Where a column is optional, use a nullable Java type (`Integer`, `Instant`, `BigDecimal`). Keep the same enum spellings as in the CHECK constraints.

## 6. STAGE 5: DEMO DATA INITIALIZER (commit)

Create `bootstrap.DataInitializer` (module `bootstrap`, may use every module). It is an `ApplicationRunner` annotated `@Order(1)` and `@ConditionalOnProperty(name = "app.seed.demo-data", havingValue = "true")`, uses a `TransactionTemplate`, and is **idempotent**: it inserts only what is missing (users by `user_id`, policies by `policy_number`) and never updates or deletes existing rows. It seeds exactly what spec section 10 lists for users and policies:

- Users: `manager.apac` / `Manager@123` (no market); `officer.in|sg|au|jp|my|th` / `Officer@123`; `claimant.in|sg|au|jp|my|th` / `Claimant@123`. Full names like "Claimant India", "Officer Singapore", "APAC Manager". Email `<userId>@claims-demo.example`. Passwords hashed with the `PasswordEncoder` bean. 13 accounts.
- Policies, per claimant: motor `MOT-<MKT>-0001` and property `PRP-<MKT>-0001`; sum insured motor = 3 x the market's escalation threshold and property = 10 x the threshold; deductible = 1% of the threshold using `MoneyRules.percentOf`; `valid_from` = seeding date minus one year, `valid_to` = seeding date plus one year; status ACTIVE; realistic `insured_item` text (a plausible vehicle registration or property address for the market). One extra policy `MOT-IN-0002` for `claimant.in` with status LAPSED, valid from two years ago to one year ago. 13 policies.
- It does NOT create sample claims (that comes in prompt 6).
- It logs one INFO summary line: `Demo data ready: markets=6, users=13, policies=13 (inserted users=N, policies=M)`. Never log passwords.

Verify with `mvnw -B clean compile`. Commit: `feat(bootstrap): add idempotent demo data initializer`.

## 7. STAGE 6: TESTS (H2 in PostgreSQL mode, profile `test`, no Docker)

Create tests under `src/test/java/com/apac/claims/...`. Follow spec section 13: no `@Transactional` on tests unless a repository test needs rollback (then say why); unique data per test; no hidden coupling between tests. Add a small `support` package for test data builders.

1. **Unit tests**: `MoneyRulesTest` (scales for INR, JPY, SGD; JPY rejects decimals; INR rejects three decimals; normalize rounds half up; percentOf), `ApiExceptionTest` (each subclass has the right status and code), `PageResponseTest`, `DomainEventTest` (factory fields, unique ids), `ConfigurationPropertiesTest` (the YAML binds into the records and validation rejects a too-short JWT secret setting if you added that validation).
2. **Repository tests** with `@DataJpaTest` + `@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)` + `@ActiveProfiles("test")`:
   - Flyway applied all six migrations; the six markets exist with the right currencies and thresholds; the two counters exist with `next_value` 10001.
   - Hibernate `validate` passes (the context starting is the proof; also assert the Flyway history has six successful rows).
   - Constraint tests with `assertThrows(DataIntegrityViolationException.class, ...)` and a `flush`: duplicate market code, duplicate `user_id`, an OFFICER without a market (check constraint), duplicate `claim_number`, duplicate `(user_id, idempotency_key)`, duplicate `(source_event_id, recipient_id)` notification, a payment instruction with a duplicate `claim_number`.
   - **Enum drift guard** (parameterized with `@EnumSource`): for every constant of `ClaimStatus`, `TimelineEntryType`, `NotificationType`, `RejectionReason`, `OutboxStatus` and `PaymentStatus`, persist a row that uses it and flush successfully, proving the CHECK constraints accept every Java value; and a native-SQL insert of an invalid value is rejected.
   - Repository behaviour: `findForUpdate` returns the counter and an increment inside a transaction persists; `ClaimRepository.findByClaimNumber`; the duplicate-claim existence query; latest assessment query; outbox "due events" query returns only PENDING events whose `nextAttemptAt` has passed, in order; notification unread count.
3. **Seeder tests** (`@SpringBootTest` + `@ActiveProfiles("test")`): after startup there are 6 markets, 13 users, 13 policies, 2 counters; passwords are stored hashed and `matches` succeeds for the documented passwords; running `DataInitializer.run(...)` a second time changes nothing (counts identical, no exception); the LAPSED policy exists; managers have no market, officers and claimants have the right market; the sum insured and deductible follow the formula for at least INR and JPY.
4. **Context test**: the application context loads with H2, Flyway, Hibernate `validate` and the seeder.

Target at least 40 test methods in this prompt (parameterized cases count once per method). Run `mvnw -B clean verify`. All tests must pass. Read the surefire summary and report the real numbers (tests run, failures, errors, skipped). Fix real defects in main code or migrations; never relax a test or constraint to get green.

Then make the commits for stages 3 and 4, then stage 6, in this order with accurate messages (stage the right files for each; if separating them is impractical because the schema and entities must be committed together to build, combine stages 3 and 4 into one commit):
- `feat(db): add flyway migrations v1 to v6`
- `feat(domain): add entities and repositories for all modules`
- `test(data): add unit, repository, seeder and context tests`

## 8. STAGE 7: VERIFY AGAINST REAL POSTGRESQL, THEN DOCUMENT (commit)

1. Make sure PostgreSQL is reachable and `claims_db` exists. Find `psql.exe` (PATH, then `C:\Program Files\PostgreSQL\17\bin\psql.exe`). With `$env:PGPASSWORD = 'postgres'` run `psql -h localhost -p 5432 -U postgres -d claims_db -c "select 1"`. If `claims_db` does not exist and `psql` works, create it with `psql -h localhost -U postgres -c "CREATE DATABASE claims_db"`. If `claims_db` is missing and you cannot create it, STOP AND ASK (section 9) with the pgAdmin steps.
2. Build the jar (`mvnw -B -DskipTests package`) and start it with JDK 21: `java -jar target\claims-management-platform.jar`, output redirected to `target\verify-app.log`, keeping the process id. Wait up to 120 seconds for the log line `Started ClaimsManagementPlatformApplication`. Then read the log: it must show Flyway applying 6 migrations (`Successfully applied 6 migrations` or the equivalent), the `Demo data ready` line, and **no `ERROR` lines and no stack traces**. The generated-security-password INFO line is expected until prompt 3.
3. With `psql`, check: the tables exist (`\dt` or a query on `information_schema.tables`: at least markets, app_users, policies, claim_counters, claims, claim_assessments, information_requests, claim_timeline_entries, idempotency_keys, outbox_events, payment_instructions, notifications, flyway_schema_history); `select count(*) from markets` is 6; `app_users` 13; `policies` 13; `claim_counters` 2 with `next_value` 10001; `select version, success from flyway_schema_history` shows six successful rows; `select user_id, role, left(password_hash, 7) from app_users limit 3` shows BCrypt hashes (prefix `$2a$`). If `psql` is not available, verify through the log lines only and say so.
4. Stop the application (kill by process id) and confirm port 8080 is free. Start it a second time and confirm the second start applies no migrations, logs the `Demo data ready` line with zero inserted rows, and has no errors. Stop it again.
5. Update the documents so they match reality: in `docs/03-data-model.md` change the status of everything built to **Implemented** and correct any column, constraint or index that differs from what you actually created; in `docs/requirements-traceability.md` set R12 to "Data layer implemented (API in prompt 3)" and leave the other rows as planned; update `NOTES.md` if an assumption changed; add the prompt 2 entries to the stage log in `.claude/ai-journal.md` with the real commands, the real test numbers, problems met and how they were fixed, and honest limitations.
6. Ensure `git status` is clean after the commit: `docs: reconcile data model, traceability and ai journal for the data layer`.

## 9. STOP AND ASK (the only reasons to pause)

- `claims_db` is missing and cannot be created by you: give the pgAdmin steps (Servers, PostgreSQL 17, right-click Databases, Create, Database, name `claims_db`, owner `postgres`, Save) and wait for "done".
- PostgreSQL refuses the connection or the password (show the exact error).
- Dependencies cannot be downloaded (show the exact error; never invent proxy settings).
- The same failure persists after three real fix attempts: stop and report what you tried.

If you run out of room, finish the current stage, commit, update the stage log in `.claude/ai-journal.md`, and tell the developer which stage is next. When the developer replies "continue", read the stage log and resume.

## 10. KNOWN PITFALLS FOR THIS PROMPT

1. Hibernate `validate` compares types: `Instant` needs `TIMESTAMP WITH TIME ZONE`, `LocalDate` needs `DATE`, `BigDecimal` needs `NUMERIC(19,2)`, `boolean` needs `BOOLEAN`, enums as strings need `VARCHAR`. A `CHAR` column or a `TIMESTAMP` without time zone makes validation fail.
2. A CHECK constraint that does not list every enum constant makes inserts fail later. The enum drift test exists to catch this.
3. Do not use reserved or risky names as identifiers (`user`, `order`, `value`, `year`, `read`, `key`). The spec already avoids them (`read_flag`, `idempotency_key`, `app_users`).
4. `OutboxEvent` has an application-assigned String id. Without `Persistable`, Spring Data will do a SELECT then INSERT through `merge`. Implement `Persistable<String>` as described.
5. With `open-in-view=false`, lazy associations must not be touched outside a transaction; repository tests that read lazy fields need `@Transactional` on the test method or a fetch join.
6. `@DataJpaTest` does not run `ApplicationRunner` beans, so the seeder does not run there. Seeder tests use `@SpringBootTest`.
7. Windows: every Maven command needs `JAVA_HOME` = JDK 21; PowerShell `curl` is an alias, use `psql` and `Invoke-RestMethod` or leave HTTP checks to later prompts.
8. If you change an enum or a CHECK constraint after V1 to V6 were applied to `claims_db`, reset the schema as described in stage 3; never leave the real database out of sync with the migrations.

## 11. FINAL REPORT FOR THIS PROMPT (short, plain language)

Report: the files created (grouped by module), the commits (`git log --oneline`), the real `mvnw -B clean verify` result with test counts, the real PostgreSQL checks (migrations applied, row counts, second-start idempotency), anything that deviated from this prompt and why, and anything the developer must do. End with: "Prompt 2 complete. Ready for prompt 3 (security and identity)."

Begin with Stage 0 now.
