# PROMPT 3 OF 7: SECURITY AND IDENTITY (JWT, ERROR CONTRACT, LOGIN WITH ROLE DROPDOWN, USERS, MARKETS, SWAGGER)

You are the same senior Java / Spring Boot engineer continuing the **claims-management-platform** project inside IntelliJ IDEA on Windows 11. Before doing anything else, read `docs/PROJECT_SPEC.md` (the single source of truth: especially sections 3, 6, 7 and 15), `.claude/ai-journal.md` (what has been done), `NOTES.md`, and `docs/04-api-design.md`. Then carry out this prompt. In this prompt you build: the global error contract and correlation ids, JWT security, the identity API (login with a role dropdown, registration, users), the markets API, the OpenAPI/Swagger configuration, the root redirect, a first version of the live smoke script, and tests for all of it. Claims, policies, notifications and reporting endpoints come in prompts 4 to 6.

Working rules (unchanged):
- Keep a task list. Work through the stages in order without waiting for approval between stages. Ask the developer only in the situations in section 10.
- Never claim something works unless you ran it and saw the real output. Never weaken, skip or delete a test to make a build pass.
- Use ONLY the Maven Wrapper (`.\mvnw.cmd` in PowerShell or cmd, `./mvnw` in Git Bash) with `-B`, and always with `JAVA_HOME` set to the JDK 21 folder recorded in `.claude/ai-journal.md` (set it in every terminal session and check `java -version` prints 21 first). The system `java` is JDK 25: never use it.
- Docker is not installed (never run `docker`); Kafka is not used; never add a remote or push; never change global git config; PostgreSQL 17 at `localhost:5432`, user `postgres`, password `postgres`, database `claims_db`.
- Stack unchanged: Java 21, Spring Boot 3.5.6, no Lombok, DTOs as records, `jakarta.*` only, constructor injection, Javadoc on every class.
- Flyway migrations V1 to V6 are applied to the developer's `claims_db` and are **locked**. Any schema change from now on is a new migration (`V7__...`), never an edit.

---

## 1. STAGE 0: SANITY CHECK AND SPEC AMENDMENT

1. Confirm `prompts/prompt-03-security-and-identity.md` exists (if this prompt was pasted into the chat, save it there verbatim). Add its line to `prompts/README.md`.
2. `git status` must be clean and `git log --oneline` must show the prompt 1 and prompt 2 commits. Run `mvnw -B clean verify` with JDK 21: BUILD SUCCESS before you add anything.
3. **Amend the specification.** Prompt 2 found a real conflict: `app_users.market_id` references `markets`, so `identity` must depend on `market`. In `docs/PROJECT_SPEC.md` section 3, change the `identity` row of the allowed-dependency table to: `shared, config, security, market`. Add one sentence under the table: "identity depends on market because every user belongs to a market; market depends only on shared and config, so no cycle is created (amended in prompt 3)." Make `docs/02-architecture.md`, `NOTES.md` and the journal consistent with this (they may already describe it; do not duplicate). Do NOT edit `prompts/prompt-01-...md`: it is the historical record.
4. Commit: `docs: allow identity to depend on market in the module dependency table`.

## 2. STAGE 1: ERROR CONTRACT AND CORRELATION IDS (commit)

In `shared` (it must not depend on any feature module):

