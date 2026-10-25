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
