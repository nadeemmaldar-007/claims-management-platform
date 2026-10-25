# 02. Architecture

Status: **partly Implemented.** `shared`, `config`, `security`, `identity`, `market`, `policy`, `bootstrap` and, except for settlement, `claim` are built (prompts 2 to 4), and `integration` has its outbox write side. `notification`, `reporting`, the outbox relay and the payment gateway are **Planned** until the prompt that builds them completes.
Date: 2026-10-25. Source of truth: [PROJECT_SPEC.md](PROJECT_SPEC.md) sections 2, 3, 6, 8.

## 1. Architecture drivers and quality attributes

| Driver | Consequence for the design |
|---|---|
| Six markets, one team structure per market | Market is a first-class concept; scope rules (claimant, officer-by-market, manager-global) are enforced in services |
| Money and authority limits | Money is `NUMERIC(19,2)` with currency-specific decimal places; approval limits are checked in the service layer |
| Auditability | Every action writes a timeline entry; claims carry decision and settlement fields; events are persisted before delivery |
| Correctness under concurrency | Optimistic locking on claims, row-locked claim counters, idempotent submission and settlement |
| Replaceable external systems | Policy lookup, payment and notification channels sit behind ports |
| Reviewable by assessors | One deployable, boundaries checked by automated tests, no Docker needed to build or test |

Quality attributes, in priority order:

1. **Correctness and auditability.** A decision must be explainable from the timeline and the stored fields.
2. **Security.** No claim data leaks across roles or markets; out-of-scope resources look absent (404).
3. **Maintainability.** Modules with explicit dependencies; rules enforced by ArchUnit rather than convention.
4. **Testability.** All tests run on H2 in PostgreSQL mode without Docker.
5. **Operability.** Health endpoint, correlation id on every request, configuration through environment variables.
6. **Performance and scale.** A secondary concern: one instance is expected to be enough; the design records how it would scale (section 10).

## 2. Architectural style: modular monolith

The system is **one deployable** (a single Spring Boot application, base package `com.apac.claims`) split into **eleven modules** that are first-level packages. Boundaries are explicit and are enforced by ArchUnit tests, not by Maven sub-modules.

Why a modular monolith rather than microservices or one undivided package:

- The domain is small and strongly consistent: a claim change, its timeline entry and its outbox event must commit together. A single database transaction gives that for free.
- Microservices would add network failure modes, distributed transactions and deployment effort that the problem does not need.
- An undivided package would let any class call any other, and the dependency structure would erode. Explicit modules with a checked dependency table keep the later option of splitting by module open (section 10).
- See [ADR 0001](adr/0001-modular-monolith-with-enforced-module-boundaries.md).

## 3. Module responsibilities

| Module | Responsibility | Status |
|---|---|---|
| `shared` | Kernel: `ApiException` base class (error code and HTTP status), error codes, `PageResponse`, `MoneyRules`, domain-event contracts (`DomainEvent`, `DomainEventPublisher`, `DomainEventHandler`), correlation id filter, and the single `GlobalExceptionHandler` in `shared.api` | Implemented |
| `config` | `@ConfigurationProperties` records and OpenAPI configuration | Implemented |
| `security` | JWT issuing and decoding, security filter chain, JSON 401 and 403 handlers. Depends on no feature module | Implemented |
| `identity` | Users, roles, login, registration, current-user resolution, adapter from users to Spring's `UserDetailsService` | Implemented |
| `market` | The six markets: country, currency, escalation threshold | Implemented |
| `policy` | Policy reference data (a local snapshot standing in for the policy administration system) and the adapter that implements claim's `PolicyLookup` port | Implemented |
| `claim` | Core: first notice of loss, workflow, queue and pick-up, assignment, assessment, information requests, decisions, settlement, timeline, claim numbers | Implemented except settlement (prompt 5) |
| `notification` | In-app notifications, event handlers, `NotificationChannel` port and the logging email adapter | Planned |
| `reporting` | Workload, performance, aging, exposure, overview (read side) | Planned |
| `integration` | Transactional outbox, relay, payment gateway adapter | Outbox write side Implemented; relay and gateway Planned |
| `bootstrap` | Composition root: demo-data `DataInitializer` and the root redirect controller (`bootstrap.api`). Nothing depends on it | Implemented |