- `shared.exception.UnauthenticatedException` (extends `ApiException`, 401, `UNAUTHENTICATED`).
- `shared.security.Authz`: a final constants class for `@PreAuthorize` expressions, so feature modules never depend on `security`: `CLAIMANT = "hasRole('CLAIMANT')"`, `OFFICER = "hasRole('OFFICER')"`, `MANAGER = "hasRole('MANAGER')"`, `STAFF = "hasAnyRole('OFFICER','MANAGER')"`, `AUTHENTICATED = "isAuthenticated()"`.
- `shared.api.ProblemDetails`: a small factory that builds an RFC 7807 `ProblemDetail` with `title`, `status`, `detail`, the extra properties `code`, `timestamp` (ISO instant), `correlationId` (from the logging MDC key `correlationId`, or absent when none), and an optional `errors` list of `{field, message}`. It is also used by the security handlers.
- `shared.api.CorrelationIdFilter`: a `OncePerRequestFilter` with `@Order(Ordered.HIGHEST_PRECEDENCE)` registered as a component. It reads `X-Correlation-Id`; if absent or not matching `^[A-Za-z0-9._-]{1,64}$` it generates a UUID. It puts the value in the MDC under `correlationId`, sets it on the response header `X-Correlation-Id` **before** continuing the chain (so even 401 responses carry it) and removes the MDC entry in a `finally` block.
- `shared.api.GlobalExceptionHandler`: `@RestControllerAdvice` extending `ResponseEntityExceptionHandler`. Requirements:
  - Override `handleExceptionInternal` so that **every** `ProblemDetail` body produced by Spring MVC or by this class is decorated with `code`, `timestamp` and `correlationId`.
  - `ApiException` -> its status, code and message. `MethodArgumentNotValidException` (override) and `ConstraintViolationException` -> 400 `VALIDATION_FAILED` with `errors`. `HttpMessageNotReadableException` (override, including invalid enum values) -> 400 `MALFORMED_REQUEST`. `MethodArgumentTypeMismatchException` and `MissingServletRequestParameterException` -> 400 `VALIDATION_FAILED` naming the parameter (for example a missing or unknown `role`). `ObjectOptimisticLockingFailureException` -> 409 `CONCURRENT_MODIFICATION`. `DataIntegrityViolationException` -> 409 `DUPLICATE_RESOURCE` with a generic message that does not leak SQL. `NoResourceFoundException` -> 404 `RESOURCE_NOT_FOUND`. 405 and 415 keep their status with a code of `MALFORMED_REQUEST`.
  - **`AccessDeniedException` -> 403 `ACCESS_DENIED` and `AuthenticationException` -> 401 `UNAUTHENTICATED`, handled explicitly.** A catch-all handler otherwise turns `@PreAuthorize` failures into 500 errors.
  - A final `Exception` catch-all -> 500 `INTERNAL_ERROR` with the generic message "Unexpected error" (stack trace only in the log, with the correlation id).

Verify with `mvnw -B clean compile`. Commit: `feat(shared): add global exception handler, correlation id filter and problem details`.

## 3. STAGE 2: JWT SECURITY (commit)

In `security` (depends only on `shared` and `config`; it must not import `identity`):

- `config.ClockConfig` (module `config`): a `Clock` bean (`Clock.systemUTC()`). Use `Clock` everywhere time is read from now on, so tests can control it.
- `security.JwtConfig`: the encoder and decoder beans with **exactly** the wiring in spec section 6 (`SecretKeySpec` "HmacSHA256", `NimbusJwtEncoder(new ImmutableSecret<>(key))`, `NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build()`), plus an `OAuth2TokenValidator` that checks the issuer equals `app.security.jwt.issuer` together with the default timestamp validation. Fail fast with a clear `IllegalStateException` when the secret is shorter than 32 bytes.
- `security.TokenIssuer`: `IssuedToken issue(String userId, String roleName, String marketCode)` returning a record `(String token, long expiresInSeconds)`. It always passes an explicit HS256 `JwsHeader`; claims `iss`, `sub` (the user id), `iat`, `exp` (from `app.security.jwt.expiry-minutes`), `role`, and `market` **only when not null** (`JwtClaimsSet.Builder.claim` throws on null). Uses the `Clock` bean.
- `security.SecurityConfig`: `@Configuration`, `@EnableWebSecurity`, `@EnableMethodSecurity`. One `SecurityFilterChain` bean: CSRF disabled; stateless sessions; `httpBasic`, `formLogin` and `logout` disabled; `authorizeHttpRequests` with `requestMatchers` permitting `/`, `/api/v1/auth/**`, `/swagger-ui.html`, `/swagger-ui/**`, `/v3/api-docs/**`, `/actuator/health`, `/error` and requiring authentication for everything else; `oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter)).authenticationEntryPoint(entryPoint).accessDeniedHandler(deniedHandler))`. The converter is a `JwtAuthenticationConverter` using a `JwtGrantedAuthoritiesConverter` with `setAuthoritiesClaimName("role")` and `setAuthorityPrefix("ROLE_")`. Also expose an `AuthenticationManager` bean from `AuthenticationConfiguration.getAuthenticationManager()` (it is built from the `UserDetailsService` and `PasswordEncoder` beans).
- `security.RestAuthenticationEntryPoint` (401, `UNAUTHENTICATED`) and `security.RestAccessDeniedHandler` (403, `ACCESS_DENIED`): write `application/problem+json` using `ProblemDetails` and the `ObjectMapper`, with the correlation id.

