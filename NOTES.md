# Notes: Decisions and Assumptions

Status: **final**, reconciled with the finished code in prompt 6. It was written at design stage (prompt 1) and updated in prompts 2 to 5. Full detail is in [docs/](docs/): [PLAN.md](docs/PLAN.md), [architecture](docs/02-architecture.md) and the [decision records](docs/adr/).

## Key assumptions

- **One currency per market.** A claim uses its claimant's market and currency; there are no cross-currency claims. Decimal places follow the currency (JPY 0, the others 2).
- **Calendar-hour SLAs.** First review within 48 hours of submission, decision within 240 hours; no business-day or holiday calendar.
- **Static, illustrative exchange rates.** Used only to show totals across markets in USD, labelled `STATIC_ILLUSTRATIVE` ([ADR 0010](docs/adr/0010-static-illustrative-exchange-rates.md)).
- **Simulated external systems.** The policy system, the payment gateway and email delivery are simulated behind ports.
- **Text-only information responses.** Claimants answer information requests with text; there are no attachments ([ADR 0013](docs/adr/0013-text-only-information-responses.md)).
- **A team is a market.** An officer sees the claims, workload and performance of their own market.
- **Escalation threshold is the officer's approval limit,** in local currency, roughly comparable across markets.
- **Policies belong to the claimant.** A claim is valid only against the claimant's own, active, matching-type policy in their market.
- **The role is chosen at login, as a query parameter.** `POST /auth/login?role=` makes Swagger UI show a real dropdown. The role stored on the account is authoritative; the parameter is only checked against it, so it adds a convenience and not a security factor ([ADR 0004](docs/adr/0004-stateless-jwt-hs256-role-selected-at-login.md)).
- **One generic login failure.** Unknown user, wrong password, inactive account and wrong role all return the same 401 and the same message, so a caller cannot tell which accounts exist.
- **The token's user is checked on every request.** A deleted or deactivated user is refused at once, on every protected endpoint; the role in the token is trusted until it expires.
- **All time comes from a `Clock` bean,** so tests can control it.
- **A manager can assign straight from the queue** (spec amendment, prompt 4). `assign` on a SUBMITTED claim behaves like a pick-up for the chosen officer; on UNDER_REVIEW, PENDING_INFORMATION and ESCALATED it is a reassignment that keeps the status.
- **An incident date may be at most tomorrow in UTC** (spec amendment, prompt 4). A claimant in a market ahead of UTC reports an incident from "today", which is still tomorrow in UTC, so "not in the future" would wrongly refuse it.
- **Delivery stages moved** (spec amendment, prompt 4): the policy endpoints and the write side of the outbox were built in prompt 4; the relay, notifications, the payment gateway and settlement followed in prompt 5.
- **Six prompts, not seven** (spec amendment, prompt 5): integration, notifications, settlement, reporting, sample claims, the remaining architecture rules, the coverage gate and the smoke script extension are one prompt (5); packaging and finish (Docker files, the OpenAPI contract export and its test, README, interview guide, document reconciliation, the final verification on a fresh database) is the last (6). The prompt file names in the specification are `prompt-05-integration-and-reporting.md` and `prompt-06-packaging-and-finish.md`. Older notes that said "prompt 6" for reporting or "prompt 7" for packaging were reconciled in prompt 6.
- **403 for an officer who sees a claim but is not its assignee, 404 for a claim outside the scope** (prompt 4). Assessing, asking for information, cancelling a request, escalating, approving and rejecting need the assigned officer or a manager; an officer on an ESCALATED claim is refused with a message that says so. A manager is never refused for not being the assignee.
- **Assigning an unsuitable officer is a 422, not a 404** (prompt 4): an unknown, inactive, non-officer or other-market `officerUserId` gets one message for every case, so the answer does not reveal which user ids exist. The code is `VALIDATION_FAILED`, as the list of 20 error codes has no dedicated one; an `approvedAmount` above the recommended payout is answered the same way.
- **Idempotent submission and its race** (prompt 4): the same key from the same user returns the original claim (200, `Idempotent-Replay: true`) without comparing the body. If two identical requests arrive at the same moment, the second fails on the unique key, rolls back with its claim number and gets a 409; a retry then receives the replay. Only one claim exists.
- **A claimant never sees a staff user id** (prompt 4): the assigned officer is left out of the claim, "Claims team" replaces the actor of a timeline entry, the person who decided and the person who asked for information, and the reason an officer gave for an escalation is replaced by a neutral text. Assessments, their notes and INTERNAL timeline entries are for staff only.
- **Which actions write an event** (prompt 4): submission, pick-up and assignment from the queue, asking for and receiving information, escalation, approval and rejection. A plain reassignment, an assessment and the cancelling of an information request write a timeline entry and no event.
- **Information requests** (prompt 4): at most one is open at a time, because a claim that waits for information accepts no second request; creating one answers 201 with the request (the client needs its id), answering and cancelling answer with the claim. Rejecting a claim cancels a request that is still open.
- **Three named exceptions to the layer rules** ([ADR 0014](docs/adr/0014-api-records-and-kernel-api-in-the-layer-rules.md); two in prompt 4, the third in prompt 5): a service may use the request and response records of its own `api.dto` package, every module may use the kernel `shared.api`, and `bootstrap` may use `claim.api.dto` (the sample-claims seeder drives the real claim services with their request records). Nothing else is excused.
- **The outbox relay** (prompt 5): it takes the oldest due events first (`PENDING` rows whose `next_attempt_at` has passed; a failed event stays `PENDING` until it is given up), handles each in its own `REQUIRES_NEW` transaction, and records a failure in a new transaction so the failed handler cannot roll the record back. The delay before the next try is 2 to the power of the attempts, in seconds; the fifth failure makes the event `FAILED`. `last_error` holds the exception's text and never the payload. It is a single instance with at-least-once delivery, switched on by `app.outbox.relay.enabled`, and the tests call `processBatch()` themselves.
- **Notifications are written from the event payload alone** (prompt 5): the handler does not read the claim, so `notification` does not depend on `claim`. The event carries the claimant, the market and the assigned officer; the recipients are the claimant, the market's active officers for a new claim, the assignee for an answer to an information request, and the active managers for an escalation. A second delivery of the same event creates nothing, checked first and backed by the unique `(source_event_id, recipient_id)`, whose insert runs in its own transaction. A channel that fails (email is a logging adapter that writes a masked address) never fails the handler.
- **The payment record is written in its own transaction** (prompt 5): `SimulatedPaymentGateway` runs in `REQUIRES_NEW` and is idempotent by claim number, so a payment that was recorded stays recorded even if the settlement then rolls back, and a retry reuses the stored reference instead of paying twice. The gateway is called before the claim changes state; an unavailable gateway answers 502 `PAYMENT_GATEWAY_UNAVAILABLE` and nothing else is written. The payment status is `COMPLETED`, because the schema's check constraint allows only `INITIATED`, `COMPLETED` and `FAILED`; the prompt's word `PAID` is not a valid value.
- **Exchange rates are illustrative** (prompt 5): each figure is converted on its own at a static rate and rounded half-up to two decimals, so a total is the sum of rounded figures. Every exposure response says `STATIC_ILLUSTRATIVE`, and the application refuses to start if a market's currency has no rate.
- **Reports are computed in memory** (prompt 5): the services read the claims in scope with one query (their market, assignee and decider loaded with it) and fold them in plain Java calculators, so the arithmetic is unit-tested without a database. There are no summary tables, so a figure is never stale; the price is that the work grows with the number of claims in scope.
- **Who sees which report** (prompt 5): an officer sees the own market and a manager sees all markets; `marketCode` narrows the result further. An officer who names another market gets an empty report, not an error, as the claims in the scope are none; an unknown code is a 400. Exposure is for managers only. The performance period is inclusive and defaults to the last 30 days; `from` after `to` is a 400. Ages are whole days.
- **The API contract is a committed file** (prompt 6): `src/main/resources/openapi/claims-api.yaml` was exported from the running application (36 operations) and `OpenApiContractTest` fails when its `METHOD /path` strings and those of the live `/v3/api-docs` differ in either direction. After an API change the file is exported again from a running instance; the test is what reminds you. `/v3/api-docs.yaml` needs a token (the public pattern is `/v3/api-docs/**`), so the export is made with a manager's token.
- **Docker files are provided and were reviewed, not executed** (prompt 6): the `Dockerfile`, `docker-compose.yml`, `.dockerignore` and `.env.example` follow the specification. Docker is not installed on the development machine, so they were checked for consistency (YAML parses, every variable has a default, the jar name equals `finalName`, the variable names match `application.yml`) and nothing more. The PostgreSQL container publishes no host port, so it never clashes with a local PostgreSQL.
- **Verified on a fresh database** (prompt 6): the local `claims_db` was dropped and created again; the packaged jar then applied the six migrations, seeded 13 users, 13 policies and four claims (`CLM-10001`, `CLM-10002`, `CLP-10001`, `CLP-10002`; the next new numbers are `CLM-10003` and `CLP-10003`), and the smoke script passed 165 of 165 steps twice.
- **Sample claims** (prompt 5): `app.seed.sample-claims` (off by default) creates four claims, once, through the real services, so they carry timeline entries and outbox events like any other claim. The seeder does nothing if any claim exists.
- **Seeded policies are valid for one year each side of the seeding date,** so a claim on `MOT-IN-0001` and the other seeded policies needs an incident date within that year; the tests and the smoke script use their own claimants and policies, or days inside the year.
- **`identity` depends on `market`.** `app_users.market_id` is a foreign key to `markets`, so the dependency exists; the specification's dependency table was amended in prompt 3 to include it. It creates no cycle. See [docs/02-architecture.md](docs/02-architecture.md).

