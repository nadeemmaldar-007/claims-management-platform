# PROMPT 5 OF 6: INTEGRATION, NOTIFICATIONS, SETTLEMENT, REPORTING, SAMPLE CLAIMS AND QUALITY GATES

You are the same senior Java / Spring Boot engineer continuing the **claims-management-platform** project inside IntelliJ IDEA on Windows 11. Before anything else, read `docs/PROJECT_SPEC.md` (sections 3, 5, 7, 8, 9, 10, 13), `.claude/ai-journal.md`, `NOTES.md` and `docs/04-api-design.md`. Then carry out this prompt.

Prompt 4 built the claims core and the write side of the outbox. This prompt completes the product: the outbox relay, the notification module, the payment gateway and settlement, the five reports, the four sample claims, the remaining architecture rules and the coverage gate. **Prompt 6 (the last) is Docker, README and final checks.**

Working rules:
- Keep a task list. Work through the stages in order without waiting for approval. Ask the developer only in the situations in section 11.
- **Do exactly what this prompt lists. Do not run mutation testing, extra experiments, repeated full builds or long polling loops.** Run the full `mvnw -B clean verify` only at the end of stages 3, 4 and 6 and once in stage 8; in between, compile (`mvnw -B clean compile`) and run only the test classes you just wrote (`-Dtest=ClassName`).
- Never claim something works unless you ran it and saw the real output. Never weaken, skip or delete a test to make a build pass.
- Use ONLY the Maven Wrapper with `-B` and `JAVA_HOME` set to the JDK 21 folder recorded in the journal (check `java -version` prints 21 first). The system `java` is JDK 25: never use it.
- Docker is not installed (never run `docker`); Kafka is not used; never add a remote or push; never change global git config; PostgreSQL 17 at `localhost:5432`, user `postgres`, password `postgres`, database `claims_db`.
- Stack unchanged: Java 21, Spring Boot 3.5.6, no Lombok, DTOs as records, `jakarta.*` only, constructor injection, Javadoc on every class, SLF4J only, injected `Clock` for every "now".
- Flyway V1 to V6 are applied and **locked**; add `V7__...` only if the schema is really wrong.
- Reuse what exists. Read the existing code before writing new code.

---

## 1. STAGE 0: SANITY CHECK AND SPEC AMENDMENT

1. Save this prompt as `prompts/prompt-05-integration-and-reporting.md` (if not there) and add its line to `prompts/README.md`.
2. `git status` clean, `git log --oneline` shows the earlier commits, `mvnw -B clean verify` is BUILD SUCCESS. Report the real test count.
3. Amend `docs/PROJECT_SPEC.md` (change only these places and record the decision in `NOTES.md` and the journal): section 16 now has **six** prompts: 5 = integration, notifications, settlement, reporting, sample claims, ArchUnit completion and the JaCoCo gate; 6 = packaging and finish (Docker files, OpenAPI contract export and its test, README, interview guide, document reconciliation, final verification on a fresh database). In section 14 the prompt file names become `prompt-05-integration-and-reporting.md` and `prompt-06-packaging-and-finish.md`.
4. Commit: `docs: merge integration and reporting into prompt 5 and finish in prompt 6`.

## 2. STAGE 1: OUTBOX RELAY (commit)

In `integration` (depends only on `shared`, `config`, `claim` where a claim port is needed):