Verify with `mvnw -B clean compile`. Commit: `feat(security): add jwt encoder and decoder, security filter chain and json error handlers`.

## 4. STAGE 3: IDENTITY API (commit)

In `identity` (may depend on `shared`, `config`, `security`, `market`), layers `api`, `service`, `domain` (already exists), `infrastructure`:

- `infrastructure.AppUserDetailsService implements UserDetailsService`: loads by lower-cased `user_id`; account disabled when `active` is false; one authority `ROLE_<role>`; the password hash comes from the entity.
- `service.CurrentUserService`: `AppUser requireCurrentUser()` reads the authentication name (`sub`) from the `SecurityContext`, loads the user from the database, and throws `UnauthenticatedException` when the user is missing or inactive. Other modules call this; controllers pass the `AppUser` to services.
- `service.AuthService`:
  - `login(userId, password, role)`: lower-case and trim the user id; authenticate with `AuthenticationManager`; then require that the account's role equals the requested role. **Every failure** (unknown user, bad password, inactive, wrong role) throws the same `UnauthenticatedException` with the message "Invalid user ID, password or role". Log failures at WARN with the user id and requested role but never the password. On success call `TokenIssuer` and return `LoginResponse`.
  - `register(RegisterRequest)`: always creates a CLAIMANT; rejects duplicate `userId` or `email` with `DuplicateResourceException`; the market code must exist and be active (else `ValidationFailedException` on field `marketCode`); BCrypt hash.
- `service.UserService`: `createStaff(CreateUserRequest)` (OFFICER requires an active market; MANAGER must not have one; duplicate checks), `getMe`, `list(role, marketCode, pageable)` using a `Specification` (no optional-null JPQL), mapping to `UserResponse` inside the transaction.
- DTO records (package `identity.api.dto`): `LoginRequest(userId, password)`, `LoginResponse(accessToken, tokenType, expiresInSeconds, user)`, `RegisterRequest(userId, password, fullName, email, marketCode)` (**no role field**), `CreateUserRequest(userId, password, fullName, email, role, marketCode)`, `UserResponse(userId, fullName, email, role, marketCode, active, createdAt)`, `UserSummary(userId, fullName, role, marketCode)` for the login response. Validation: `userId` pattern `^[A-Za-z0-9._-]{4,30}$`, password `^(?=.*[A-Za-z])(?=.*\d).{8,64}$`, `fullName` 2 to 100, `email` valid and at most 150, `marketCode` two letters. Every field has `@Schema(description, example)` with a **valid example** (login examples `claimant.in` and `Claimant@123`; the password field uses `format = "password"`); `CreateUserRequest.role` documents `allowableValues = {"OFFICER","MANAGER"}`.
- Controllers (`@RestController`, `@Tag`, `@Operation`, `@ApiResponse` entries for the real status codes, `@PreAuthorize` using `Authz`):
  - `AuthController`: `POST /api/v1/auth/login` with `@RequestParam("role") Role role` (required enum query parameter, so Swagger UI shows a dropdown) and `@Valid @RequestBody LoginRequest`; operation description exactly: "1) choose your role in the dropdown, 2) enter userId and password in the body, 3) Execute, 4) copy accessToken, click Authorize, paste the token only." `POST /api/v1/auth/register` returns 201 with a `UserResponse` body and no `Location` header. Both operations carry an empty `@SecurityRequirements` so Swagger shows no lock.
  - `UserController`: `GET /api/v1/users/me` (`Authz.AUTHENTICATED`), `POST /api/v1/users` (`Authz.MANAGER`, 201), `GET /api/v1/users?role=&marketCode=&page=&size=` (`Authz.MANAGER`, returns `PageResponse<UserResponse>`, page size capped at 50, default 10, sort whitelist `userId`, `createdAt`).

