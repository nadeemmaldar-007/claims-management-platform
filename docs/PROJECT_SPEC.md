# Claims Management Platform: Engineering Specification

This is the single source of truth for the implementation. Later work is done in stages (section 16). Where this document says "must", the implementation must comply.

---

## 1. PURPOSE, USERS AND SCOPE

**Business context.** An APAC property and casualty insurer processes **motor** and **property** claims across **six markets**. Today claimants submit by phone or email and wait without visibility; claims staff work from shared inboxes and spreadsheets with no consolidated workload view; managers cannot see outstanding claims or liability exposure in real time. This project is the **backend** that powers a replacement platform. The frontend is out of scope. The real company name must not appear anywhere in code, docs or branding.

**Personas and what each must be able to do**

| Persona | Role value | Needs |
|---|---|---|
| Claimant | CLAIMANT | report an incident (first notice of loss), track the claim and its timeline, provide additional information when asked, receive the decision and be notified |
| Claims officer (claims staff) | OFFICER | see the queue of incoming claims in their market, pick up a claim, review and assess it, request information, escalate, approve or reject within their authority, progress to settlement, see their team's workload and performance |
| Manager | MANAGER | see outstanding claims and liability exposure in real time across all markets, handle escalated claims, assign and reassign work, see all teams' workload and performance, create staff accounts, maintain policy reference data |

**In scope:** everything above, role-based security, six markets with currencies, policy validation, assessment with deductible and payout calculation, information requests, decisions, settlement through a payment gateway port, notifications, reporting, audit timeline, Flyway-managed PostgreSQL schema, tests, Docker packaging, documentation.

**Out of scope (documented as such, with reasons):** frontend, file or document uploads (information responses are text only; the design notes where a storage port would be added), a message broker (the outbox is the seam), real payment or policy-system connections (simulated behind ports), real exchange-rate feeds (static illustrative rates), email delivery (a logging channel stands in), claimant withdrawal of a claim.

## 2. ENGINEERING CONSTRAINTS

- Java 21, Spring Boot 3.5.6, Maven (wrapper), PostgreSQL 17, Flyway, springdoc-openapi 2.8.9 (Swagger UI).
- No Lombok, MapStruct, Liquibase, Testcontainers, Kafka or any broker. DTOs are Java records. Entities are plain classes with explicit getters and setters and a protected no-argument constructor.
- Tests run on H2 in PostgreSQL mode, so no Docker is needed to build or test.
- Configuration comes from environment variables with safe local defaults. No secrets in the repository other than documented local-development defaults.
- Every class has a one or two line Javadoc. Comments explain why, not what. SLF4J logging only; never log passwords, tokens or personal data beyond user ids and claim numbers.
- Never use `javax.*`, `antMatchers`, `authorizeRequests`, `WebSecurityConfigurerAdapter` or `@MockBean`.

## 3. ARCHITECTURE

**Style:** a **modular monolith**: one deployable, explicit module boundaries, enforced by automated architecture tests. Base package `com.apac.claims`.

**Modules (first-level packages)**

| Module | Responsibility |
|---|---|
| `shared` | kernel: the `ApiException` base class (carries an error code and an HTTP status; every business exception in every module extends it, so the single `GlobalExceptionHandler` in `shared.api` never needs to know feature modules), error codes, `PageResponse`, `MoneyRules`, domain-event contracts (`DomainEvent`, `DomainEventPublisher`, `DomainEventHandler`), the correlation id filter |
| `config` | `@ConfigurationProperties` records, OpenAPI configuration |
| `security` | JWT issuing and decoding, security filter chain, JSON 401 and 403 handlers. Depends on no feature module |
| `identity` | users, roles, login, registration, current-user resolution |
| `market` | the six markets: country, currency, escalation threshold |
| `policy` | policy reference data (a local snapshot standing in for the policy administration system) and the adapter that implements claim's policy lookup port |
| `claim` | the core: first notice of loss, workflow, queue and pick-up, assignment, assessment, information requests, decisions, settlement, timeline, claim numbers |
| `notification` | in-app notifications, event handlers, notification channel port and logging email adapter |
| `reporting` | workload, performance, aging, exposure, overview (read side) |
| `integration` | transactional outbox, relay, payment gateway adapter |
| `bootstrap` | composition root: the demo-data `DataInitializer` and the root redirect controller (`bootstrap.api`). No other module depends on it |

**Layers inside each feature module (packages)**
- `api`: controllers and request and response records. Depends on `service` and DTO-relevant `domain` enums only.
- `service`: use-case services (transaction boundaries, orchestration, authorization-by-scope checks) and the **ports** (interfaces) to external systems.
- `domain`: entities, enums, domain rules, Spring Data repository interfaces (a documented pragmatic choice: repositories are treated as persistence ports). No web or servlet types.
- `infrastructure`: adapters that implement ports, plus framework wiring.

**Allowed module dependencies** (anything not listed is forbidden; the graph must have **no cycles**)