## The ten most important decisions

1. **Modular monolith** with eleven modules and a fixed dependency table, enforced by ArchUnit tests. One deployable, one transaction per use case. [ADR 0001](docs/adr/0001-modular-monolith-with-enforced-module-boundaries.md)
2. **Pragmatic hexagonal layering** (`api`, `service`, `domain`, `infrastructure`): JPA entities are the domain model, ports live in `service`, entities never leave the service layer. [ADR 0002](docs/adr/0002-pragmatic-hexagonal-layering.md)
3. **PostgreSQL 17 with Flyway and Hibernate `validate`.** The schema is versioned, written in portable SQL and checked against the entities at startup. [ADR 0003](docs/adr/0003-postgresql-flyway-hibernate-validate.md)
4. **Stateless JWT (HS256), role chosen at login.** Every login failure returns the same 401; the user is loaded from the database on every request. [ADR 0004](docs/adr/0004-stateless-jwt-hs256-role-selected-at-login.md)
5. **Transactional outbox, no message broker.** Events are written with the state change and delivered by a relay, which is the seam for a broker later. [ADR 0005](docs/adr/0005-transactional-outbox-no-message-broker.md)
6. **At-least-once delivery with idempotent consumers,** retry with exponential backoff, `FAILED` after five attempts. [ADR 0006](docs/adr/0006-at-least-once-delivery-idempotent-consumers.md)
7. **Claim numbers from row-locked counters** (`CLM-10001`, `CLP-10001`): unique, gap-free, portable. [ADR 0007](docs/adr/0007-claim-numbers-from-row-locked-counters.md)
8. **Ports and adapters** for policy lookup, payment and notification, so `claim` depends on none of them. [ADR 0008](docs/adr/0008-ports-and-adapters-for-external-integrations.md)
9. **Optimistic locking for pick-up and transitions, and an idempotency key for submission,** so races and retries are safe without long locks. [ADR 0009](docs/adr/0009-optimistic-locking-and-idempotency-key.md)
10. **One error contract** (RFC 7807 with codes and a correlation id), versioned under `/api/v1`, and **404 instead of 403** for out-of-scope resources. [ADR 0011](docs/adr/0011-api-versioning-error-contract-correlation-ids.md)

