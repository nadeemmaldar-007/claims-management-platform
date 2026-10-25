# Notes: Decisions and Assumptions

Status: written at design stage (prompt 1) and updated in prompt 2 (data layer); reconciled with the finished code in the last prompt. Full detail is in [docs/](docs/): [PLAN.md](docs/PLAN.md), [architecture](docs/02-architecture.md) and the [decision records](docs/adr/).

## Key assumptions

- **One currency per market.** A claim uses its claimant's market and currency; there are no cross-currency claims. Decimal places follow the currency (JPY 0, the others 2).
- **Calendar-hour SLAs.** First review within 48 hours of submission, decision within 240 hours; no business-day or holiday calendar.
- **Static, illustrative exchange rates.** Used only to show totals across markets in USD, labelled `STATIC_ILLUSTRATIVE` ([ADR 0010](docs/adr/0010-static-illustrative-exchange-rates.md)).
- **Simulated external systems.** The policy system, the payment gateway and email delivery are simulated behind ports.
- **Text-only information responses.** Claimants answer information requests with text; there are no attachments ([ADR 0013](docs/adr/0013-text-only-information-responses.md)).
- **A team is a market.** An officer sees the claims, workload and performance of their own market.
- **Escalation threshold is the officer's approval limit,** in local currency, roughly comparable across markets.
- **Policies belong to the claimant.** A claim is valid only against the claimant's own, active, matching-type policy in their market.
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

- The outbox relay is a single instance; running several would need `FOR UPDATE SKIP LOCKED`.
- Outbox rows and idempotency keys are never purged; `FAILED` events are re-queued by hand.
- A token cannot be revoked before it expires (the per-request user check stops deactivated users).
- Tests use H2, not PostgreSQL, so PostgreSQL-only behaviour is checked by hand during final verification, not by automated tests.
- `Dockerfile` and `docker-compose.yml` are not executed on the development machine, because Docker is not installed there.
- Base-currency totals use illustrative rates and are not accounting figures.
- Demo accounts and policies are seeded by default (`app.seed.demo-data`) with documented passwords; switch the seeding off for anything but local use.
- Where the specification is silent, the design records a working assumption ([docs/04-api-design.md](docs/04-api-design.md), "Details to confirm during implementation").