Verify with `mvnw -B clean compile`. Commit: `feat(identity): add login, registration and user management api`.

## 5. STAGE 4: MARKETS API (commit)

In `market`: `service.MarketService` (`list()`, `getByCode(code)` throwing `ResourceNotFoundException`), `api.MarketController` (`GET /api/v1/markets`, `GET /api/v1/markets/{code}`, both `Authz.AUTHENTICATED`), `api.dto.MarketResponse(code, countryName, currencyCode, escalationThreshold, active)` with `@Schema` examples. Code lookups are case-insensitive (normalize to upper case).

Commit: `feat(market): add markets api`.

## 6. STAGE 5: OPENAPI AND ROOT REDIRECT (commit)

- `config.OpenApiConfig` per spec section 7: title "Claims Management Platform API", version 1.0.0, a description that explains the login steps and lists the demo accounts' user ids (never the passwords outside the example values), tags for Authentication, Users, Markets, Claims, Notifications, Reports, a `bearerAuth` HTTP bearer JWT security scheme, and a global security requirement so protected operations show the lock.
- `bootstrap.api.RootRedirectController`: `GET /` redirects to `/swagger-ui/index.html`; annotated `@Hidden`.
- Check that Swagger UI, `/swagger-ui.html` and `/v3/api-docs` are reachable without a token and that the login operation's `role` parameter appears as an enum with three values.

Commit: `feat(config): add openapi configuration and root redirect`.

## 7. STAGE 6: TESTS (H2 in PostgreSQL mode, profile `test`, no Docker)

Follow spec section 13: unique data per test, no hidden coupling, log in through the real login endpoint to obtain tokens. Add a test-only controller under `src/test/java` (path `/api/v1/_test/...`, never shipped) that throws each exception type so the handler can be tested end to end. Add a shared `support` base class or helper for logging in, building bearer headers and creating unique user ids.

