# ADR 0011: API versioning, error contract (RFC 7807 with codes) and correlation ids

- **Status:** Accepted
- **Date:** 2026-10-25

## Context

Clients need to depend on the API over time, to react to failures programmatically, and to report a problem in a way that engineers can trace in the logs. Security and business failures must not reveal whether a resource exists.

## Decision

- **Versioning in the path:** all endpoints are under `/api/v1`. A breaking change introduces `/api/v2` next to it.
- **One error shape:** RFC 7807 `ProblemDetail` (`application/problem+json`) with extra properties `code` (machine-readable), `timestamp`, `correlationId`, and for validation failures `errors: [{ "field", "message" }]`. Spring's `problemdetails` support is enabled.
- **Single handler:** one `GlobalExceptionHandler` in `shared.api`, extending `ResponseEntityExceptionHandler`. Every business exception extends `ApiException`, which carries a code and an HTTP status, so the handler never needs to know feature modules. `AccessDeniedException` is handled explicitly (403), because a catch-all would otherwise turn it into a 500. The 401 and 403 produced inside the security chain use the same body shape.
- **Status code policy:** 401 no or invalid token, 403 role not allowed, 404 unknown or out-of-scope resource, 400 invalid request, 422 business-rule violation, 409 invalid transition, duplicate or concurrent conflict, 502 upstream failure. A stable list of 20 error codes is part of the contract.
- **404 instead of 403 for out-of-scope resources**, so that a claimant cannot learn that another claim number exists.
- **Correlation id:** `X-Correlation-Id` is read from the request or generated, put in the logging MDC, returned as a response header and repeated in error bodies.
- The 500 response has a generic message; the stack trace goes only to the log.

## Alternatives considered

| Alternative | Why not |
|---|---|
| Version in a header or media type | Harder to try in a browser or Swagger UI, and easier to forget |
| A custom JSON error format | RFC 7807 is a standard that clients and tools already understand |
| Return 403 for resources outside the caller's scope | Reveals that the resource exists |
| Per-controller exception handlers | Duplicated mapping and inconsistent bodies |
| No correlation id | A user's error report could not be matched to a log line |

## Consequences

**Good**
- Clients branch on `code`, not on message text.
- One place defines how every failure looks, including security failures.
- A correlation id ties a client report to the server logs.
- Existence of out-of-scope resources is not revealed.

**Bad**
- Clients cannot distinguish "does not exist" from "not yours"; this is intended.
- The list of codes is a contract; renaming a code is a breaking change.
- Support staff must ask for the correlation id from the response.

## Implementation notes (prompt 3)

The decision was implemented as written, with `GlobalExceptionHandler`, `ProblemDetails` and `CorrelationIdFilter` in `shared.api`. These details became concrete:

- **One factory for every error body.** The exception handler and the two security handlers (401 and 403, written directly to the response) all use `ProblemDetails`, so a 401 produced by a filter has exactly the same shape as a 404 produced by a controller. Every body has `type`, `title`, `status`, `detail`, `instance`, `code`, `timestamp` and, when the request has one, `correlationId`.
- **Spring's own errors are decorated too.** The handler overrides `handleExceptionInternal`, so the 405, 415 and 404 responses that Spring MVC builds itself also get `code`, `timestamp` and `correlationId`. Their code is `MALFORMED_REQUEST` (404 for an unknown path is `RESOURCE_NOT_FOUND`).
- **Correlation id.** A client value is accepted only when it matches `^[A-Za-z0-9._-]{1,64}$`; anything else (empty, too long, spaces, line breaks, markup) is replaced by a UUID and never reflected. The header is set before the filter chain continues, so even a 401 carries it; the logging context entry is removed in a `finally` block.
- **What an error never contains.** Validation errors list field names and messages, never the rejected value, so a rejected password is not repeated. A malformed body is reported as "Invalid value for field 'kind'", without the value. A data integrity error says "The request conflicts with existing data", without SQL or constraint names. An unexpected error says "Unexpected error" and nothing from the exception.
- **`AccessDeniedException` and `AuthenticationException` have their own handlers.** Without them the catch-all would turn a failed `@PreAuthorize` into a 500. A mutation check (pointing the access-denied handler at another exception type) made seven tests fail with "expected 403 but was 500".
- **A property to know.** Spring MVC binds and validates a request body before the method-security check runs. A caller without the role who sends an invalid body therefore gets the 400 validation error, and with a valid body gets 403. Only field names and messages are shown, which the public OpenAPI document lists anyway; a test records the behaviour. Getting 403 first would need the role rules duplicated in the filter chain, which this design avoids.
- **Paging.** `size` above 50 is capped and not rejected; a `size` below 1, a negative `page` and a `sort` outside the endpoint's whitelist are 400s naming the parameter.
- The 404-instead-of-403 rule for out-of-scope resources applies from the claims prompt on; so far only an unknown market code gives a 404.