### The claim module as built (prompt 4)

| Part | Classes | Notes |
|---|---|---|
| Workflow rules | `ClaimWorkflow`, `ClaimAction`, `AssessmentCalculator` (all `claim.domain`) | Pure Java with no Spring or I/O: the whole transition table and the payout formula are tested exhaustively |
| Scope and authority | `ClaimScope`, `ClaimActionSupport` (`claim.service`) | One place decides who may see a claim (404 outside the scope) and one answers "assigned officer or manager" (403) |
| Use cases | `ClaimSubmissionService`, `ClaimQueryService`, `ClaimAssignmentService`, `ClaimAssessmentService`, `InformationRequestService`, `ClaimDecisionService` | Each public method is one transaction and one use case; each class is under 250 lines |
| Mapping | `ClaimMapper` | Maps entities to records inside the transaction and applies what a claimant may not see (staff user ids, assessments, the reason of an escalation) |
| Timeline | `TimelineService` | The only creator of timeline entries; visibility is decided by the entry type |
| Claim numbers | `ClaimNumberGenerator` | Row-locked counters, `MANDATORY` transaction, taken after all validation ([ADR 0007](adr/0007-claim-numbers-from-row-locked-counters.md)) |
| Port | `PolicyLookup` and `PolicyView` (`claim.service`), implemented by `policy.infrastructure.PolicySnapshotLookup` | `claim` imports nothing from `policy`; `PolicyView` has its own status enum ([ADR 0008](adr/0008-ports-and-adapters-for-external-integrations.md)) |
| Event write side | `ClaimEvents` (builds the eight events), `shared.event.DomainEventPublisher` (the port), `integration.service.OutboxDomainEventPublisher` (the adapter) | A service raises an event in the same transaction as the state change; the publisher requires that transaction (`MANDATORY`). The relay that delivers the rows is built in prompt 5 |

The three architecture test classes in `src/test/java/com/apac/claims/architecture` are the first of the executable rules (prompt 6 completes them). `ModuleDependencyRulesTest` is table-driven: it holds the table of section 4 as data, computes for every module the modules its classes really depend on, and reports the class, the target and the module pair of every dependency the table does not allow; the other tests check cycles, the special rules of section 4, and that no module uses another module's `api` or `infrastructure` package. `LayerRulesTest` checks the layers of section 5 inside every feature module, and `CodingRulesTest` the coding conventions. The two named exceptions are recorded in [ADR 0014](adr/0014-api-records-and-kernel-api-in-the-layer-rules.md).

## 4. Allowed module dependencies

Anything not listed is forbidden, and the graph has no cycles.

