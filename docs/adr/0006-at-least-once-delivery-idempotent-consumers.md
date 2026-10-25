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

## Implementation note (prompt 5)

- The delay after a failed attempt is `2^attempts` seconds (2, 4, 8, 16), a row becomes `FAILED` when `attempts` reaches `max-attempts` (5), and a later event is never held up by an earlier failure. The failure is recorded in a new transaction, otherwise the counter would be rolled back with the failed delivery. The stored error is the exception class and message cut to 1000 characters; the payload is never copied into it, not even for an envelope that cannot be read.
- The only consumer is the notification handler. It is idempotent in two steps: it asks whether the pair (event, recipient) already has a notification, and the unique constraint catches a duplicate that slips past the question (the insert runs in a transaction of its own, because on PostgreSQL a violated constraint would otherwise spoil the transaction of the relay). Tests deliver the events of a claim twice and count the notifications.
- Because delivery repeats events, a reassigned or deactivated recipient is not an error: a recipient who no longer exists is skipped with a warning.
