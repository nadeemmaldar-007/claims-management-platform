# 01. Problem Analysis

Status: **Planned** (nothing is implemented yet; this document describes the problem the platform solves).
Date: 2026-10-25. Source of truth: [PROJECT_SPEC.md](PROJECT_SPEC.md).

## 1. The business problem

An insurer that sells motor and property cover in six Asia-Pacific markets handles claims by phone and email. Three groups feel the pain in different ways:

- **Claimants** report an incident to a call centre or a mailbox and then hear nothing. They cannot see whether anyone has picked the claim up, what is missing, or why it was decided the way it was.
- **Claims staff** work out of shared inboxes and spreadsheets. Nobody can see which claims are waiting, who holds which claim, or how loaded a colleague is, so pick-up is uneven and old claims are forgotten.
- **Managers** cannot answer "how many claims are open right now, and how much money could we owe?" without asking each team for a spreadsheet. Exposure is stale by the time it is compiled, and each market reports in its own currency.

The platform replaces this with one system of record. A claimant submits a first notice of loss, staff work it through a defined workflow with an audit trail, and managers read live figures. This repository contains the **backend only**; a frontend is a separate piece of work.

## 2. Stakeholders and personas

| Persona | Role value | Who they are | Primary goal |
|---|---|---|---|
| Claimant | `CLAIMANT` | A policyholder in one market who has suffered a loss | Report the loss quickly, know where the claim stands, answer questions, get the outcome |
| Claims officer | `OFFICER` | Staff member belonging to one market team | Work the market's queue, assess claims, decide within authority, settle |
| Manager | `MANAGER` | Regional lead with no single market | See everything, decide escalations, balance work, maintain reference data |

Secondary stakeholders (no login in this version): the **policy administration system** owners (source of policy data, simulated here), the **finance and payments** team (receives settlement instructions, simulated here), and the **platform engineers** who run and extend the system.

## 3. Needs per persona

| Persona | Needs | Satisfied by (module) |
|---|---|---|
| Claimant | Submit a claim for a policy they hold, in their own market and currency | `claim`, `policy` (lookup), `market` |
| Claimant | Retry a submission safely after a network failure without creating a duplicate | `claim` (idempotency key) |
| Claimant | Track status and a timeline of public events | `claim` |
| Claimant | Answer an information request with text | `claim` |
| Claimant | Be told when something happens (acknowledgement, assignment, question, decision, payment) | `notification` |
| Officer | See the queue of unassigned claims in their market, oldest first, with SLA breach flag | `claim` |
| Officer | Pick up a claim so that no colleague works the same one | `claim` |
| Officer | Record an assessment with an automatic deductible and payout calculation | `claim` |
| Officer | Ask the claimant for information, cancel a request, escalate, approve within the limit, reject, settle | `claim`, `integration` (payment) |
| Officer | See the team workload and performance | `reporting` |
| Manager | See outstanding claims and liability exposure for all markets in one currency, in real time | `reporting`, `market` |
| Manager | Decide escalated claims, assign and reassign claims | `claim` |
| Manager | Create staff accounts and maintain policy reference data | `identity`, `policy` |
| All | Log in with a user ID, password and a role chosen from a list | `identity`, `security` |

## 4. Scope

| Item | In or out | Reason |
|---|---|---|
| First notice of loss, workflow, assessment, decisions, settlement, timeline | In | Core of the business problem |
| Role-based security and per-market scoping | In | Claimants must not see other claims; officers work one market |
| Six markets with their own currencies and escalation thresholds | In | Defines authority limits and money formatting |
| Policy validation at submission | In | Prevents invalid claims entering the queue; done through a port against a local snapshot |
| Notifications (in-app plus a logging channel) | In | Claimants need visibility; email delivery is only simulated |
| Reporting: overview, exposure, aging, workload, performance | In | The manager and officer needs in section 3 |
| Flyway-managed PostgreSQL schema, tests, Docker packaging, documentation | In | Required deliverables |
| Frontend | Out | Separate work; the API and Swagger UI are the interface here |
| File and document uploads | Out | Adds storage, virus scanning and size policies. Information responses are text only (ADR 0013); a storage port is the extension point |
| Message broker | Out | One deployable and one database are enough; the transactional outbox is the seam (ADR 0005) |
| Real payment and policy-system connections | Out | Simulated behind ports so that the real systems can replace the adapters (ADR 0008) |
| Real exchange-rate feed | Out | Static illustrative rates are enough to show base-currency totals (ADR 0010) |
| Real email delivery | Out | A logging channel stands in for it |
| Claimant withdrawal of a claim | Out | Not required by the personas; would add a status and a transition |

