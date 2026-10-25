# ADR 0014: Three named exceptions to the layer and module rules about the api packages

- **Status:** Accepted
- **Date:** 2026-10-25 (the third exception was added in prompt 5)

## Context

The specification (section 3) and ADR 0002 put the request and response records in the `api` layer ("`api`: controllers and request and response records") and say that entities never leave the service layer: services map them to records inside the transaction, because `open-in-view` is off. The first architecture rules (prompt 4) say that `service` does not depend on `api` and that `api` is used by nothing but itself. Both statements cannot hold at once: a service that returns a `ClaimDetailResponse` or takes a `SubmitClaimRequest` depends on a class in `api.dto`. The code of every feature module (identity, market, policy, claim) was written this way from prompt 3 on, and `docs/02-architecture.md` already recorded the consequence in prompt 3: the architecture tests "must allow `..api.dto..` to be used from `service`, and keep controllers, which are the rest of `api`, out of reach".

A second case is the kernel. `shared.api` holds `PageResponse`, `PageRequests`, `ProblemDetails`, the exception handler and the correlation-id filter. Every module uses them, services included, and the specification names them as part of the kernel that all modules may depend on.

Moving the 23 request and response records out of `api` is mechanically easy, but it would contradict the specification text (which would need a fifth package in its layer description), touch every controller, service and test that imports them, and only move the same dependency to another package name. The records are plain data carriers with no behaviour, so the dependency does no harm.

## Decision

The layer rules keep their full strength except for three exceptions, each written out in the rule itself (`LayerRulesTest`, `ModuleDependencyRulesTest`):

1. **Records of the module's own `api.dto` package.** A class in `<module>.service` may depend on classes in `<module>.api.dto`. It may not depend on any other class of `<module>.api` (a controller, a handler). Classes of `domain` and `infrastructure` may not depend on `api` at all, and nobody outside the module and its tests may use its `api` package or its `api.dto` package, with the one exception of number 3.
2. **The kernel `shared.api`.** Any module may use `shared.api`, because `shared` is the kernel. The rule "no module uses another module's `api` or `infrastructure` package" therefore does not look at `shared`.
3. **The composition root builds the request records of the services it calls.** The module `bootstrap` (the demo-data and sample-claim seeders) may use the classes of `claim.api.dto`, because the services that it calls to create the sample claims, as a real user would, take those records as parameters. It may use nothing else of another module's `api` package, and no other module may use another module's `api.dto` (added in prompt 5, with the sample claims).

No other exception exists. A new dependency that is not one of these three fails the build.

## Alternatives considered

| Alternative | Why not |
|---|---|
| Move the records to `service.dto` (or a new `dto` package) | Contradicts the specification's description of the api layer, changes the layer description of the specification and every import of the 23 records, and gains nothing in behaviour |
| Return entities from services and map in the controller | Needs an open session in the controller (`open-in-view` is off on purpose) or lazy-loading failures; a service would no longer be one transaction that returns a finished answer |
| Duplicate every record as a service-level result type | Twice the classes and a mapping layer for nothing; MapStruct is not allowed |
| Drop the rule | The rule still catches the real mistakes: a service calling a controller, a domain class knowing the web layer, an adapter reaching into `api` |

## Consequences

**Good**
- The rules are executable and describe the project as it is; the two exceptions are visible in one place.
- Everything that matters is still enforced: the direction of the layers, the independence of `domain`, and the closed `api` and `infrastructure` packages between modules.

**Bad**
- A reader who expects a strict "service never sees api" has to read this ADR. If the project ever gets a second kind of client (a message consumer, a CLI) that needs the same results, the records should move out of `api` and this ADR should be retired.
