# Requirements Traceability Matrix

Status: **Planned**. The "planned tests" column is filled in with real test names by the prompt that writes them; the status column changes to **Implemented** when the requirement is delivered and verified.
Date: 2026-10-25. Source: [PROJECT_SPEC.md](PROJECT_SPEC.md) sections 1, 5, 7, 9, 13.

| Id | Requirement | Module | Planned endpoints | Planned tests (names to be filled in) | Status |
|---|---|---|---|---|---|
| R1 | Claimant reports an incident (first notice of loss) | `claim`, with `policy` (lookup), `market`, `identity` | `POST /claims` (optional `Idempotency-Key`); `GET /policies/mine`; `GET /markets` | to be filled in (prompt 4): submission validation, policy lookup rules, duplicate claim, idempotent replay | Planned |
| R2 | Claimant tracks a claim | `claim`, `notification` | `GET /claims`; `GET /claims/{claimNumber}`; `GET /claims/{claimNumber}/timeline` (PUBLIC entries only); `GET /notifications` | to be filled in (prompts 4, 5): scope (own claims only), timeline visibility | Planned |
| R3 | Claimant provides additional information when asked | `claim` | `GET /claims/{claimNumber}/information-requests`; `POST /claims/{claimNumber}/information-requests/{requestId}/respond` | to be filled in (prompt 4): information request round trip, response to a non-open request | Planned |
| R4 | Claimant receives decisions | `claim`, `notification`, `integration` | `GET /notifications`; `GET /notifications/unread-count`; `POST /notifications/{id}/read`; `POST /notifications/read-all`; decision endpoints in R7 | to be filled in (prompt 5): notifications for approve, reject and settle, idempotent delivery | Planned |
| R5 | Staff pick up incoming claims | `claim` | `GET /claims/queue`; `POST /claims/{claimNumber}/pick-up`; `POST /claims/{claimNumber}/assign` | to be filled in (prompt 4): queue ordering and SLA flag, pick-up race, assignment and reassignment | Planned |
| R6 | Staff review and assess claims | `claim`, `policy` | `GET /policies/{policyNumber}`; `POST /claims/{claimNumber}/assessments`; `GET /claims/{claimNumber}/assessments`; `POST /claims/{claimNumber}/information-requests`; `POST .../information-requests/{requestId}/cancel`; `POST /claims/{claimNumber}/escalate` | to be filled in (prompt 4): payout calculation, assessment visibility, escalation | Planned |
| R7 | Staff progress claims to settlement or rejection | `claim`, `integration` (payment gateway adapter) | `POST /claims/{claimNumber}/approve`; `POST /claims/{claimNumber}/reject`; `POST /claims/{claimNumber}/settle` | to be filled in (prompts 4, 5): every workflow transition, approval limit, manager decision on escalated claims, settlement success and gateway failure | Planned |
| R8 | Staff see team workload | `reporting` | `GET /reports/workload` | to be filled in (prompt 6): scope by role, counts per officer, queue and SLA figures | Planned |
| R9 | Staff see team performance | `reporting` | `GET /reports/performance?from=&to=&marketCode=` | to be filled in (prompt 6): period default, approval rate, durations | Planned |
| R10 | Managers see outstanding claims in real time | `reporting`, `claim` | `GET /reports/overview`; `GET /reports/aging`; `GET /claims?status=` | to be filled in (prompt 6): zero-filled status counts, aging buckets | Planned |
| R11 | Managers see liability exposure | `reporting`, `market` | `GET /reports/exposure`; `GET /reports/overview` | to be filled in (prompt 6): exposure per market and type, base-currency conversion, rate source label | Planned |
| R12 | Six markets with their own currencies | `market`, `claim`, `reporting` | `GET /markets`; `GET /markets/{code}`; claim submission (currency from the claimant's market) | to be filled in (prompts 2, 4, 6): reference data migration, decimal places per currency, base-currency conversion | Planned |
| R13 | Structure, boundaries, data model and communication are documented | all, `docs/` | `GET /v3/api-docs`; committed `src/main/resources/openapi/claims-api.yaml` | to be filled in (prompts 4, 6, 7): ArchUnit rules, OpenAPI contract test; documents reviewed in prompt 7 | Planned |

## Cross-cutting capabilities

These support several requirements and have no number of their own.

| Capability | Module | Planned endpoints | Planned tests | Status |
|---|---|---|---|---|
| Login, registration and staff account creation | `identity`, `security` | `POST /auth/login?role=`; `POST /auth/register`; `GET /users/me`; `POST /users`; `GET /users` | to be filled in (prompt 3) | Planned |
| Policy reference data maintenance | `policy` | `PUT /policies/{policyNumber}` | to be filled in (prompts 2, 4) | Planned |
| Error contract and correlation id | `shared` | all endpoints | to be filled in (prompt 3) | Planned |
| Health | `config` and Actuator | `GET /actuator/health` | to be filled in (prompt 3) | Planned |

## Where each requirement is designed

| Requirement | Design documents |
|---|---|
| R1, R2, R3, R5, R6, R7 | [04-api-design.md](04-api-design.md) (state diagram, endpoints), [03-data-model.md](03-data-model.md) |
| R4 | [05-integration-design.md](05-integration-design.md) (events, notification rules) |
| R8 to R11 | [PROJECT_SPEC.md](PROJECT_SPEC.md) section 9, [04-api-design.md](04-api-design.md) |
| R12 | [03-data-model.md](03-data-model.md) (`markets`), [ADR 0010](adr/0010-static-illustrative-exchange-rates.md) |
| R13 | [02-architecture.md](02-architecture.md), [03-data-model.md](03-data-model.md), [05-integration-design.md](05-integration-design.md), the ADRs |