| Module | May depend on |
|---|---|
| shared | none |
| config | shared |
| security | shared, config |
| identity | shared, config, security, market |
| market | shared, config |
| claim | shared, config, identity, market |
| policy | shared, config, identity, market, claim (implements claim's `PolicyLookup` port) |
| notification | shared, config, identity |
| reporting | shared, config, identity, market, claim |
| integration | shared, config, claim (implements claim's `PaymentGateway` port; dispatches shared events) |
| bootstrap | every module (it only wires and seeds); nothing depends on it |

identity depends on market because claimants and officers belong to a market (`app_users.market_id`); managers are APAC-wide and have none; market depends only on shared and config, so no cycle is created (amended in prompt 3).

Cross-module rules: a module may use another module's `service` classes and `domain` types, never its `api` or `infrastructure` packages. `claim` must not depend on `policy`, `notification`, `reporting` or `integration`; it reaches them only through ports it owns and through the shared event contracts.

**Communication**
- Synchronous: REST/JSON under `/api/v1`.
- Asynchronous: domain events written to an **outbox table in the same transaction** as the state change, then delivered by a scheduled **relay** to `DomainEventHandler` beans. Delivery is at-least-once; handlers are idempotent.
- External systems: through ports (`PolicyLookup`, `PaymentGateway`, `NotificationChannel`) with adapters in `policy.infrastructure`, `integration.payment` and `notification.infrastructure`.

## 4. DOMAIN MODEL AND DATA

Flyway migrations own the schema. Hibernate runs with `ddl-auto: validate`. All enums are stored as `VARCHAR` strings with `CHECK` constraints. Primary keys are `BIGINT GENERATED BY DEFAULT AS IDENTITY` except where noted. Money is `NUMERIC(19,2)`. Timestamps are `TIMESTAMP WITH TIME ZONE` (Java `Instant`). Dates are `DATE` (`LocalDate`). Booleans are `BOOLEAN`.

Migrations (portable SQL that runs on PostgreSQL and on H2 in PostgreSQL mode: no `TEXT`, `JSONB`, sequences, partial indexes or vendor functions):
- `V1__markets_and_identity.sql`: `markets`, `app_users`.
- `V2__policies.sql`: `policies`.
- `V3__claims.sql`: `claim_counters`, `claims`, `claim_assessments`, `information_requests`, `claim_timeline_entries`, `idempotency_keys`.
- `V4__integration.sql`: `outbox_events`, `payment_instructions`.
- `V5__notifications.sql`: `notifications`.
- `V6__reference_data.sql`: the six markets and the two claim counters.

**Tables**

`markets`: id, `code` (unique, 2 letters), `country_name`, `currency_code` (ISO 4217), `escalation_threshold`, `active`.

`app_users`: id, `user_id` (unique, lower-case, pattern `^[a-z0-9._-]{4,30}$`), `password_hash`, `full_name`, `email` (unique, lower-case), `role` (CLAIMANT, OFFICER, MANAGER), `market_id` (FK, nullable), `active`, `created_at`. Check: `role = 'MANAGER' OR market_id IS NOT NULL`.

`policies`: id, `policy_number` (unique), `market_id` (FK), `product_type` (MOTOR, PROPERTY), `holder_id` (FK to app_users), `insured_item` (vehicle registration or property address), `sum_insured`, `deductible`, `valid_from`, `valid_to`, `status` (ACTIVE, LAPSED, CANCELLED), `created_at`, `updated_at`.

`claim_counters`: `claim_type` (PK, VARCHAR), `next_value` BIGINT.

`claims`: id, `claim_number` (unique), `claim_type` (MOTOR, PROPERTY), `status`, `policy_number`, `policy_sum_insured` and `policy_deductible` (snapshots taken at submission), `claimant_id` (FK), `market_id` (FK), `assigned_officer_id` (FK, nullable), `assigned_at` (nullable), `currency_code`, `claimed_amount`, `incident_date`, `incident_location`, `description`; motor columns `vehicle_registration_number`, `vehicle_make`, `vehicle_model`, `vehicle_year` (INTEGER), `damage_type`, `police_report_number`; property columns `property_address`, `property_type`, `peril_type`; decision columns `decision_type` (APPROVED, REJECTED), `decision_reason`, `decision_remarks`, `decided_by_id` (FK), `decided_at`, `approved_amount`; settlement columns `payment_method`, `payment_reference`, `settled_amount`, `settled_at`; `version` BIGINT (optimistic lock), `created_at`, `updated_at`. Indexes: `(claimant_id)`, `(market_id, status)`, `(assigned_officer_id, status)`, `(policy_number, incident_date, claim_type)`.

`claim_assessments`: id, `claim_id` (FK), `assessed_by_id` (FK), `assessed_loss_amount`, `deductible_applied`, `recommended_payout`, `coverage_confirmed`, `recommendation` (APPROVE, REJECT, ESCALATE), `notes` (VARCHAR 2000, internal), `created_at`. Multiple assessments are allowed; the latest counts.

`information_requests`: id, `claim_id` (FK), `requested_by_id` (FK), `question` (VARCHAR 1000), `due_at`, `status` (OPEN, RESPONDED, CANCELLED), `response_text` (VARCHAR 2000), `responded_by_id` (FK), `responded_at`, `created_at`.

`claim_timeline_entries`: id, `claim_id` (FK), `entry_type` (SUBMITTED, ASSIGNED, REASSIGNED, ASSESSMENT_RECORDED, INFORMATION_REQUESTED, INFORMATION_PROVIDED, INFORMATION_REQUEST_CANCELLED, ESCALATED, APPROVED, REJECTED, SETTLED), `from_status`, `to_status`, `actor_user_id` (VARCHAR), `message` (VARCHAR 1000), `visibility` (PUBLIC, INTERNAL), `occurred_at`. ASSESSMENT_RECORDED and REASSIGNED are INTERNAL; the rest PUBLIC. Claimants only ever see PUBLIC entries.

`idempotency_keys`: id, `user_id` (FK), `idempotency_key` (VARCHAR 64), `claim_number`, `created_at`, unique `(user_id, idempotency_key)`.

`outbox_events`: `id` (VARCHAR 36, the event id, PK), `aggregate_type`, `aggregate_id`, `event_type`, `payload` (VARCHAR 8000, JSON), `occurred_at`, `status` (PENDING, PROCESSED, FAILED), `attempts` INTEGER, `next_attempt_at`, `processed_at`, `last_error` (VARCHAR 1000). Index on `(status, next_attempt_at)`.

`payment_instructions`: id, `claim_number` (unique), `amount`, `currency_code`, `payment_method`, `reference` (unique), `status`, `created_at`.

`notifications`: id, `recipient_id` (FK), `claim_number`, `notification_type`, `title`, `message` (VARCHAR 1000), `read_flag` BOOLEAN, `source_event_id` (VARCHAR 36), `created_at`, `read_at`. Unique `(source_event_id, recipient_id)` (idempotent consumption). Index on `(recipient_id, read_flag)`.

Entity mapping rules: `@Enumerated(EnumType.STRING)`; associations `LAZY`; `spring.jpa.open-in-view=false`; entities never leave the service layer (map to DTOs inside the transaction); `@Version` on `Claim`; timestamps set in `@PrePersist` / `@PreUpdate`. Avoid reserved words as names (`read`, `user`, `value`, `year`, `order`).

**Claim numbers.** `CLM-` plus number for MOTOR, `CLP-` plus number for PROPERTY, for example `CLM-10001`, `CLP-10001`. Each type has its own counter starting at 10001. `ClaimNumberGenerator.next(type)` is `@Transactional(propagation = MANDATORY)`, reads the `claim_counters` row with a **pessimistic write lock**, increments it and returns the formatted value, in the same transaction as the claim insert (so a rollback leaves no gap). The API addresses claims by claim number.

## 5. BUSINESS RULES AND WORKFLOW

**Markets** (seed through migration, in this order): IN India INR 500000.00; SG Singapore SGD 10000.00; AU Australia AUD 10000.00; JP Japan JPY 1000000.00; MY Malaysia MYR 25000.00; TH Thailand THB 200000.00 (the number is the officer escalation threshold in local currency; thresholds are roughly comparable in value). Allowed decimal places = `java.util.Currency.getInstance(code).getDefaultFractionDigits()` (JPY 0, others 2); enforce with `MoneyRules`.

**Statuses:** SUBMITTED, UNDER_REVIEW, PENDING_INFORMATION, ESCALATED, APPROVED, REJECTED, SETTLED. Open statuses: SUBMITTED, UNDER_REVIEW, PENDING_INFORMATION, ESCALATED.

**Transitions** (`ClaimWorkflow`, a pure class with unit tests)

| From | Action | To | Who |
|---|---|---|---|
| (none) | submit | SUBMITTED | CLAIMANT |
| SUBMITTED | pick-up | UNDER_REVIEW | OFFICER or MANAGER (sets assigned officer) |
| SUBMITTED | assign (manager) | UNDER_REVIEW | MANAGER (assigns the claim to a chosen officer of the claim's market) |
| UNDER_REVIEW | request information | PENDING_INFORMATION | assigned OFFICER or MANAGER |
| PENDING_INFORMATION | claimant responds, or request cancelled, when no request is left OPEN | UNDER_REVIEW | CLAIMANT or staff |
| UNDER_REVIEW | escalate | ESCALATED | assigned OFFICER |
| UNDER_REVIEW | approve | APPROVED | assigned OFFICER (within limit) or MANAGER |
| UNDER_REVIEW | reject | REJECTED | assigned OFFICER or MANAGER |
| PENDING_INFORMATION | reject | REJECTED | assigned OFFICER or MANAGER |
| ESCALATED | approve or reject | APPROVED or REJECTED | MANAGER only |
| APPROVED | settle | SETTLED | assigned OFFICER or MANAGER |

REJECTED and SETTLED are final. Reassignment by a MANAGER is allowed in UNDER_REVIEW, PENDING_INFORMATION and ESCALATED, to an active OFFICER of the claim's market; it does not change the status. A manager can therefore also assign work straight from the queue (the SUBMITTED row above), which behaves like a pick-up for the chosen officer (amended in prompt 4).

**Submission validation (first notice of loss).** The claimant's market is the claim's market and currency. Fields: `claimType`, `policyNumber` (6 to 20 letters, digits, dashes), `claimedAmount` (> 0, at most 100000000, valid scale for the currency), `incidentDate` (not in the future, not older than 5 years), `incidentLocation` (max 200), `description` (10 to 1000 characters), plus exactly one block: `motor` {vehicleRegistrationNumber, vehicleMake, vehicleModel, vehicleYear (1980 to next year), damageType (COLLISION, THEFT, FIRE, FLOOD, VANDALISM, OTHER), policeReportNumber optional} for MOTOR, or `property` {propertyAddress, propertyType (RESIDENTIAL, COMMERCIAL, INDUSTRIAL), perilType (FIRE, FLOOD, STORM, THEFT, VANDALISM, OTHER)} for PROPERTY. A wrong or missing block is a 400. Through the `PolicyLookup` port the policy must exist and belong to the claimant (`POLICY_NOT_FOUND`), be ACTIVE and cover the incident date (`POLICY_NOT_ACTIVE`), match the claim type (`POLICY_TYPE_MISMATCH`), be in the claimant's market (`POLICY_MARKET_MISMATCH`), and `claimedAmount` must not exceed the sum insured (`AMOUNT_EXCEEDS_SUM_INSURED`); all are 422. A claim for the same policy, incident date and type that is not REJECTED is a 409 `DUPLICATE_CLAIM`. The sum insured and deductible are copied onto the claim. The rule that `incidentDate` is "not in the future" is implemented as "not later than tomorrow in UTC": a claimant in a market ahead of UTC (for example AU or JP in the morning) reports an incident from today, which is still tomorrow in UTC (amended in prompt 4).

**Idempotent submission.** `POST /claims` accepts an optional `Idempotency-Key` header (8 to 64 characters). The same key from the same user returns the original claim (200, header `Idempotent-Replay: true`) instead of creating a second one.

**Pick-up.** `pick-up` works only on SUBMITTED claims, sets `assigned_officer` to the caller and `assigned_at`, and relies on the claim's version so two officers racing get one success and one 409 `CLAIM_ALREADY_ASSIGNED`.

**Assessment.** `assessedLossAmount`, `coverageConfirmed`, `recommendation`, `notes`. The system computes `deductible_applied` = the claim's deductible snapshot and `recommended_payout` = if `coverageConfirmed` then `max(0, min(assessedLossAmount, policy_sum_insured) - deductible)` else 0. Allowed in UNDER_REVIEW and ESCALATED for the assigned officer or a manager.

**Information requests.** Staff create a request (`question` 10 to 1000 characters, `dueInDays` 1 to 30, default from configuration) on an UNDER_REVIEW claim; the claim becomes PENDING_INFORMATION. The claimant responds (`responseText` 10 to 2000 characters). `overdue` is computed at read time (`OPEN` and `due_at` in the past). When no request remains OPEN the claim returns to UNDER_REVIEW.

**Approve.** Requires a latest assessment with `coverageConfirmed` true and `recommended_payout` > 0 (`ASSESSMENT_REQUIRED` 409 otherwise). `approvedAmount` is optional; default is the recommended payout; if given it must be > 0 and at most the recommended payout. An OFFICER may approve only if `approvedAmount` is at most the market's escalation threshold, else 422 `APPROVAL_LIMIT_EXCEEDED` ("escalate to a manager"); a MANAGER has no limit. Records the decision fields.

**Reject.** `reasonCode` (POLICY_INACTIVE, NOT_COVERED, FRAUD_SUSPECTED, INSUFFICIENT_EVIDENCE, DUPLICATE_CLAIM, INFORMATION_NOT_PROVIDED, OTHER) and `remarks` (10 to 1000 characters) are required.

**Settle.** Body `paymentMethod` (BANK_TRANSFER, CHEQUE) and optional `remarks`. The `PaymentGateway` port is called **before** the state change is committed. The gateway adapter is idempotent by claim number (the `payment_instructions` table), so a retry returns the same payment reference and never pays twice. If the gateway fails, the response is 502 `PAYMENT_GATEWAY_UNAVAILABLE`, the claim stays APPROVED, and no timeline entry or event is written. On success the claim becomes SETTLED with `payment_reference`, `settled_amount` = approved amount and `settled_at`.

**Scope (who sees what).** CLAIMANT: own claims only. OFFICER: claims of their own market. MANAGER: all markets. Out-of-scope or unknown resources return **404**, never 403, so existence is not revealed. Claimants never see INTERNAL timeline entries or assessments.

**Timeline and events.** Every action writes a timeline entry and, where listed, one outbox event in the same transaction: `ClaimSubmitted`, `ClaimAssigned`, `InformationRequested`, `InformationProvided`, `ClaimEscalated`, `ClaimApproved`, `ClaimRejected`, `ClaimSettled`.

## 6. SECURITY

Login uses User ID, password and a **role chosen from a dropdown**.

- `POST /api/v1/auth/login?role=CLAIMANT|OFFICER|MANAGER` with JSON body `{ "userId", "password" }`. `role` is a **required enum query parameter** because Swagger UI renders enum query parameters as a real dropdown. Authenticate, then check the account's role equals the chosen role. Every failure (unknown user, wrong password, inactive, wrong role) returns the same 401 with the message "Invalid user ID, password or role". Response: `{ "accessToken", "tokenType": "Bearer", "expiresInSeconds", "user": { "userId", "fullName", "role", "marketCode" } }`.
- `POST /api/v1/auth/register`: public self-registration that **always** creates a CLAIMANT (no role field). Body: `userId`, `password`, `fullName`, `email`, `marketCode`. 201, no token.
- `POST /api/v1/users` (MANAGER): create OFFICER or MANAGER. OFFICER requires `marketCode`; MANAGER must not have one.
- Passwords: 8 to 64 characters with at least one letter and one digit; BCrypt.
- Stateless sessions, CSRF disabled (token API), `SecurityFilterChain` with `requestMatchers`, `@EnableMethodSecurity`, `@PreAuthorize` on controller methods. Business-scope rules live in services.
- Public paths: `/`, `/api/v1/auth/**`, `/swagger-ui.html`, `/swagger-ui/**`, `/v3/api-docs/**`, `/actuator/health`, `/error`. Everything else needs a valid token.
- JWT, HS256, with this exact wiring (mistakes happen here):

```java
SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));   // com.nimbusds.jose.jwk.source.ImmutableSecret
JwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build(); // default would be RS256
encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims));      // always an explicit HS256 header
```
  Claims: `iss`, `sub` = userId, `iat`, `exp`, `role`, and `market` only when not null (`JwtClaimsSet.Builder.claim` rejects null). Map `role` to authority `ROLE_<role>` using `JwtGrantedAuthoritiesConverter` (`setAuthoritiesClaimName("role")`, `setAuthorityPrefix("ROLE_")`) in a `JwtAuthenticationConverter`.
- The secret must be at least 32 bytes; fail fast at startup with a clear message otherwise.
- JSON for security failures (`RestAuthenticationEntryPoint` 401, `RestAccessDeniedHandler` 403) in the same error shape as section 7, registered on `oauth2ResourceServer`.
- Layout that avoids a module cycle: `security` contains JWT issuing/decoding, the filter chain and the handlers and depends on no feature module; `identity.infrastructure.AppUserDetailsService` adapts users to Spring's `UserDetailsService`; `identity.service.AuthService` calls `security`'s token issuer using plain values (userId, role name, market code).
- On each request the current user is loaded from the database by `sub`; a missing or inactive user gets 401.

Status code policy: no or invalid token 401; role not allowed 403; unknown or out-of-scope resource 404; invalid request 400; business-rule violation 422; invalid state transition or duplicate or concurrent conflict 409; upstream failure 502.

## 7. REST API (`/api/v1`, JSON)

**Conventions.** Plural nouns, kebab-case, commands as sub-resources (`/claims/{n}/approve`). 201 with a `Location` header on create. Lists return `PageResponse` (`content`, `page`, `size`, `totalElements`, `totalPages`, `last`); `page` starts at 0, `size` default 10 and capped at 50; `sort=field,dir` limited to a whitelist per endpoint. Filters use `Specification` objects or separate repository methods (no `(:x is null or ...)` JPQL). Every request and response carries `X-Correlation-Id` (generated when absent, put in the logging MDC, returned in the header and in error bodies).

**Error contract.** RFC 7807 `ProblemDetail` (`application/problem+json`) with extra properties `code` (machine-readable), `timestamp`, `correlationId`, and for validation failures `errors: [{ "field", "message" }]`. `GlobalExceptionHandler` extends `ResponseEntityExceptionHandler` and handles bean validation, malformed JSON or bad enum value, missing or mismatched request parameter (400), not found (404), duplicate resource (409), invalid transition (409), optimistic lock (409), data integrity (409), business rules (422), bad credentials and authentication (401), **`AccessDeniedException` (403)**, payment gateway failure (502) and a final catch-all (500, generic message, stack trace only in the log). A catch-all handler swallows `AccessDeniedException` into a 500 unless it is handled explicitly: handle it explicitly and test it.

Error codes: `VALIDATION_FAILED`, `MALFORMED_REQUEST`, `UNAUTHENTICATED`, `ACCESS_DENIED`, `RESOURCE_NOT_FOUND`, `DUPLICATE_RESOURCE`, `CLAIM_INVALID_TRANSITION`, `CLAIM_ALREADY_ASSIGNED`, `CONCURRENT_MODIFICATION`, `DUPLICATE_CLAIM`, `POLICY_NOT_FOUND`, `POLICY_NOT_ACTIVE`, `POLICY_TYPE_MISMATCH`, `POLICY_MARKET_MISMATCH`, `AMOUNT_EXCEEDS_SUM_INSURED`, `ASSESSMENT_REQUIRED`, `APPROVAL_LIMIT_EXCEEDED`, `INFORMATION_REQUEST_NOT_OPEN`, `PAYMENT_GATEWAY_UNAVAILABLE`, `INTERNAL_ERROR`.

**Endpoint catalogue**

| Method and path | Who | Purpose |
|---|---|---|
| POST `/auth/login?role=` | public | login, returns JWT |
| POST `/auth/register` | public | self-register a claimant |
| GET `/users/me` | any logged-in | own profile |
| POST `/users` | MANAGER | create OFFICER or MANAGER |
| GET `/users?role=&marketCode=&page=&size=` | MANAGER | list users |
| GET `/markets`, GET `/markets/{code}` | any logged-in | markets, currencies, thresholds |
| GET `/policies/mine` | CLAIMANT | own policies |
| GET `/policies/{policyNumber}` | OFFICER (own market), MANAGER | policy details |
| PUT `/policies/{policyNumber}` | MANAGER | create or update a policy (stand-in for the policy-system feed) |
| POST `/claims` | CLAIMANT | submit a claim; optional `Idempotency-Key` |
| GET `/claims?status=&claimType=&marketCode=&assignedTo=&unassigned=&submittedFrom=&submittedTo=&page=&size=&sort=` | any logged-in | list, scoped by role; `assignedTo` is `me` or a userId |
| GET `/claims/queue` | OFFICER, MANAGER | SUBMITTED unassigned claims, oldest first, with `ageHours` and `slaBreached` |
| GET `/claims/{claimNumber}` | any logged-in | claim detail (scoped) |
| GET `/claims/{claimNumber}/timeline` | any logged-in | timeline (claimants see PUBLIC entries only) |
| POST `/claims/{claimNumber}/pick-up` | OFFICER, MANAGER | take a queued claim |
| POST `/claims/{claimNumber}/assign` | MANAGER | assign or reassign; body `officerUserId` |
| POST `/claims/{claimNumber}/assessments` | OFFICER, MANAGER | record an assessment |
| GET `/claims/{claimNumber}/assessments` | OFFICER, MANAGER | list assessments |
| POST `/claims/{claimNumber}/information-requests` | OFFICER, MANAGER | ask the claimant for information |
| GET `/claims/{claimNumber}/information-requests` | any logged-in | list requests (scoped) |
| POST `/claims/{claimNumber}/information-requests/{requestId}/respond` | CLAIMANT | provide the information |
| POST `/claims/{claimNumber}/information-requests/{requestId}/cancel` | OFFICER, MANAGER | cancel a request |
| POST `/claims/{claimNumber}/escalate` | OFFICER | escalate; `remarks` required |
| POST `/claims/{claimNumber}/approve` | OFFICER, MANAGER | optional `approvedAmount`, `remarks` |
| POST `/claims/{claimNumber}/reject` | OFFICER, MANAGER | `reasonCode`, `remarks` |
| POST `/claims/{claimNumber}/settle` | OFFICER, MANAGER | `paymentMethod`, optional `remarks` |
| GET `/notifications?unreadOnly=&page=&size=` | any logged-in | own notifications |
| GET `/notifications/unread-count` | any logged-in | unread count |
| POST `/notifications/{id}/read`, POST `/notifications/read-all` | any logged-in | mark as read |
| GET `/reports/overview` | OFFICER (own market), MANAGER | outstanding claims, exposure, queue and SLA summary |
| GET `/reports/exposure` | MANAGER | exposure per market and claim type |
| GET `/reports/aging` | OFFICER (own market), MANAGER | open claims by age bucket |
| GET `/reports/workload` | OFFICER (own team), MANAGER | workload per officer |
| GET `/reports/performance?from=&to=&marketCode=` | OFFICER (own team), MANAGER | performance per officer |
| GET `/actuator/health` | public | health |

`GET /claims/queue` is a literal path and must win over `/claims/{claimNumber}`; test it. `GET /` redirects to the Swagger UI (hidden from OpenAPI).

**Swagger / OpenAPI.** Swagger UI at `/swagger-ui/index.html` (and `/swagger-ui.html`), JSON at `/v3/api-docs`. `OpenApiConfig`: title "Claims Management Platform API", version 1.0.0, a description explaining the login steps, tags per module, a global `bearerAuth` HTTP bearer security scheme; login and register are marked public with an empty `@SecurityRequirements`. Every operation has `@Operation(summary, description)` and `@ApiResponse` entries for its real status codes. Every request field has `@Schema` with a description and a **valid example** so "Try it out" works first time (login examples `claimant.in` and `Claimant@123`). `POST /claims` has two named `@ExampleObject` examples (a full MOTOR one, a full PROPERTY one). The login description reads: "1) choose your role in the dropdown, 2) enter userId and password in the body, 3) Execute, 4) copy accessToken, click Authorize, paste the token only."

## 8. INTEGRATION

**Outbox.** In the same transaction as a state change, the service layer writes an `outbox_events` row through `DomainEventPublisher` (implemented in `integration`). The JSON envelope contains `eventId` (UUID), `eventType`, `aggregateType` ("Claim"), `aggregateId` (claim number), `occurredAt`, `version` (1) and a payload with claim number, claim type, status, market code, claimant user id, assigned officer user id (when set), amounts and currency (when relevant), reason code (when relevant). The **relay** (`@Scheduled`, property `app.outbox.relay.enabled`) loads PENDING rows whose `next_attempt_at` has passed, in order, dispatches each to all `DomainEventHandler` beans, then marks it PROCESSED. On failure it increments `attempts`, stores `last_error` and sets `next_attempt_at` with exponential backoff (2, 4, 8 ... seconds); after `max-attempts` the row becomes FAILED. The relay is a single instance; the design notes that scaling out needs `SKIP LOCKED`. The relay exposes a method that processes one batch so tests can call it directly.

**Notification handler** (`notification` module). Idempotent (unique `(source_event_id, recipient_id)`; a duplicate insert is ignored). Rules: `ClaimSubmitted` notifies the claimant (acknowledgement) and every active officer of the market (new claim in the queue); `ClaimAssigned` the claimant; `InformationRequested` the claimant; `InformationProvided` the assigned officer; `ClaimEscalated` every manager (and the claimant with a neutral message); `ClaimApproved`, `ClaimRejected` (with the reason) and `ClaimSettled` (with the payment reference) the claimant. Each notification is also sent through the `NotificationChannel` port; the default adapter logs one INFO line with a masked email and never fails the handler.

**Ports and adapters**

| Port (owner) | Adapter (location) | Behaviour |
|---|---|---|
| `PolicyLookup` (claim.service) | database snapshot adapter (policy.infrastructure) | returns a `PolicyView` (policy number, market code, product type, holder user id, sum insured, deductible, validity, status) |
| `PaymentGateway` (claim.service) | simulated gateway (integration.payment) | idempotent by claim number via `payment_instructions`; reference `PAY-` plus 12 upper-case hex characters; property `app.integration.payment.simulate-failure` makes it throw `PaymentGatewayException` |
| `NotificationChannel` (notification.service) | logging email channel (notification.infrastructure) | logs a masked line |

## 9. REPORTING DEFINITIONS (read side, computed live, no caching)

- Scope: OFFICER sees their own market (their team); MANAGER sees all markets and may filter by `marketCode`.
- **Exposure** per market and claim type: `openClaimedAmount` (sum of claimed amount of open claims), `approvedAwaitingPayment` (sum of approved amount of APPROVED claims), `settledTotal` (sum of settled amount), `outstandingExposure` = `openClaimedAmount` + `approvedAwaitingPayment`, each in the local currency, and the base-currency equivalent using the configured static rates. The response states `baseCurrency` and `rateSource: "STATIC_ILLUSTRATIVE"`. Totals across markets are only ever given in the base currency.
- **Aging**: open claims by age since submission in buckets `0-2 days`, `3-7 days`, `8-14 days`, `15+ days`: count and outstanding exposure.
- **Workload** per officer: `openAssigned`, counts by status (UNDER_REVIEW, PENDING_INFORMATION, ESCALATED), `approvedAwaitingSettlement`, `oldestOpenAgeHours`, `slaBreaches` (open assigned claims older than `decision-hours`); and per market: `unassignedInQueue` and `queueSlaBreaches` (SUBMITTED older than `first-review-hours`).
- **Performance** per officer for the period (`from`, `to`; default the last 30 days): `decidedCount`, `approvedCount`, `rejectedCount`, `approvalRate` (null when no decisions), `settledCount`, `avgHoursToFirstReview` (submission to assignment), `avgHoursToDecision` (submission to decision), `totalApprovedAmount` and `currency`. Compute durations in Java, not in SQL, so the code is portable.
- **Overview**: counts by status (all statuses, zero-filled) and by type, outstanding exposure per market and in the base currency, queue size, SLA breach counts, aging summary.

## 10. SEED DATA

Reference data (markets, counters) comes from migration `V6`. Demo data comes from `DataInitializer` (an `ApplicationRunner`, idempotent). `app.seed.demo-data` controls users and policies; `app.seed.sample-claims` controls the sample claims (only when no claims exist).

- Users (BCrypt hashed; email `<userId>@claims-demo.example`): manager `manager.apac` / `Manager@123` (no market); officers `officer.in`, `officer.sg`, `officer.au`, `officer.jp`, `officer.my`, `officer.th` / `Officer@123`; claimants `claimant.in`, `claimant.sg`, `claimant.au`, `claimant.jp`, `claimant.my`, `claimant.th` / `Claimant@123` (market = suffix). 13 accounts.
- Policies, per claimant: motor `MOT-<MKT>-0001` and property `PRP-<MKT>-0001` (for example `MOT-IN-0001`); sum insured motor = 3 x the market threshold, property = 10 x the threshold; deductible = 1% of the threshold rounded to the currency's decimals; valid from one year before to one year after the seeding date; status ACTIVE. One extra LAPSED policy `MOT-IN-0002` for `claimant.in` (valid in the past) for testing.
- Sample claims, created through the same service-layer classes a real user would call, in order: (1) MOTOR, `claimant.in`, policy `MOT-IN-0001`, INR 45000.00, stays SUBMITTED; (2) MOTOR, `claimant.sg`, SGD 18000.00, `officer.sg` picks up, assesses (loss 17500.00) and escalates, so ESCALATED; (3) PROPERTY (flood), `claimant.au`, AUD 6500.00, `officer.au` picks up, so UNDER_REVIEW; (4) PROPERTY (fire), `claimant.in`, INR 250000.00, `officer.in` picks up, assesses (loss 245000.00) and approves, so APPROVED. Realistic descriptions and details, with incident dates within the last 60 days. On a fresh database the next new claims are therefore `CLM-10003` and `CLP-10003`; confirm the real numbers during verification and write the real numbers in the README.

## 11. BUILD (`pom.xml`)

- Parent `org.springframework.boot:spring-boot-starter-parent:3.5.6`; `groupId` `com.apac`; `artifactId` `claims-management-platform`; `version` `1.0.0`; `java.version` 21; `maven.compiler.release` 21; UTF-8; `<finalName>claims-management-platform</finalName>`.
- Dependencies: `spring-boot-starter-web`, `-data-jpa`, `-validation`, `-security`, `-oauth2-resource-server` (Nimbus JWT; do not add jjwt), `-actuator`; `org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.9`; `org.flywaydb:flyway-core` and `org.flywaydb:flyway-database-postgresql` (versions managed by Boot; the PostgreSQL module is required for PostgreSQL 17); `org.postgresql:postgresql` (runtime). Test scope: `spring-boot-starter-test`, `spring-security-test`, `com.h2database:h2`, `com.tngtech.archunit:archunit-junit5:1.3.0`.
- Plugins: `spring-boot-maven-plugin`; `org.jacoco:jacoco-maven-plugin:0.8.12` (`prepare-agent`, `report`; the `check` rule is added in the testing prompt).
- If Maven cannot resolve something, stop and ask with the exact error. Never invent proxy or mirror settings.

## 12. CONFIGURATION

`src/main/resources/application.yml`:

```yaml
server:
  port: ${SERVER_PORT:8080}
spring:
  application:
    name: claims-management-platform
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:claims_db}
    username: ${DB_USERNAME:postgres}
    password: ${DB_PASSWORD:postgres}
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
    properties:
      hibernate:
        jdbc:
          time_zone: UTC
  flyway:
    enabled: true
    locations: classpath:db/migration
  mvc:
    problemdetails:
      enabled: true
management:
  endpoints:
    web:
      exposure:
        include: health,info
logging:
  pattern:
    level: "%5p [%X{correlationId:-}]"
app:
  security:
    jwt:
      secret: ${JWT_SECRET:dev-only-secret-change-me-0123456789abcdef}
      expiry-minutes: ${JWT_EXPIRY_MINUTES:120}
      issuer: claims-management-platform
  seed:
    demo-data: ${SEED_DEMO_DATA:true}
    sample-claims: ${SEED_SAMPLE_CLAIMS:true}
  sla:
    first-review-hours: 48
    decision-hours: 240
    information-request-default-days: 7
  reporting:
    base-currency: USD
    rate-source: STATIC_ILLUSTRATIVE
    rates:
      INR: 0.012
      SGD: 0.74
      AUD: 0.65
      JPY: 0.0067
      MYR: 0.21
      THB: 0.029
  outbox:
    relay:
      enabled: true
      poll-interval-ms: 1000
      batch-size: 50
      max-attempts: 5
  integration:
    payment:
      simulate-failure: false
  notification:
    email:
      enabled: true
springdoc:
  swagger-ui:
    path: /swagger-ui.html
    persist-authorization: true
    tags-sorter: alpha
    operations-sorter: method
```

`src/test/resources/application-test.yml`: H2 `jdbc:h2:mem:claims_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1`, username `sa`, empty password, the same Flyway migrations, `ddl-auto: validate`, `app.seed.demo-data: true`, `app.seed.sample-claims: false`, `app.outbox.relay.enabled: false`, `app.security.jwt.secret: test-only-secret-0123456789abcdef0123456789`.

Bind `app.*` with `@ConfigurationProperties` records (registered with `@ConfigurationPropertiesScan`). The rates are illustrative values for demonstration only and must be labelled that way in the API and docs.

## 13. TEST STRATEGY AND QUALITY GATES

All tests run on H2 in PostgreSQL mode (profile `test`), without Docker. No `@Transactional` on tests; create unique data per test and match claim numbers with regex (`^CLM-\d{5,}$`, `^CLP-\d{5,}$`). Log in through the real login endpoint to get tokens.

- **Unit tests:** `ClaimWorkflow` (every allowed and forbidden transition), `MoneyRules`, assessment payout calculation, SLA and aging calculations, event envelope serialisation, services with Mockito for ports (for example `PaymentGateway` failure keeps the claim APPROVED).
- **Repository tests** (`@DataJpaTest` with `@AutoConfigureTestDatabase(replace = NONE)` and profile `test`): constraints and queries, Flyway migrations validate against the entities.
- **Integration tests** (`@SpringBootTest` + `@AutoConfigureMockMvc`): end-to-end journeys through the API, security (401, 403, 404), validation and error contract, idempotency key, pick-up race (second pick-up 409), information request round trip, escalation and manager decision, settlement and gateway failure, outbox relay and idempotent notification delivery (call the relay method directly), reporting scope and numbers, OpenAPI contents (`bearerAuth`, login `role` enum with three values, `/api/v1/claims/queue`), and the contract test (the committed `src/main/resources/openapi/claims-api.yaml` and the live `/v3/api-docs` expose the same paths and methods).
- **Architecture tests (ArchUnit):** layer rules inside modules; modules free of cycles; no module depends on another module's `api` or `infrastructure`; `claim` does not depend on `policy`, `notification`, `reporting`, `integration`; `domain` does not depend on `org.springframework.web`, `jakarta.servlet`, or any `api`, `service`, `infrastructure` package; `security` depends on no feature module; no field injection; controllers are `@RestController` classes in `..api..` named `*Controller`; no standard streams, no `java.util.logging`, no generic exceptions (`GeneralCodingRules`). If a rule cannot be met cheaply, document a narrow exception in an ADR; never silently delete the rule.
- **Gates:** `mvnw -B clean verify` must pass; JaCoCo line coverage of at least 75% for the bundle (excluding DTO records, `config`, the main class, `package-info`), enforced by a `check` rule; at least 80 test methods in total.
- **Smoke script:** `scripts/SmokeTest.java` (single-file Java, run with `java scripts/SmokeTest.java [baseUrl]`) exercises a live instance: health, login for each role, wrong role 401, claim submission and idempotent replay, pick-up, assessment, information request and response, escalation, approval limit 422, manager approval, settlement, notifications, reports, scope 404, validation 400. It prints PASS or FAIL per step and exits 1 on any failure. It must work against the local app and Docker Compose.

## 14. PACKAGING AND DOCUMENTATION DELIVERABLES

- `Dockerfile` (multi-stage: `maven:3.9-eclipse-temurin-21` to build with `mvn -B -DskipTests package`, `eclipse-temurin:21-jre` to run, non-root user via `groupadd -r app && useradd -r -g app app`, jar copied to `/app/app.jar`, `EXPOSE 8080`, `ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar /app/app.jar"]`). `docker-compose.yml` (no `version:` key): `postgres:17` with `POSTGRES_DB=claims_db`, a named volume, a `pg_isready` healthcheck and **no published host port**; the `app` service builds the Dockerfile, sets `DB_HOST=postgres` and the other `DB_*` variables, publishes `${APP_PORT:-8080}:8080` and waits for the healthy database. `.dockerignore`, `.env.example`. Docker is not available on the dev machine: review these files line by line and say honestly in the AI journal that they were not executed there.
- `README.md` with these H2 sections **in this order**: `Overview` (pitch, features, tech stack, a criteria-to-evidence table mapping the six marking areas to files and docs, the **links table**: Swagger UI, `/swagger-ui.html`, OpenAPI JSON, health, GitHub repository as `https://github.com/<your-username>/claims-management-platform`, `NOTES.md`, design docs, ADR folder, `prompts/`, AI journal, interview guide); `Architecture` (Mermaid diagrams: component and module graph, ER, claim state, sequence for submission through outbox to notification, local versus Docker deployment; layer rules; the module dependency table; trade-offs); `Setup` (prerequisites JDK 21 and PostgreSQL 17, the one-time `CREATE DATABASE claims_db;` step with the pgAdmin click path, three ways to run: IntelliJ run button, `mvnw`, Docker Compose; the 13 demo accounts; configuration variables; troubleshooting: database missing, port 8080 busy, wrong JDK, 401 versus 403 versus 404, Docker not installed; how to push to GitHub with placeholders); `API Reference` (Swagger walkthrough using real claim numbers observed, markets table, claim number rules, workflow and business rules, endpoint summary, error contract, link to `src/main/resources/openapi/claims-api.yaml`); `Testing` (how to run, test pyramid, quality gates, smoke script); then `AI Journal and Prompts` (how AI was used, link to `.claude/ai-journal.md` and the `prompts/` folder).
- `NOTES.md` (repository root): decisions and assumptions, as described in the design documents stage.
- `prompts/`: every prompt file used, verbatim, named `prompt-01-setup-spec-design-scaffold.md`, `prompt-02-data-layer.md`, `prompt-03-security-and-identity.md`, `prompt-04-claims-core.md`, `prompt-05-integration.md`, `prompt-06-reporting.md`, `prompt-07-packaging-and-finish.md`. Any extra instruction the developer types that changes the work (for example a fix request) is saved as `prompts/prompt-0N-extra-<topic>.md` and listed in the AI journal. A `prompts/README.md` lists the prompts in order with one line each.
- `src/main/resources/openapi/claims-api.yaml`: the API contract, exported from the running application's `/v3/api-docs.yaml` during final verification and committed. A test asserts that every path and method in the committed contract exists in the live OpenAPI document and vice versa.
- `.claude/ai-journal.md`: honest and specific; never invent anything; stage log with real commands, errors and fixes; decisions with reasons; what the AI got wrong or retried; what the developer must review personally; limitations (Docker files not executed on the dev machine, simulated external systems, static rates, single relay instance); lessons learned.
- `docs/INTERVIEW_GUIDE.md`: a 2-minute pitch; a 5-minute Swagger demo script; an architecture walkthrough; and 15 likely client questions with honest, implementation-accurate answers (module boundaries and how they are enforced, outbox versus broker, idempotency, concurrency on pick-up and claim numbers, why 404 not 403, security design, currency handling, how reports are computed, what is simulated, what you would build next).
- `docs/` design documents and ADRs written in prompt 1 are reconciled with the final code in the last prompt.

## 15. ARCHITECTURE DECISION RECORDS TO WRITE (prompt 1)

0001 Modular monolith with enforced module boundaries; 0002 Pragmatic hexagonal layering (`api`, `service`, `domain`, `infrastructure`) enforced by ArchUnit; 0003 PostgreSQL with Flyway migrations and Hibernate `validate`; 0004 Stateless JWT (HS256) with the role selected at login; 0005 Transactional outbox for domain events and no message broker in this version; 0006 At-least-once delivery with idempotent consumers; 0007 Claim numbers from row-locked counters; 0008 Ports and adapters for policy, payment and notification integrations; 0009 Optimistic locking for pick-up and transitions, and an idempotency key for submission; 0010 Static illustrative exchange rates for base-currency exposure; 0011 API versioning, error contract (RFC 7807 with codes) and correlation ids; 0012 Test strategy: H2 in PostgreSQL mode, so Docker is not needed to build or test; 0013 Text-only information responses, file storage deferred.

## 16. DELIVERY STAGES (seven prompts)

1. Setup, specification, design documents, scaffold (this prompt).
2. Data layer: Flyway migrations V1 to V6, entities, repositories, shared kernel, market and identity domain, policy domain, seed initializer, repository tests, application context test.
3. Security and identity: JWT, security chain, handlers, correlation id filter, global exception handler, auth and user endpoints, OpenAPI configuration, root redirect, tests.
4. Claims core: workflow, claim numbers, submission with policy lookup, idempotency, queue, pick-up, assignment, assessment, information requests, decisions, timeline, the policy endpoints (`/policies/...`), the write side of the outbox (`integration.service.OutboxDomainEventPublisher`), tests, first ArchUnit rules.
5. Integration: the outbox relay, notification module, payment gateway adapter, settlement, tests.
6. Reporting: overview, exposure, aging, workload, performance, tests; complete ArchUnit rules; JaCoCo gate; sample claims.
7. Packaging and finish: Docker files, smoke script, OpenAPI contract export and its test, README (required headings), NOTES.md, interview guide, `prompts/README.md`, document reconciliation, final verification against a running app.

Prompt files are stored as `prompts/prompt-0N-<topic>.md` (see section 14). Each later prompt starts with: "Read `docs/PROJECT_SPEC.md` and `.claude/ai-journal.md`, then carry out every instruction in `prompts/prompt-0N-<topic>.md`."

**Definition of done for every stage:** `mvnw -B clean verify` is green with JDK 21; the stage's tests exist and pass; the traceability matrix and the AI journal stage log are updated; one or more focused commits with conventional messages; `git status` is clean.
