# ADR 0005: Transactional outbox for domain events, and no message broker in this version

- **Status:** Accepted
- **Date:** 2026-10-25

## Context

Claim changes must trigger notifications without making the claim transaction depend on the notification code. If the claim commits but the notification is lost, claimants are not told; if the notification code fails inside the claim transaction, a claim action fails for an unrelated reason. Writing to a database and publishing to a broker in one step cannot be made atomic without a pattern such as the outbox.

A message broker (Apache Kafka) exists on the development machine but is intentionally not used. Docker is not available, and a broker would add installation, configuration and failure modes that the assessment does not call for.

## Decision

- The service layer writes an `outbox_events` row through `DomainEventPublisher` **in the same transaction** as the state change and the timeline entry.
- A scheduled **relay** (`app.outbox.relay.enabled`) loads due `PENDING` rows in order and dispatches each to all `DomainEventHandler` beans in the same process, then marks the row `PROCESSED`.
- `claim` knows only `DomainEventPublisher`; `integration` implements it; `notification` implements a handler. No module is coupled to another through events.
- **No Kafka or other broker dependency, configuration or code** exists anywhere in the project.
- The relay's dispatch step is the documented **seam** where a broker would be added ([05-integration-design.md](../05-integration-design.md), section 7).

## Alternatives considered

| Alternative | Why not |
|---|---|
| Spring `ApplicationEventPublisher` with `@TransactionalEventListener(AFTER_COMMIT)` | An event is lost if the process stops after the commit; there is no retry and no record |
| Calling the notification service inside the claim transaction | Couples `claim` to `notification` (forbidden by the dependency table) and lets a notification failure roll back the claim |
| Publish to Kafka or RabbitMQ directly after commit | Still a dual write that can lose events; adds infrastructure that is unavailable or unwanted here |
| Change data capture (for example Debezium) | Heavy infrastructure for a single-application system |

## Consequences

**Good**
- A state change and its event are committed or rolled back together.
- Failed deliveries are retried with backoff, and their status, attempts and last error are visible in the table.
- No new infrastructure; the tests call the relay's one-batch method directly.
- A broker can be added later by changing only the relay's dispatch step.

**Bad**
- Delivery has a delay of up to the poll interval (1 second by default).
- The relay is a single instance; several instances would need `FOR UPDATE SKIP LOCKED`.
- The outbox table grows; a purge job is not built in this version.
- Delivery is at-least-once, so handlers must be idempotent ([ADR 0006](0006-at-least-once-delivery-idempotent-consumers.md)).

## Implementation note (prompt 5)

The relay is `integration.service.OutboxRelay`, called by `OutboxRelayScheduler` every `app.outbox.relay.poll-interval-ms` (1000 ms) while `app.outbox.relay.enabled` is true; `processBatch()` can also be called directly, which is how the tests drive it (the scheduler is off in the test profile). Each event is handled in a transaction of its own (`REQUIRES_NEW`), so one failing event neither rolls back nor blocks the others. The names of the events moved to the kernel (`shared.event.EventTypes`), so that the consumers need not depend on the claim module. Against the real database, the first start after prompt 5 delivered the 24 events that the smoke runs of prompt 4 had left `PENDING`, and the 32 of the two new runs, without a single failure.