1. **Unit tests**: `TokenIssuerTest` (HS256 header, claims, expiry from the controlled `Clock`, `market` claim absent for managers); `JwtConfigTest` (decoder rejects a tampered token, an expired token, a token signed with another key, a token with a different algorithm header, and a wrong issuer; a secret shorter than 32 bytes fails fast); `ProblemDetailsTest`; `CorrelationIdFilterTest` (generates when missing, echoes a valid value, replaces an invalid or over-long value, clears the MDC); `AuthServiceTest` and `UserServiceTest` with Mockito (every failure path gives the same message; role mismatch; duplicate handling; market rules).
2. **Integration tests** (`@SpringBootTest` + `@AutoConfigureMockMvc` + `@ActiveProfiles("test")`):
   - Login: success for each of the three roles with the seeded accounts (response shape, token usable on `/api/v1/users/me`); wrong password 401; right password wrong role 401; unknown user 401; inactive user 401 (create and deactivate one); missing `role` 400; unknown role value 400; same generic message in all 401 cases; user id is case-insensitive.
   - Token handling: no token 401 with a `application/problem+json` body containing `code=UNAUTHENTICATED` and a correlation id; tampered token 401; expired token 401 (build one with the encoder and a past clock); a token for a user that no longer exists 401.
   - Authorization: claimant calling `POST /api/v1/users` and `GET /api/v1/users` gets 403 with `code=ACCESS_DENIED` as a JSON body (this proves the access-denied path does not become a 500); officer gets 403 on the same endpoints; manager succeeds.
   - Registration: success creates a CLAIMANT (even if the body contains a `role` property, which is ignored); then login works; duplicate `userId` 409; duplicate email 409; weak password 400 with `errors` naming `password`; invalid email 400; unknown market 400 naming `marketCode`.
   - User management: manager creates an officer for market IN (201) who can then log in as OFFICER; OFFICER without a market 400; MANAGER with a market 400; creating a CLAIMANT through this endpoint is rejected 400; list is paged and filtered by role and market; page size capped at 50.
   - Markets: authenticated users get six markets with the right currencies and thresholds; one by code (lower-case input works); unknown code 404 `RESOURCE_NOT_FOUND`; no token 401.
   - Error contract through the test-only controller: each exception type maps to the documented status, `code`, `timestamp` and `correlationId`; malformed JSON 400 `MALFORMED_REQUEST`; unsupported method 405; unknown path (authenticated) 404; a response carries `X-Correlation-Id` (echoed when supplied, generated otherwise), including 401 responses.
   - OpenAPI and docs: `/v3/api-docs` is public, contains `bearerAuth`, `/api/v1/auth/login` with a `role` query parameter whose enum is exactly CLAIMANT, OFFICER, MANAGER, and `/api/v1/markets`; `/swagger-ui/index.html` and `/swagger-ui.html` do not require a token (200 or a redirect); `GET /` returns a redirect to the Swagger UI; `/actuator/health` is public and reports UP.
3. Target at least 45 new test methods. Run `mvnw -B clean verify`; every test must pass; read the surefire summary and report the real numbers (run, failures, errors, skipped) and the JaCoCo line coverage figure if the report was produced. Fix real defects in main code; never relax a test.

Commit: `test(security): add unit and integration tests for the error contract, jwt security, identity and markets`.

## 8. STAGE 7: FIRST VERSION OF THE LIVE SMOKE SCRIPT AND REAL-DATABASE VERIFICATION (commit)

1. Create `scripts/SmokeTest.java`: a single-file Java program (run with `java scripts/SmokeTest.java [baseUrl]`, default `http://localhost:8080`; it must work with JDK 21 and later) built on `java.net.http.HttpClient` with redirects disabled, Java text blocks for JSON bodies (no shell quoting problems) and simple regex helpers to read `accessToken`. Build a small reusable harness now, because later prompts add steps: a `step(String name, int expectedStatus, Supplier<HttpResponse<String>>)` style helper that prints `PASS` or `FAIL` with the real status, a helper that logs in and returns a token, a helper to assert that a body contains a text, a failure counter, and `System.exit(1)` when anything failed. Steps for this prompt: health is UP; Swagger UI 200; `/v3/api-docs` 200 and contains `bearerAuth` and `CLAIMANT`; `GET /` returns 302; login succeeds for `claimant.in`, `officer.in` and `manager.apac` with their roles; the same credentials with the wrong role 401; wrong password 401; `/api/v1/users/me` without a token 401 and the body contains `UNAUTHENTICATED`; `/api/v1/users/me` with each token 200; claimant `POST /api/v1/users` 403; manager creates an officer with a unique user id 201 and that officer can log in; register a unique claimant 201 and log in; `GET /api/v1/markets` 200 and contains `INR` and `JPY`; a registration with a weak password 400 and the body contains `errors`. The script must be easy to extend in later prompts.
2. Build the jar and start the application against the real `claims_db` with JDK 21 (`java -jar target\claims-management-platform.jar`, output to `target\verify-app.log`, remember the process id; if port 8080 is busy use `SERVER_PORT=8081` and tell the developer). Wait up to 120 seconds for `Started ClaimsManagementPlatformApplication`.
3. Run `java scripts/SmokeTest.java http://localhost:8080` (with the real port). Every step must PASS. Fix the code (never the expectation) until it does. Read the log: no `ERROR`, no stack traces; log lines show the correlation id; no password, token or full request body is logged.
4. Stop the application (kill by process id) and confirm the port is free.