- Move the event-type name constants (`ClaimSubmitted` ... `ClaimSettled`) to `shared.event.EventTypes` if they live in `claim.service`; `notification` must be able to use them without depending on `claim`. Update `ClaimEvents` to use them.
- `integration.service.OutboxRelay` (a normal bean, always present): `int processBatch()` loads up to `app.outbox.relay.batch-size` rows with status PENDING and `next_attempt_at <= now` ordered by `occurred_at` then id, and handles **each event in its own transaction** (`TransactionTemplate`, `REQUIRES_NEW`): read the JSON envelope back into a `DomainEvent`, call every `DomainEventHandler` bean, then mark PROCESSED with `processed_at`. If a handler throws, roll that event's transaction back and, in a new transaction, increment `attempts`, store `last_error` (truncated to 1000 characters, never the payload), set `next_attempt_at = now + 2^attempts seconds` (2, 4, 8, 16, ...), and set status FAILED once `attempts >= max-attempts`. One failing event never stops the rest of the batch. Returns the number of events processed successfully. Log one INFO line per processed event (type, claim number) and one WARN per failure.
- `integration.infrastructure.OutboxRelayScheduler`: `@Scheduled(fixedDelayString = "${app.outbox.relay.poll-interval-ms:1000}")` calling `processBatch()` and catching every exception so the schedule never dies; the class and `@EnableScheduling` are active only when `app.outbox.relay.enabled` is true (`@ConditionalOnProperty`, default true). With the property false (tests) nothing runs by itself.
- Document in the class Javadoc: single-instance relay; scaling out needs `SELECT ... FOR UPDATE SKIP LOCKED`; delivery is at-least-once; ordering is per batch, not guaranteed after a retry.

Tests (`OutboxRelayTest`, integration style, profile `test`): tests share one database, so other tests' PENDING events exist: loop `processBatch()` until it returns 0 (maximum 50 loops) and assert only on your own events. Cover: a PENDING event reaches the handler and becomes PROCESSED; a handler registered through a `@TestConfiguration` that throws only for one specific aggregate id gives attempts 1, a stored error, a future `next_attempt_at` and status PENDING; after the attempts reach the maximum the status is FAILED; an event whose `next_attempt_at` is in the future is skipped; a failing event does not stop the next one; processing twice (reset the row to PENDING) calls the handler twice and the idempotent notification handler (stage 2) produces no duplicates.

Commit: `feat(integration): add outbox relay with retry and backoff`.

## 3. STAGE 2: NOTIFICATIONS (commit)

In `notification` (may depend on `shared`, `config`, `identity`; never on `claim`):

