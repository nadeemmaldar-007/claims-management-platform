# ADR 0007: Claim numbers from row-locked counters

- **Status:** Accepted
- **Date:** 2026-10-25

## Context

Claims need human-readable numbers that staff and claimants can quote: `CLM-10001` for motor and `CLP-10001` for property, each type counting up from 10001. Two simultaneous submissions must never receive the same number, and a failed submission should not leave a hole in the series. The SQL must be portable to H2 in PostgreSQL mode, which rules out relying on sequences.

## Decision

- A table `claim_counters` has one row per claim type (`claim_type` primary key, `next_value` `BIGINT`). Migration `V6` seeds both rows with 10001.
- `ClaimNumberGenerator.next(type)` is `@Transactional(propagation = MANDATORY)`: it must run inside the transaction that inserts the claim. It reads the counter row with a **pessimistic write lock**, increments it, and returns the formatted number.
- Because the counter update and the claim insert are in one transaction, a rollback also rolls back the counter, so there is no gap.
- The API addresses claims by claim number, which has a unique constraint.

## Alternatives considered

| Alternative | Why not |
|---|---|
| Database sequences | Not portable across PostgreSQL and H2 in the way the migrations require, and sequences do not roll back, so failed submissions leave gaps |
| `max(claim_number) + 1` | Race condition between concurrent submissions |
| Using the identity column as the number | No type prefix or per-type series, and identity values also skip on rollback |
| UUIDs | Not human-friendly |
| An in-memory counter in the application | Lost on restart, wrong with more than one instance |

## Consequences

**Good**
- Unique, gap-free numbers per type, even under concurrency.
- Works unchanged on PostgreSQL and H2.
- The rule is expressed in one small class and is easy to test.

**Bad**
- Submissions of the same type are serialised on the counter row until the transaction commits. That is acceptable for the expected volume but would be a bottleneck at high submission rates.
- The counter rows must exist; they are created by migration.
- Gap-free numbering depends on the generator always being called inside the claim transaction, which `MANDATORY` propagation enforces.

## Implementation note (prompt 4)

Nothing in the decision changed. `ClaimSubmissionService` takes the number only after every validation (request, policy checks, duplicate check) has passed, so a request that is refused never uses up a number and never holds the counter lock; the lock is held from the number to the commit of the claim, which is short. Tests compare the counter before and after refused submissions (400, 422 and 409) and after an idempotent replay, and check that the two claim types count independently.