Also recorded: tests run on H2 in PostgreSQL mode so that Docker is not needed ([ADR 0012](docs/adr/0012-test-strategy-h2-postgresql-mode.md)).

## Deliberately left out, and why

| Left out | Why |
|---|---|
| Frontend | The deliverable is the backend; Swagger UI is the demo surface |
| File and document uploads | Needs storage, limits and scanning ([ADR 0013](docs/adr/0013-text-only-information-responses.md)) |
| Message broker | The outbox is enough for one deployable; the relay is the seam ([ADR 0005](docs/adr/0005-transactional-outbox-no-message-broker.md)) |
| Real payment and policy-system connections | Not available; simulated ([ADR 0008](docs/adr/0008-ports-and-adapters-for-external-integrations.md)) |
| Real exchange-rate feed | Static illustrative rates are sufficient ([ADR 0010](docs/adr/0010-static-illustrative-exchange-rates.md)) |
| Real email delivery | A logging channel stands in |
| Claimant withdrawal of a claim | Not required by the personas |

## Known limitations

- The outbox relay is a single instance; running several would need `FOR UPDATE SKIP LOCKED`. Delivery is at least once and the order is not guaranteed after a retry.
- Outbox rows and idempotency keys are never purged; `FAILED` events are re-queued by hand.
- Notifications reach the claimant after the next poll of the relay (every second by default), not inside the request. Email is a logging channel that writes a masked address; nothing is sent.
- A payment that was recorded stays recorded when the settlement then fails, because the record is written in its own transaction; a retry reuses the stored reference. The gateway is simulated, so no money moves.
- Reports read every claim in the scope and add the figures up in memory. That is exact and always current, and it is fine for the demo volume, but a very large number of claims would need a database query or a summary table.
- A manager sees every market; there is no per-region manager scope. An officer who asks for another market's report gets an empty report.
- A token cannot be revoked before it expires (the per-request user check stops deactivated users). There is no refresh token, no login throttling and no account lockout, and a role change applies only at the next login.
- Spring validates a request body before the role check runs, so a caller without the role who sends an invalid body sees a 400 instead of a 403 (nothing sensitive is shown; see [docs/04-api-design.md](docs/04-api-design.md)).
- The live smoke script creates two users in the database on every run (`smoke.off.*` and `smoke.clm.*`) and, since prompt 4, a policy and several claims for `claimant.in` and `claimant.sg`; since prompt 5 it also settles a claim, which writes a payment and notifications. There is no endpoint to delete any of it.
- A submission without an `Idempotency-Key` is protected against duplicates by the business check (same policy, incident date and type) only. That check is a read followed by an insert, so two identical requests at exactly the same moment, without a key, can both pass it. The key is the safe way to retry.
- Tests use H2, not PostgreSQL, so PostgreSQL-only behaviour is checked by hand during final verification, not by automated tests.
- `Dockerfile` and `docker-compose.yml` are not executed on the development machine, because Docker is not installed there.
- Base-currency totals use illustrative rates and are not accounting figures.
- The payment gateway, the policy system and email delivery are simulated; no money moves and no email is sent. Information responses are text only; there are no attachments.
- There is no login throttling or lockout, no refresh token and no token revocation; a role change applies at the next login (the per-request account check only stops deleted or deactivated users).
- The relay is a single instance, and reports are computed in memory from the claims in scope; both are sized for a demo.
- The committed `claims-api.yaml` must be exported again by hand after an API change; `OpenApiContractTest` fails until it is.
- `GET /v3/api-docs.yaml` needs a bearer token, while `GET /v3/api-docs` (JSON) is public.
- Demo accounts and policies are seeded by default (`app.seed.demo-data`) with documented passwords; switch the seeding off for anything but local use.
- Where the specification is silent, the design records a working assumption ([docs/04-api-design.md](docs/04-api-design.md), "Details to confirm during implementation").