- `service.NotificationChannel` (port: `void send(Notification notification, AppUser recipient)`), `infrastructure.LoggingEmailChannel` (logs one INFO line `Email notification to <masked email> type=<t> claim=<n>` where the email is masked like `a***@claims-demo.example`; active when `app.notification.email.enabled` is true; **never throws**, it catches and logs).
- `service.NotificationService` and `service.NotificationEventHandler implements DomainEventHandler`, built **only from the event payload** (no claim lookups): rules of spec section 8. `ClaimSubmitted`: claimant (acknowledgement) and every active officer of the market (new claim in the queue). `ClaimAssigned`: claimant. `InformationRequested`: claimant. `InformationProvided`: the assigned officer. `ClaimEscalated`: every active manager and the claimant (neutral message). `ClaimApproved`: claimant (with the approved amount and currency). `ClaimRejected`: claimant (with the reason code in plain words). `ClaimSettled`: claimant (with the payment reference). Titles and messages are short, in plain English, and contain no internal assessment text. Idempotent: the unique `(source_event_id, recipient_id)` constraint, a duplicate is ignored (check first with `existsBy...`, and treat a `DataIntegrityViolationException` as "already delivered"). Each created notification is also passed to the channel.
- `api.NotificationController` (any logged-in user, own notifications only): `GET /api/v1/notifications?unreadOnly=&page=&size=` (newest first, paging rules as elsewhere), `GET /api/v1/notifications/unread-count` (`{ "unreadCount": n }`), `POST /api/v1/notifications/{id}/read` (404 for someone else's or unknown id), `POST /api/v1/notifications/read-all` (`{ "updated": n }`). `NotificationResponse(id, claimNumber, type, title, message, read, createdAt, readAt)`. `@Tag("Notifications")`, `@Operation`, `@ApiResponse`.

Tests: handler rules per event type (recipients and messages, using handler calls with hand-built events), idempotency (same event twice, one notification per recipient), channel failure does not fail the handler, masked email, endpoints (own only, unread-only filter, unread count, mark read, read-all, someone else's id is 404, no token 401), and one end-to-end test: submit, pick-up and approve a claim through the API, run the relay loop, and check the claimant's and the officer's notifications.

Commit: `feat(notification): add event-driven notifications, channel port and endpoints`.

## 4. STAGE 3: PAYMENT GATEWAY AND SETTLEMENT (commit, then full verify)

- If not present, add the port `claim.service.PaymentGateway` (`PaymentReceipt pay(PaymentRequest request)`; `PaymentRequest(claimNumber, amount, currencyCode, paymentMethod)`; `PaymentReceipt(reference)`) and `claim.service.PaymentGatewayException extends ApiException` (502, `PAYMENT_GATEWAY_UNAVAILABLE`).
- `integration.payment.SimulatedPaymentGateway implements PaymentGateway`: idempotent by claim number through `payment_instructions`: if a row for the claim number exists return its reference; otherwise, when `app.integration.payment.simulate-failure` is true throw `PaymentGatewayException`; else insert a row with reference `PAY-` plus 12 upper-case hex characters (from a UUID), status `PAID`, and return it. Run the adapter with `Propagation.REQUIRES_NEW` so the payment record survives a rollback of the claim transaction (it models an external system); on a unique-constraint race, re-read and return the existing reference. Log one INFO line with claim number and reference.
- `claim.service` settlement use case and `POST /api/v1/claims/{claimNumber}/settle` (`Authz.STAFF`): body `SettleClaimRequest(paymentMethod BANK_TRANSFER|CHEQUE, remarks optional max 1000)`. Rules of spec section 5: APPROVED only (else 409 `CLAIM_INVALID_TRANSITION`), assigned officer or any manager (an officer who is not assigned gets 403), the gateway is called **before** the state change is committed, on `PaymentGatewayException` the transaction rolls back (claim stays APPROVED, no timeline entry, no event), on success set `payment_method`, `payment_reference`, `settled_amount` = approved amount, `settled_at`, status SETTLED, timeline SETTLED (PUBLIC), event `ClaimSettled` with the payment reference in the payload. Fill the `settlement` block in `ClaimDetailResponse`. Out-of-scope claims are 404 as everywhere. 200 with the updated claim.

Tests: `SimulatedPaymentGatewayTest` (reference format, same claim number twice gives the same reference and one row, failure flag throws and writes nothing), settlement happy path, 409 on a claim that is not APPROVED, 409 on a second settle, 403 for a non-assigned officer, 404 out of scope, 403 for the claimant, **gateway failure keeps the claim APPROVED with no timeline entry and no `ClaimSettled` event and returns 502 `PAYMENT_GATEWAY_UNAVAILABLE`** (use a `@TestConfiguration` that replaces the gateway with one that throws, or a Mockito-based service test), retry after a failure succeeds and the reference matches the stored payment instruction, `settled_amount` equals the approved amount.

Run the full `mvnw -B clean verify`. Commit: `feat(claim): add settlement through the payment gateway port with a simulated adapter`.

## 5. STAGE 4: REPORTING (commit, then full verify)

In `reporting` (may depend on `shared`, `config`, `identity`, `market`, `claim`; read side only, no writes, no caching, all computed live). Follow spec section 9 exactly; compute durations and aggregates **in Java** (load the needed claims with `Specification` queries and aggregate in memory; document in the class Javadoc that a read model or SQL aggregation is the next step at larger scale). Use the injected `Clock` and `app.sla`, `app.reporting` properties (fail fast at startup if a market currency has no configured rate).

- Scope: OFFICER sees their own market; MANAGER sees all markets and may pass `marketCode`; an OFFICER passing a different `marketCode` gets 404-style empty scope (apply the filter on top of the scope, so the result is empty, or use 403 if you find it clearer: choose one, test it and document it in `NOTES.md`).
- Endpoints (all `@Tag("Reports")`, `@Operation`, `@ApiResponse`, `@Schema` examples):
  - `GET /api/v1/reports/overview` (OFFICER, MANAGER): counts by status (all seven statuses, zero-filled) and by claim type, outstanding exposure per market and in the base currency, queue size, queue SLA breach count, open-claim SLA breach count, aging summary.
  - `GET /api/v1/reports/exposure` (MANAGER): per market and claim type `openClaimedAmount`, `approvedAwaitingPayment`, `settledTotal`, `outstandingExposure` (= open + approved awaiting payment) in the local currency and the base-currency equivalents (amount x configured rate, scale 2, HALF_UP), plus `baseCurrency`, `rateSource` = `STATIC_ILLUSTRATIVE` and a total row in the base currency only. Totals across markets are never added in local currencies.
  - `GET /api/v1/reports/aging` (OFFICER, MANAGER): open claims by age since submission in buckets `0-2 days`, `3-7 days`, `8-14 days`, `15+ days`: count and outstanding exposure (base currency; local currency per market when the scope is one market).
  - `GET /api/v1/reports/workload` (OFFICER own team, MANAGER): per officer (every active officer in scope, including those with no claims): `openAssigned`, counts by UNDER_REVIEW, PENDING_INFORMATION, ESCALATED, `approvedAwaitingSettlement`, `oldestOpenAgeHours`, `slaBreaches` (open assigned claims older than `decision-hours`); per market: `unassignedInQueue`, `queueSlaBreaches` (SUBMITTED older than `first-review-hours`).
  - `GET /api/v1/reports/performance?from=&to=&marketCode=` (OFFICER own team, MANAGER): per officer for the period (dates inclusive in UTC, default the last 30 days, `from` after `to` is 400): `decidedCount`, `approvedCount`, `rejectedCount`, `approvalRate` (null when no decisions), `settledCount`, `avgHoursToFirstReview` (submission to assignment, over claims assigned to the officer in the period), `avgHoursToDecision` (submission to decision, over claims the officer decided in the period), `totalApprovedAmount` and `currency`. Only OFFICER accounts appear as rows. State each definition in the field `@Schema` description.
- Claimants get 403 on every report endpoint.

Tests: pure-calculation unit tests with hand-built data and a controlled `Clock` (aging buckets at the boundaries 2/3, 7/8, 14/15 days, exposure arithmetic and base-currency conversion, approval rate with no decisions, averages), plus integration tests that create **their own market-specific data** (tests share one database: use claims you created, assert on deltas or on your own officer's row, never on global totals): officer scope (own market only), manager sees all and can filter, claimant 403, officer 403 on exposure, `rateSource` is `STATIC_ILLUSTRATIVE`, a claim moved through submit, pick-up, assessment, approval, settlement shows up correctly in exposure and in the officer's workload and performance rows, zero-filled status counts, `from` after `to` is 400.

Run the full `mvnw -B clean verify`. Commit: `feat(reporting): add overview, exposure, aging, workload and performance reports`.

## 6. STAGE 5: SAMPLE CLAIMS (commit)

Extend `bootstrap.DataInitializer` (or add `bootstrap.SampleClaimSeeder`, an `ApplicationRunner` ordered after the user and policy seeding) per spec section 10: runs only when `app.seed.sample-claims` is true **and no claims exist**; creates the four sample claims **through the same service-layer classes a real user calls** (acting as the seeded users, not by inserting rows): (1) MOTOR `claimant.in`, policy `MOT-IN-0001`, INR 45000.00, stays SUBMITTED; (2) MOTOR `claimant.sg`, `MOT-SG-0001`, SGD 18000.00, picked up by `officer.sg`, assessed (loss 17500.00, coverage confirmed, recommendation ESCALATE) and escalated, so ESCALATED; (3) PROPERTY (flood) `claimant.au`, `PRP-AU-0001`, AUD 6500.00, picked up by `officer.au`, so UNDER_REVIEW; (4) PROPERTY (fire) `claimant.in`, `PRP-IN-0001`, INR 250000.00, picked up by `officer.in`, assessed (loss 245000.00, coverage confirmed, recommendation APPROVE) and approved, so APPROVED. Realistic descriptions and details (vehicle make and model, address, police report number), incident dates within the last 60 days (use the `Clock`). A failure aborts startup with a clear message. Idempotent (a second start creates nothing). The test profile keeps it off.

Tests: a dedicated `@SpringBootTest` with a property override that enables it on an empty database context (use `@TestPropertySource(properties = "app.seed.sample-claims=true")` and a fresh in-memory database name, for example `jdbc:h2:mem:sample_seed;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1`) asserts four claims in the four states, the expected claim numbers match `CLM-`/`CLP-` patterns, and that a second run creates nothing.

Commit: `feat(bootstrap): seed four sample claims through the service layer`.

## 7. STAGE 6: COMPLETE ARCHITECTURE RULES AND THE COVERAGE GATE (commit, then full verify)

1. Extend the ArchUnit tests from prompt 4 (do not remove any rule): `notification` does not depend on `claim`; `reporting` does not depend on `notification`, `integration`, `policy`; no class in `reporting` calls a repository `save`, `delete` or `flush` method; `@Entity` classes reside only in `..domain..`; Spring Data repositories reside only in `..domain..`; `@RestController` only in `..api..`; port interfaces named `*Gateway`, `*Lookup`, `*Channel` reside in `..service..` and are implemented only in `..infrastructure..`, `..payment..` or another module's `..infrastructure..`; `@Scheduled` methods only in `..infrastructure..`; every `@PreAuthorize` value comes from `Authz` (check through the class `Authz` constants being the only strings used: a simple rule is that controller methods in `..api..` that are mapped (`@GetMapping`, `@PostMapping`, `@PutMapping`) and are not under the public paths carry `@PreAuthorize`; list the public ones explicitly by class name). If a rule cannot be met cheaply, document a narrow named exception in `docs/adr/0014-...` (or the next free number) and tell the developer; never silently loosen a rule.
2. JaCoCo: add the `check` execution to `pom.xml`: bundle line coverage minimum **0.75**, excluding DTO packages (`**/api/dto/**`), `**/config/**`, the main application class and `**/package-info*`. Run `mvnw -B clean verify`. If coverage is below the gate, **add real tests for the least-covered classes** (read `target/site/jacoco/jacoco.csv` to find them), never lower the number and never exclude more code than listed. Report the real line coverage.
3. Confirm the total test count is at least 80 (it should be far above).

Run the full `mvnw -B clean verify`. Commit: `test: complete architecture rules and enforce the coverage gate`.

## 8. STAGE 7: SMOKE SCRIPT AND REAL-DATABASE VERIFICATION (commit)

1. Extend `scripts/SmokeTest.java` (keep the harness, keep it readable, group steps under headings). New steps: a fresh claim taken through submit, pick-up, assessment and manager approval, then `POST /settle` as the manager 200 with a `settlement` block whose reference matches `^PAY-[0-9A-F]{12}$`, a second settle 409, the claimant's settle 403; **notifications**: after the actions, poll the claimant's `GET /notifications` every second for up to 15 seconds until a notification for that claim number with an approved or settled type appears (the relay runs every second) then PASS, else FAIL; unread count greater than 0; mark one as read 200; read-all 200 and unread count 0; another user's notification id 404; **reports**: as the manager `overview` 200, `exposure` 200 with `STATIC_ILLUSTRATIVE`, `aging` 200, `workload` 200, `performance` 200 and `performance?from=2099-01-01&to=2098-01-01` 400; as `officer.in` `overview` 200, `exposure` 403; as a claimant `overview` 403. The script exits 1 on any failure and works when run twice in a row.
2. Build the jar and start the application against the real `claims_db` with JDK 21 (log to `target\verify-app.log`, remember the process id; if port 8080 is busy use `SERVER_PORT=8081` and tell the developer). Wait up to 120 seconds for `Started ClaimsManagementPlatformApplication`. The relay will process the PENDING events left by earlier smoke runs: that is expected.
3. Run `java scripts/SmokeTest.java http://localhost:<port>` twice. Every step must PASS both times. Fix the code (never the expectation) until it does. Check the log: no `ERROR` lines, no stack traces except deliberately provoked ones at WARN or below, relay INFO lines present, no password, token or claim description logged.
4. Stop the application by process id and confirm the port is free. Do not delete the data in `claims_db`: the developer resets the database before the final verification in prompt 6.

Commit: `build: extend smoke script with settlement, notifications and reports`.

## 9. STAGE 8: DOCUMENTS (minimal, commit, then final verify)

Update only what changed: `docs/04-api-design.md` (settle, notifications and reports endpoints **Implemented** with real status codes; the reporting scope decision), `docs/requirements-traceability.md` (rows for settlement, notifications, relay, reports, sample claims, gates with the real test class names), `docs/02-architecture.md` (relay, notification flow, payment port, reporting read side), ADRs 0005, 0006, 0008, 0010 (note anything that changed), `NOTES.md` (new assumptions: relay semantics, `REQUIRES_NEW` payment record, rates are illustrative, in-memory aggregation), `.claude/ai-journal.md` (prompt 5 entries: real commands, real numbers, problems and fixes, limitations), `prompts/README.md`. Prompt 6 does the full reconciliation, README and interview guide.

Run `mvnw -B clean verify` once more. Commit: `docs: record integration, settlement and reporting in the design documents and journal`. `git status` must be clean.

## 10. KNOWN PITFALLS FOR THIS PROMPT

1. A handler that throws inside the per-event transaction must roll back only that event; mark the failure in a **new** transaction (`REQUIRES_NEW`), otherwise the attempt counter is rolled back with it.
2. `@Scheduled` needs `@EnableScheduling`; keep it conditional on the relay property so tests are not disturbed by background threads.
3. Tests share one database, and the relay processes **all** pending events: always loop until empty and assert only on your own data.
4. `ObjectMapper` must write instants as ISO-8601 text and read them back (the Spring-managed mapper does; do not create a bare one).
5. The payment adapter needs its own transaction (`REQUIRES_NEW`), so a settle rollback does not erase the payment instruction. Check the order of side effects in the settle method: gateway call first, then state change, timeline, event.
6. `notification` must not import `claim`. Build notification text from the event payload only.
7. Exposure sums use `BigDecimal` with `compareTo`; convert to the base currency per row, then sum; round once at the end of each conversion (HALF_UP).
8. Aging uses `Duration.between(createdAt, now).toDays()`; boundaries are 0-2, 3-7, 8-14, 15+ whole days.
9. JaCoCo: the `check` goal is bound to `verify`; exclusions are patterns on class files (`**/api/dto/**`, etc.).
10. Do not use `curl` (PowerShell alias); the smoke script covers live checks.
11. Every Maven command needs `JAVA_HOME` = JDK 21.

## 11. STOP AND ASK (the only reasons to pause)

- PostgreSQL refuses the connection or password (show the exact error), or `claims_db` is missing.
- Dependencies cannot be downloaded (show the exact error; never invent proxy settings).
- The same failure persists after three real fix attempts: stop and report what you tried.

If you run out of room, finish the current stage, commit, update the stage log in `.claude/ai-journal.md`, and tell the developer which stage is next. When the developer replies "continue", read the stage log and resume.

## 12. FINAL REPORT FOR THIS PROMPT (short, plain language)

Report: files created (grouped by module), the commits (`git log --oneline`), the real `mvnw -B clean verify` result with test counts and JaCoCo line coverage, the real smoke script output (PASS or FAIL per step, both runs), the number of ArchUnit rules and their result, any deviation and why, any decision the developer must take, and anything the developer must do. End with: "Prompt 5 complete. Ready for prompt 6 (packaging and finish)."

Begin with Stage 0 now.
