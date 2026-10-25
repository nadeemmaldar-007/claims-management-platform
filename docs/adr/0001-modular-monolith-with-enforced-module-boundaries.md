# ADR 0001: Modular monolith with enforced module boundaries

- **Status:** Accepted
- **Date:** 2026-10-25

## Context

The platform covers one domain (claims) with a handful of supporting concerns: identity, markets, policy data, notifications, reporting and integrations. A claim change, its timeline entry and its outbox event must succeed or fail together. The team is small, the load is modest, and the result must be easy for a reviewer to understand and to run locally without Docker.

The risk with a single deployable is that any class can call any other, so the structure erodes into a tangle that cannot be split later.

## Decision

Build **one Spring Boot application** with base package `com.apac.claims`, divided into eleven first-level packages that act as modules: `shared`, `config`, `security`, `identity`, `market`, `policy`, `claim`, `notification`, `reporting`, `integration`, `bootstrap`.

- The allowed dependencies between modules are fixed in a table (specification section 3, drawn in [02-architecture.md](../02-architecture.md)). Anything not listed is forbidden and the graph has no cycles.
- A module may use another module's `service` and `domain` types, never its `api` or `infrastructure` packages.
- `claim` must not depend on `policy`, `notification`, `reporting` or `integration`; it uses ports it owns and the shared event contracts.
- The rules are enforced by **ArchUnit tests** that run in `mvnw verify`. A rule that cannot be met cheaply gets a narrow, documented exception in an ADR; it is never deleted silently.
- The project stays a single Maven module.

## Alternatives considered

| Alternative | Why not |
|---|---|
| Microservices | Network failures and distributed transactions for no benefit at this size; harder to run and review |
| One package layered by technology (`controller`, `service`, `repository`) | No visible business boundaries; nothing stops claims code from reaching into reporting internals |
| Several Maven modules | The compiler would enforce the boundaries, but ports implemented by modules that depend back on `claim` need careful module ordering, and the build gets heavier. ArchUnit gives the same checking with one `pom.xml` |
| Spring Modulith | Adds a dependency and its own conventions; explicit ArchUnit rules read directly as the dependency table |

## Consequences

**Good**
- One database transaction covers a state change, its timeline entry and its outbox row.
- One artifact to build, run and deploy; tests need no containers.
- The dependency structure is documented and checked on every build.
- The modules already communicate through services, ports and events, so splitting one out later is possible ([02-architecture.md](../02-architecture.md), section 10).

**Bad**
- The compiler does not enforce the boundaries; only the tests do. If the architecture tests are weakened, the boundaries weaken.
- All modules scale and fail together.
- The shared database requires discipline: tables are owned by one module and there are no foreign keys across modules except to `app_users` and `markets`.
