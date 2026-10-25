# ADR 0008: Ports and adapters for policy, payment and notification integrations

- **Status:** Accepted
- **Date:** 2026-10-25

## Context

The platform depends on three things it does not own: the policy administration system (to validate a claim against a policy), a payment system (to settle approved claims) and a notification channel (email). None of them is available for this project, yet the design should show how real ones would be connected. The `claim` module must not depend on `policy`, `integration` or `notification`.

## Decision

Each external dependency is a **port**, an interface owned by the module that needs it, with one **simulated adapter** in the module that knows the outside system:

| Port (owner) | Adapter (location) | Behaviour |
|---|---|---|
| `PolicyLookup` (`claim.service`) | database snapshot (`policy.infrastructure`) | Returns a `PolicyView` from the local `policies` table |
| `PaymentGateway` (`claim.service`) | simulated gateway (`integration.payment`) | Idempotent by claim number through `payment_instructions`; reference `PAY-` plus 12 upper-case hex characters; can be told to fail with `app.integration.payment.simulate-failure` |
| `NotificationChannel` (`notification.service`) | logging email channel (`notification.infrastructure`) | Logs one INFO line with a masked email; never fails the handler |

`policy` and `integration` depend on `claim` because they implement its ports (dependency inversion). `claim` calls the port and never sees the adapter.

The payment gateway is called **before** the claim change is committed; if it fails, the response is 502, the claim stays APPROVED and nothing else is written.

## Alternatives considered

| Alternative | Why not |
|---|---|
| Services call clients of the external systems directly | `claim` would depend on each external system's details and could not be tested without them |
| One `integration` module that owns every interface | `claim` would depend on `integration`, which the dependency table forbids |
| Skip the simulations and leave the integrations as TODO | The workflow cannot be demonstrated or tested end to end |

## Consequences

**Good**
- `claim` has no knowledge of where policy data or payments come from.
- A real system is introduced by writing one adapter class; services are unchanged.
- Services are unit-tested with Mockito for the ports, for example a gateway failure keeps the claim APPROVED.

**Bad**
- More interfaces and indirection than direct calls.
- The simulated adapters prove the flow, not that a real system behaves the same way (timeouts, partial failures, authentication), which are therefore not tested.
- The policy data is a snapshot that a manager maintains through the API; it can be out of date compared with a real policy system.

## Implementation note (prompt 4)

`PolicyLookup` and its `PolicyView` live in `claim.service`; the adapter is `policy.infrastructure.PolicySnapshotLookup`. `PolicyView` carries its own status enum and uses claim's own `ClaimType` for the product type, so `claim` imports nothing from `policy` and the module rule that forbids it holds. The two other ports (`PaymentGateway`, `NotificationChannel`) are built in prompt 5.
