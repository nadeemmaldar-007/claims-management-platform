# ADR 0002: Pragmatic hexagonal layering (`api`, `service`, `domain`, `infrastructure`)

- **Status:** Accepted
- **Date:** 2026-10-25

## Context

Inside each feature module the code needs a consistent shape so that a reader knows where to look, and so that the business logic does not depend on web or integration details. A strict hexagonal architecture (a pure domain model, separate persistence entities, repository interfaces in the domain and JPA adapters elsewhere) would double the number of classes and needs a mapping layer between domain objects and entities. MapStruct is not allowed, so every mapping would be hand-written.

## Decision

Each feature module has four packages:

| Package | Contents | Depends on |
|---|---|---|
| `api` | Controllers and request and response records | `service`, and `domain` enums used in DTOs |
| `service` | Use-case services (transactions, orchestration, scope checks) and the **ports** to external systems | `domain` |
| `domain` | Entities, enums, domain rules and Spring Data repository interfaces. No web or servlet types | no `api`, `service` or `infrastructure` |
| `infrastructure` | Adapters that implement ports, and framework wiring | `service` (ports), `domain` |

The pragmatic parts, chosen on purpose:
- **JPA entities are the domain model.** Entities are plain classes with explicit getters and setters and a protected no-argument constructor.
- **Spring Data repositories live in `domain`** and are treated as persistence ports.
- **Entities never leave the service layer.** Services map them to record DTOs inside the transaction (`open-in-view` is off).
- Ports are placed in the `service` package of the module that needs them.

ArchUnit enforces the layer rules, including that `domain` has no dependency on `org.springframework.web`, `jakarta.servlet` or the other layers, and that controllers are `@RestController` classes in `..api..` named `*Controller`.

## Alternatives considered

| Alternative | Why not |
|---|---|
| Strict hexagonal with separate domain model and JPA entities | Twice the classes and hand-written mappers for little benefit in a domain this size |
| Plain controller-service-repository layers | No place for ports, and nothing that keeps persistence and web types out of business rules |
| Rich domain-driven aggregates with domain services and factories | More ceremony than the workflow needs; the workflow is captured in one pure class, `ClaimWorkflow` |

## Consequences

**Good**
- A familiar structure with few classes, and ports exactly where external systems are.
- Business rules are testable without the web layer.
- The rules are executable, so the structure does not drift.

**Bad**
- `domain` is tied to JPA and Spring Data annotations, so the domain is not framework-free. This is accepted and documented.
- Without a mapping layer, entity changes can ripple into DTO mapping code.

## Implementation note (prompt 6)

The four layers are enforced by `LayerRulesTest` (15 rules). One consequence was found in prompt 3 and settled in prompt 4: the services build the response records, which live in `api.dto`, so `service` depends on that part of `api`. The rule keeps controllers out of reach and allows only the records of the module's own `api.dto`; with the kernel `shared.api` and the use of `claim.api.dto` by `bootstrap` these are the three named exceptions of [ADR 0014](0014-api-records-and-kernel-api-in-the-layer-rules.md). Repositories stayed in `domain` as decided, and the rule that `domain` has no web types holds.