| Module | May depend on |
|---|---|
| shared | none |
| config | shared |
| security | shared, config |
| identity | shared, config, security, market (see the note below the graph) |
| market | shared, config |
| claim | shared, config, identity, market |
| policy | shared, config, identity, market, claim (implements claim's `PolicyLookup` port) |
| notification | shared, config, identity |
| reporting | shared, config, identity, market, claim |
| integration | shared, config, claim (implements claim's `PaymentGateway` port; dispatches shared events) |
| bootstrap | every module (it only wires and seeds); nothing depends on it |

```mermaid
graph TD
    shared["shared"]
    config["config"]
    security["security"]
    identity["identity"]
    market["market"]
    claim["claim"]
    policy["policy"]
    notification["notification"]
    reporting["reporting"]
    integration["integration"]
    bootstrap["bootstrap"]

    config --> shared
    security --> shared
    security --> config
    identity --> shared
    identity --> config
    identity --> security
    identity --> market
    market --> shared
    market --> config
    claim --> shared
    claim --> config
    claim --> identity
    claim --> market
    policy --> shared
    policy --> config
    policy --> identity
    policy --> market
    policy --> claim
    notification --> shared
    notification --> config
    notification --> identity
    reporting --> shared
    reporting --> config
    reporting --> identity
    reporting --> market
    reporting --> claim
    integration --> shared
    integration --> config
    integration --> claim

    bootstrap -.-> shared
    bootstrap -.-> config
    bootstrap -.-> security
    bootstrap -.-> identity
    bootstrap -.-> market
    bootstrap -.-> claim
    bootstrap -.-> policy
    bootstrap -.-> notification
    bootstrap -.-> reporting
    bootstrap -.-> integration
```

The dotted edges show that `bootstrap` depends on every module. It is the composition root; nothing depends on it.

**Clarification found in prompt 2, amended in the specification in prompt 3.** The first version of the specification's table listed `identity` as depending on `shared`, `config` and `security` only. The data layer needs one more edge, `identity` to `market`: `app_users.market_id` is a foreign key to `markets`, so `AppUser` holds an association to `Market`, and both registration and login work with market codes. The edge creates no cycle, because `market` depends only on `shared` and `config`. `docs/PROJECT_SPEC.md` now has the same row, and the architecture tests written in later prompts must allow this edge.

Cross-module rules that the table does not show:

- A module may use another module's `service` classes and `domain` types, never its `api` or `infrastructure` packages.
- `claim` must not depend on `policy`, `notification`, `reporting` or `integration`. It reaches them only through ports it owns and through the shared event contracts. The arrows from `policy` and `integration` to `claim` are dependency inversion: those modules implement `claim`'s ports.
- `security` depends on no feature module. `identity.infrastructure.AppUserDetailsService` adapts users to Spring's `UserDetailsService`, and `identity.service.AuthService` calls the token issuer in `security` with plain values (user id, role name, market code).

## 5. Layer rules inside a feature module

| Layer (package) | Contains | May depend on |
|---|---|---|
| `api` | Controllers, request and response records | `service`, and `domain` enums that appear in DTOs |
| `service` | Use-case services (transaction boundaries, orchestration, scope checks) and the **ports** to external systems | `domain`, other modules' `service` and `domain` types |
| `domain` | Entities, enums, domain rules, Spring Data repository interfaces (treated as persistence ports, a documented pragmatic choice). No web or servlet types | `shared` and other modules' `domain` types (for example an entity association to a user); never `org.springframework.web`, `jakarta.servlet`, or any `api`, `service` or `infrastructure` package |
| `infrastructure` | Adapters that implement ports, plus framework wiring | `service` (ports), `domain` |

Rules that follow: entities never leave the service layer (services map them to DTOs inside the transaction); controllers are `@RestController` classes in `..api..` named `*Controller`; constructor injection only. See [ADR 0002](adr/0002-pragmatic-hexagonal-layering.md).

One consequence found in prompt 3: the request and response records live in `api.dto`, but the **services** create the response records (so that entities are mapped inside the transaction). `service` therefore depends on `api.dto`, and only on that part of `api`. `LayerRulesTest` allows `..api.dto..` to be used from `service` and keeps controllers, which are the rest of `api`, out of reach; the exception, and the use of the kernel `shared.api` by every module, are recorded in [ADR 0014](adr/0014-api-records-and-kernel-api-in-the-layer-rules.md).

## 6. Communication styles

| Style | Used for | Mechanism |
|---|---|---|
| Synchronous | Client to platform | REST/JSON under `/api/v1` |
| Synchronous, in-process | Module to module | Calls to another module's `service` classes, and to ports |
| Asynchronous | Reacting to claim changes (notifications) | Domain event written to the `outbox_events` table in the same transaction as the state change, then delivered by a scheduled relay to `DomainEventHandler` beans. At-least-once; handlers are idempotent |
| Ports and adapters | External systems | `PolicyLookup`, `PaymentGateway`, `NotificationChannel`, each with one simulated adapter |

Details are in [05-integration-design.md](05-integration-design.md).

## 7. Security architecture

Status: **Implemented** in prompt 3. These are the real classes.

| Class | Package | Role |
|---|---|---|
| `SecurityConfig` | `security` | The one `SecurityFilterChain`: stateless sessions, CSRF, HTTP basic, form login and logout all off; `requestMatchers` lists the public paths and requires authentication for the rest; the JWT resource server with the two JSON handlers; `@EnableMethodSecurity`. Also the `JwtAuthenticationConverter` (the single claim `role` becomes the authority `ROLE_<role>`) and the `AuthenticationManager` bean |
| `JwtConfig` | `security` | Builds the encoder and the decoder from one secret. HS256 only; the decoder validates the issuer and the timestamps (Spring's default 60-second clock-skew tolerance, with time read from the `Clock` bean). A secret shorter than 32 bytes stops the application at startup |
| `TokenIssuer`, `IssuedToken` | `security` | Issues a token from plain values (user id, role name, market code), so `security` does not depend on `identity`. The header names HS256 explicitly; `market` is only added when there is one |
| `RestAuthenticationEntryPoint`, `RestAccessDeniedHandler` | `security` | Write the 401 (with `WWW-Authenticate: Bearer`) and the 403 as `application/problem+json`, with the correlation id |
| `ActiveAccountChecker` | `security` | A port: "is the account of this token still active?" |
| `UserAccountChecker` | `identity.infrastructure` | Implements that port with one existence query |
| `AppUserDetailsService` | `identity.infrastructure` | Adapts accounts to Spring's `UserDetailsService`: lower-cased user id, disabled when inactive, one `ROLE_<role>` authority, the BCrypt hash from the entity |
| `AuthService`, `UserService`, `CurrentUserService` | `identity.service` | Login, account creation (the only place accounts are created), and resolving the caller to an account |
| `Authz` | `shared.security` | Constants for `@PreAuthorize`, so feature modules protect their controllers without importing `security` |
| `CorrelationIdFilter`, `GlobalExceptionHandler`, `ProblemDetails` | `shared.api` | Correlation ids and the error contract |
| `ClockConfig` | `config` | The `Clock` bean; all time is read through it |

- **Authentication.** `POST /api/v1/auth/login?role=` takes a user ID, a password and a role. `role` is a required enum query parameter, which makes Swagger UI show a dropdown; the role stored on the account is authoritative and the parameter is only checked against it. Every failure (unknown user, wrong password, inactive account, wrong role) returns the same 401 with the message "Invalid user ID, password or role", and failures are logged at WARN with the user id and the requested role, never the password. A failure of the user lookup itself (for example the database is down) is a 500, not a 401. Passwords are stored as BCrypt hashes (8 to 64 characters at registration, at least one letter and one digit).
- **Tokens.** Stateless JWT signed with HS256 using a shared secret of at least 32 bytes. Claims: `iss`, `sub` (user id), `iat`, `exp`, `role`, and `market` when present. The decoder is explicitly set to HS256 (its default is RS256) and rejects a tampered token, an expired token, a token signed with another key, a token with another algorithm in its header (including `none`) and a token from another issuer. See [ADR 0004](adr/0004-stateless-jwt-hs256-role-selected-at-login.md).
- **Filter chain.** Public paths: `/`, `/api/v1/auth/**`, `/swagger-ui.html`, `/swagger-ui/**`, `/v3/api-docs/**`, `/actuator/health`, `/error`. `/error` has to stay open, otherwise the body of a real error would turn into a 401. Every other actuator path needs a token.
- **Authorization.** Two layers. Role checks are declarative on controllers (`@PreAuthorize` with the `Authz` constants, giving 403). Business-scope checks live in services: a claimant sees only own claims, an officer only claims of their market, a manager everything. An out-of-scope or unknown resource returns **404**, never 403, so existence is not revealed. Spring MVC validates a request body before the method-security check runs, so a caller without the role who sends an invalid body gets the 400 validation error instead of a 403 (see [04-api-design.md](04-api-design.md), section 2).
- **Current user, on every request.** A token only proves who the caller was when it was issued. After the signature is verified, the converter asks `ActiveAccountChecker` whether the account still exists and is active; if not the request is a 401. This is one cheap query per request and is what makes a deactivation take effect immediately, on every protected endpoint. Endpoints that need the account itself call `CurrentUserService.requireCurrentUser()`, which loads it (with its market) for the services. The `role` claim is not re-read, so a role change applies at the next login.
- **Failure responses.** `RestAuthenticationEntryPoint` (401) and `RestAccessDeniedHandler` (403) write the same RFC 7807 JSON shape as every other error. An `AccessDeniedException` raised by `@PreAuthorize` inside a controller is handled explicitly by `GlobalExceptionHandler`, because the catch-all there would otherwise make it a 500.
- **Data handling.** Logs carry user ids and claim numbers only. A test and a run against the real database confirmed that no password, token, hash or request body reaches the log. No secrets are committed other than documented local-development defaults; real values come from environment variables.
- **Not built (limitations).** No login throttling or account lockout, no refresh tokens, no token revocation list (the per-request account check is the only revocation), no `jti` claim.

## 8. Observability

| Aspect | Design (Implemented) |
|---|---|
| Correlation id | `CorrelationIdFilter` runs first (`@Order(HIGHEST_PRECEDENCE)`). `X-Correlation-Id` is echoed only when it matches `^[A-Za-z0-9._-]{1,64}$`; otherwise a UUID is generated. The id goes into the logging context, onto the response header before the chain runs (so a 401 from the security filters has it too), and into every error body; the context entry is removed in a `finally` block |
| Logging | SLF4J only; the log pattern is `%5p [%X{correlationId:-}]`, so every request-time line shows the id and start-up lines show an empty one. No standard streams, no `java.util.logging`; 4xx responses are logged at DEBUG, anticipated 5xx at WARN without a stack trace, unexpected errors at ERROR with the stack trace |
| Health | `GET /actuator/health` is public; `info` is also exposed but needs a token; nothing else |
| Outbox visibility | Event status, attempts and last error are stored in `outbox_events`, so a failed delivery can be diagnosed with a query (Planned) |
| Not included | Metrics registry, tracing backend and alerting. They would be added at deployment time |

## 9. Deployment view

```mermaid
graph LR
    subgraph local["Local development"]
        ide["IntelliJ IDEA run button or mvnw spring-boot:run"]
        jvmLocal["Spring Boot app on JDK 21, port 8080"]
        pgLocal["PostgreSQL 17 on localhost:5432, database claims_db"]
        ide --> jvmLocal
        jvmLocal -->|"JDBC"| pgLocal
    end

    subgraph compose["Docker Compose for reviewers (Planned)"]
        appC["app container: eclipse-temurin 21 JRE, port 8080 published"]
        pgC["postgres:17 container, no host port"]
        vol[("named volume")]
        appC -->|"JDBC, host name postgres"| pgC
        pgC --- vol
    end

    client["Browser or API client"] -->|"HTTP /api/v1, Swagger UI"| jvmLocal
    client -->|"HTTP on APP_PORT"| appC
```

- **Local.** The developer runs the application from IntelliJ IDEA or with `mvnw`, against a PostgreSQL 17 instance whose database `claims_db` is created once by hand. Flyway creates the schema at startup; `DataInitializer` seeds demo data.
- **Docker Compose.** A multi-stage `Dockerfile` builds the jar with Maven on JDK 21 and runs it on a JRE image as a non-root user. `docker-compose.yml` starts PostgreSQL 17 (no published host port, a named volume, a `pg_isready` health check) and the application, which waits for a healthy database. Docker is **not installed on the development machine**, so these files are reviewed by reading and are not executed there.
- **Configuration.** Environment variables with safe local defaults: `SERVER_PORT`, `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, `JWT_EXPIRY_MINUTES`, `SEED_DEMO_DATA`, `SEED_SAMPLE_CLAIMS`.
- **Tests** use H2 in PostgreSQL mode and need neither PostgreSQL nor Docker.

## 10. How this would evolve

| Pressure | Step | Why the design allows it |
|---|---|---|
| A module needs independent scaling or a separate team | Split the module into its own service | Modules already talk through `service` calls, ports and events; tables are owned per module and there are no foreign keys across modules except to `app_users` and `markets`. Cross-module calls would become HTTP or events |
| Several consumers or other systems need claim events | Add a message broker | The seam is the outbox relay: replace its in-process dispatch to `DomainEventHandler` beans with a publish to a broker topic, and mark the row `PROCESSED` after the broker acknowledges. Services keep calling `DomainEventPublisher` and do not change. Consumers already deduplicate by event id |
| More than one application instance | Make the relay safe to scale out | Today the relay is a single instance. Selecting rows with `FOR UPDATE SKIP LOCKED` lets several relays share the table |
| Real policy or payment systems | Replace the adapters | `PolicyLookup` and `PaymentGateway` are ports owned by `claim`; only the adapter classes change |
| Attachments | Add a storage port in `claim` | Information responses are text-only today ([ADR 0013](adr/0013-text-only-information-responses.md)) |
| Heavier reporting load | Add caching or a read replica | Reports are computed live today, with no cache, behind the `reporting` module |