Commit: `build: add live smoke test script for security and identity`.

## 9. STAGE 8: DOCUMENTS (commit)

Reconcile the documents with what now exists: `docs/04-api-design.md` (mark the auth, users and markets endpoints and the error contract **Implemented** and correct any difference, including the final error codes and the correlation id behaviour); `docs/requirements-traceability.md` (R12 becomes "Implemented: markets data and API", and add the real test class names for the rows now covered); `docs/02-architecture.md` (security architecture matches the real classes); ADRs 0004 and 0011 (note anything that changed); `NOTES.md` (any new assumption, for example the role-in-query-parameter choice and the generic 401 message); `.claude/ai-journal.md` (prompt 3 entries: real commands, real test numbers, problems met and fixes, honest limitations such as no login throttling or account lockout and no refresh tokens, and the Swagger role dropdown decision); `prompts/README.md`.

Commit: `docs: reconcile api design, traceability and ai journal for security and identity`. `git status` must be clean afterwards.

## 10. STOP AND ASK (the only reasons to pause)

- PostgreSQL refuses the connection or the password (show the exact error), or `claims_db` is missing.
- Dependencies cannot be downloaded (show the exact error; never invent proxy settings).
- The same failure persists after three real fix attempts: stop and report what you tried.

If you run out of room, finish the current stage, commit, update the stage log in `.claude/ai-journal.md`, and tell the developer which stage is next. When the developer replies "continue", read the stage log and resume.

## 11. KNOWN PITFALLS FOR THIS PROMPT

1. The default `NimbusJwtDecoder` expects RS256; set HS256. Always pass an explicit HS256 `JwsHeader` when encoding. Never add the `market` claim when it is null.
2. A catch-all exception handler hides `AccessDeniedException` (403 becomes 500). Handle it explicitly and keep the test that proves it.
3. Do not use `antMatchers`, `authorizeRequests`, `WebSecurityConfigurerAdapter` or `@MockBean`. Use `requestMatchers`. In tests that need a bean replaced use `@TestConfiguration` or `@MockitoBean`.
4. `/error` must be permitted, otherwise real error bodies become 401 responses.
5. Enum query parameters that cannot be converted throw `MethodArgumentTypeMismatchException`; the handler must turn that into a 400 and not a 500.
6. Jackson in Spring Boot ignores unknown properties by default; the registration test relies on this to prove a `role` property cannot escalate privileges.
7. With `open-in-view=false`, map entities to DTOs inside the `@Transactional` service method; controllers never return entities.
8. `@PreAuthorize` needs `@EnableMethodSecurity`; the `Authz` constants must be compile-time constants (`public static final String`).
9. `JwtGrantedAuthoritiesConverter` splits string claims on spaces; the single role value is fine. Do not put several roles in one claim.
10. PowerShell `curl` is an alias; the smoke script exists precisely to avoid shell quoting and alias problems. Do not use `curl` for verification.
11. Every Maven command needs `JAVA_HOME` = JDK 21.

## 12. FINAL REPORT FOR THIS PROMPT (short, plain language)

Report: the files created (grouped by module), the commits (`git log --oneline`), the real `mvnw -B clean verify` result with test counts, the real smoke script output (PASS/FAIL per step), anything that deviated from this prompt and why, any spec decision the developer must take, and anything the developer must do. End with: "Prompt 3 complete. Ready for prompt 4 (claims core)."

Begin with Stage 0 now.
