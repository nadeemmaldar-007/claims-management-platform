# ADR 0009: Optimistic locking for pick-up and transitions, and an idempotency key for submission

- **Status:** Accepted
- **Date:** 2026-10-25

## Context

Two concurrency problems matter for claims:

1. Two officers may try to pick up the same queued claim at the same moment. Exactly one must win and the other must be told clearly.
2. A claimant whose connection drops after pressing "submit" will retry. The retry must not create a second claim.

Holding database locks across HTTP requests is not an option, and the workflow transitions (approve, reject, settle) also race with one another.

## Decision

- `Claim` carries a `@Version` column. A pick-up loads the claim, checks it is `SUBMITTED`, sets the assignee and `assigned_at` and saves. If another transaction changed the claim in between, the optimistic-lock failure is translated to `409 CLAIM_ALREADY_ASSIGNED` for pick-up and to `409 CONCURRENT_MODIFICATION` for other transitions.
- `POST /claims` accepts an optional `Idempotency-Key` header (8 to 64 characters). The pair `(user_id, idempotency_key)` is unique in `idempotency_keys`. A repeated key from the same user returns the original claim with `200` and the header `Idempotent-Replay: true`.
- A separate **business duplicate check** rejects a claim for the same policy, incident date and claim type as an existing claim that is not `REJECTED` (`409 DUPLICATE_CLAIM`). It catches repeats that have no key.
- The idempotency key is scoped to the user, so one user's key cannot reveal another user's claim.

## Alternatives considered

| Alternative | Why not |
|---|---|
| Pessimistic locks on the claim row for each action | Locks readers and risks deadlocks; a lock cannot span the time between a user reading the queue and clicking pick-up |
| Check the status only, with no version | Leaves a window in which two transactions both see `SUBMITTED` |
| `SELECT ... FOR UPDATE SKIP LOCKED` queue | Specific to PostgreSQL behaviour and not needed for a UI that shows a queue and lets a user choose |
| No idempotency key, rely on the duplicate check alone | A legitimate second claim with identical fields would be blocked, and the first response would still be lost on a retry |

## Consequences

**Good**
- No locks are held between requests; the loser gets a clear, specific 409 and can reload.
- A safe retry of submission returns the same claim and number.
- The duplicate check and the key together cover both kinds of repeat.

**Bad**
- The loser of a race must reload and retry; the application does not retry automatically.
- A repeated key returns the original claim without comparing the request body, so a client that reuses a key for different content gets the old claim.
- Version conflicts on other transitions surface as `CONCURRENT_MODIFICATION`, which clients must handle.

## Implementation note (prompt 4)

- The pick-up flushes the claim **inside the service method** (`saveAndFlush`) and catches `ObjectOptimisticLockingFailureException` there. Without the flush, the exception would only surface at commit, outside the method, and the caller would receive `CONCURRENT_MODIFICATION` instead of `CLAIM_ALREADY_ASSIGNED`. A manager's `assign` from the queue uses the same translation, because it races for the same claim. Tests fire real threads at one claim (two callers, then six) and expect one 200 and the rest `CLAIM_ALREADY_ASSIGNED`; removing the translation makes them fail.
- A claim that someone has already taken is answered with `CLAIM_ALREADY_ASSIGNED` when it is open, and with `CLAIM_INVALID_TRANSITION` when it is already decided.
- Two identical submissions with the same key at the same moment both pass the replay check; the second fails on the unique key, its transaction rolls back together with its claim number, and the caller gets a 409 and can retry to receive the replay.
- The business duplicate check is a read followed by an insert, so it is not atomic; only the idempotency key makes a retry fully safe.
