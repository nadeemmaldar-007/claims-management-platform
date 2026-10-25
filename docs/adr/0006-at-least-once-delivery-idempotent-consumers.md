# ADR 0006: At-least-once delivery with idempotent consumers

- **Status:** Accepted
- **Date:** 2026-10-25

## Context

The relay in [ADR 0005](0005-transactional-outbox-no-message-broker.md) dispatches an event to handlers and then updates the outbox row. The handler's effect (a notification row) and the row update are not one transaction. If the application stops between them, or a handler fails part-way through, the system must choose between losing an event and delivering it twice.

## Decision

- Delivery is **at least once**. A row becomes `PROCESSED` only after all handlers have returned without error.
- On failure the relay increments `attempts`, stores the error in `last_error`, and sets `next_attempt_at` with exponential backoff: 2, 4, 8 seconds and so on. After `max-attempts` (default 5) the row becomes `FAILED` and stays in the table.
- Every handler must be **idempotent**. The notification handler inserts with a unique key `(source_event_id, recipient_id)` and ignores a duplicate insert.
- Each event has a unique `eventId` (UUID) in its envelope, which is the deduplication key for consumers.
- The `NotificationChannel` adapter never fails the handler, so a channel problem does not trigger a retry.
- Handlers must not rely on strict ordering between different events: a retried event can be overtaken by later ones.

## Alternatives considered

| Alternative | Why not |
|---|---|
| At-most-once (mark the row processed before dispatching) | A crash or handler error loses a notification silently |
| Exactly-once | Needs one transaction covering the outbox update and every side effect of every handler. Idempotent handlers give the same observable result without that coupling |
| Unlimited retries | A permanently failing event would be retried forever; `FAILED` after a limit makes the problem visible |
| Dead-letter table separate from the outbox | More schema for no benefit; the `FAILED` status is enough at this size |

## Consequences

**Good**
- No lost events, including after a crash.
- Retrying is safe, because duplicates do nothing.
- A stuck or failed event can be diagnosed by querying the table.

**Bad**
- Every new handler must define and test its own idempotency rule.
- Ordering across events is not guaranteed after a retry.
- `FAILED` rows need manual attention (there is no automatic re-queue in this version).