## 5. Assumptions

1. A claimant belongs to exactly one market, chosen at registration. The claim takes that market and its currency; there are no cross-currency claims.
2. A policy belongs to one holder (a claimant) and one market. A claim is valid only against the claimant's own policy in the claimant's market.
3. An officer's "team" is the set of officers of their market.
4. SLAs are measured in calendar hours, not business hours: first review within 48 hours of submission, decision within 240 hours.
5. Exchange rates are static, illustrative and labelled as such (`STATIC_ILLUSTRATIVE`). Totals across markets exist only in the base currency (USD).
6. The policy system, the payment gateway and email delivery are simulated.
7. Information responses are plain text.
8. The escalation threshold is the largest amount an officer may approve, expressed in the market's local currency; thresholds are roughly comparable in value across markets.
9. Two claims for the same policy, incident date and claim type are duplicates unless the earlier one was rejected.
10. Staff accounts are created by a manager; only claimants can self-register.

## 6. Open questions

These do not block the build. Each has a working assumption above or in the spec and would be confirmed with the business.

| # | Question | Working position |
|---|---|---|
| 1 | Should SLAs use business days and market public holidays? | No; calendar hours (assumption 4) |
| 2 | Should the insurer track a separate reserve per claim? | No; outstanding exposure (open claimed amount plus approved awaiting payment) stands in |
| 3 | Can a decided claim be appealed or re-opened? | No; REJECTED and SETTLED are final |
| 4 | Are partial or staged payments needed? | No; one settlement per claim for the approved amount |
| 5 | What does `POST /claims/{n}/assign` do on a claim still in SUBMITTED? The spec defines pick-up for that status and reassignment for later statuses | To be settled in the claims-core prompt; the likely reading is "assign on behalf of an officer, same effect as pick-up" |
| 6 | Should a reassignment notify the claimant? | No; the REASSIGNED timeline entry is internal, so no claimant-facing event is raised |
| 7 | How long must outbox rows, idempotency keys and timelines be kept? | Not purged in this version; see the retention notes in [03-data-model.md](03-data-model.md) |
| 8 | Which real channels (email, SMS) are needed for notifications? | In-app only, plus the logging channel |

## 7. Glossary

| Term | Meaning in this project |
|---|---|
| **FNOL** (first notice of loss) | The claimant's first report of an insured incident. It creates the claim in status `SUBMITTED`. |
| **Reserve** | Money an insurer sets aside for the expected cost of an open claim. The platform stores no reserve; it reports outstanding exposure instead. |
| **Exposure** | The amount the insurer could still have to pay. Here: `openClaimedAmount` (claimed amount of open claims) plus `approvedAwaitingPayment`, per market and claim type. |
| **Deductible** | The part of a loss the policyholder bears. It is copied from the policy onto the claim at submission and subtracted when the recommended payout is computed. |
| **Sum insured** | The maximum the policy pays. It caps the claimed amount at submission and the payout at assessment. |
| **Escalation** | An officer hands a claim to a manager, who is the only role that can decide it from then on. The usual trigger is an approval above the officer's limit. |
| **Market** | One of the six countries (IN, SG, AU, JP, MY, TH), each with a currency, an escalation threshold and its own team of officers. |
| **Claim number** | Business identifier such as `CLM-10001` (motor) or `CLP-10001` (property). The API addresses claims by it. |
| **SLA** | Target time: first review within `first-review-hours`, decision within `decision-hours`. A claim past the target is a breach. |
| **Outbox** | A database table where domain events are written in the same transaction as the state change, then delivered by a relay. |
| **Port / adapter** | A port is an interface owned by the module that needs something external; an adapter implements it for one concrete system. |
| **Idempotency key** | A client-supplied value that makes a repeated submission return the original claim instead of creating another. |
| **Correlation id** | A per-request identifier carried in `X-Correlation-Id`, in log lines and in error bodies. |
