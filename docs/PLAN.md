# Delivery Plan

Status: prompts 1 to 3 are complete (design, scaffold, data layer, security and identity). Prompts 4 to 7 are **Planned**.
Date: 2026-10-25. Source of truth: [PROJECT_SPEC.md](PROJECT_SPEC.md) section 16.

## 1. Delivery approach

1. **Design first.** Prompt 1 turns the specification into design documents and decision records before any business code exists. The documents are written as **Planned** and are reconciled with the code in the last prompt.
2. **Vertical slices in a fixed order.** Each later prompt delivers one slice (data, security, claims, integration, reporting, packaging) that builds, is tested and is committed before the next starts.
3. **Verified before the next.** A prompt is complete only when the definition of done (section 6) holds. Nothing is reported as working unless it was run and the output was seen.
4. **Small, conventional commits.** One or more focused commits per stage with conventional prefixes (`chore`, `docs`, `feat`, `test`, `fix`). No remote is added and nothing is pushed.
5. **Resumable.** The stage log in `.claude/ai-journal.md` records what is done, so work can resume at the next stage in a fresh session.

## 2. Milestones: the seven prompts

| # | Prompt file | Goal | Main outputs |
|---|---|---|---|
| 1 | `prompt-01-setup-spec-design-scaffold.md` | Repository, specification, design documents, buildable scaffold | Git repository, `docs/PROJECT_SPEC.md`, design documents, ADRs, `NOTES.md`, `pom.xml`, Maven Wrapper, configuration, `package-info.java` per module; `mvnw -B clean verify` succeeds |
| 2 | `prompt-02-data-layer.md` | Persistence | Flyway `V1` to `V6`, entities, repositories, shared kernel, market, identity and policy domain, `DataInitializer`, repository tests, application context test |
| 3 | `prompt-03-security-and-identity.md` | Access control and the error contract | JWT, security chain, 401 and 403 handlers, correlation id filter, `GlobalExceptionHandler`, auth and user endpoints, OpenAPI configuration, root redirect, tests |
| 4 | `prompt-04-claims-core.md` | The workflow | `ClaimWorkflow`, claim numbers, submission with policy lookup, idempotency, queue, pick-up, assignment, assessment, information requests, decisions, timeline, tests, first ArchUnit rules |
| 5 | `prompt-05-integration.md` | Events and external systems | Outbox, relay, notification module, payment gateway adapter, settlement, tests |
| 6 | `prompt-06-reporting.md` | Read side | Overview, exposure, aging, workload, performance, tests; complete ArchUnit rules; JaCoCo gate; sample claims |
| 7 | `prompt-07-packaging-and-finish.md` | Packaging and reconciliation | `Dockerfile`, `docker-compose.yml`, smoke script, OpenAPI contract export and its test, README with required headings, `prompts/README.md`, interview guide, document reconciliation, final verification against a running application |

## 3. Assumptions

- Java 21 and Spring Boot 3.5.6 with Maven; the project SDK in the IDE is JDK 21 although the system `java` is JDK 25.
- PostgreSQL 17 runs on `localhost:5432` for local runs; tests do not need it.
- Docker is not available on the development machine. The Docker files are written and reviewed but not executed there.
- No message broker is used, although one exists on the machine.
- The repository stays local; no remote exists and nothing is pushed.
- The specification is the single source of truth. Where the specification is silent, the design records a working assumption and flags it for the prompt that implements it (see [04-api-design.md](04-api-design.md), "Details to confirm during implementation").

## 4. Risk register

| # | Risk | Impact | Mitigation |
|---|---|---|---|
| 1 | The build runs on the wrong JDK (JDK 25 is on PATH) | Compile or runtime errors that do not match the target | Set `JAVA_HOME` to JDK 21 in every session and check `java -version` before any Maven command; `maven.compiler.release` is 21 |
| 2 | `mvn` is not installed | No way to run the first build | Generate the Maven Wrapper once with IntelliJ's bundled Maven, then use only the wrapper |
| 3 | Dependencies cannot be downloaded (proxy or mirror) | Build blocked | Stop and ask with the exact error; never invent proxy settings |
| 4 | H2 behaves differently from PostgreSQL | Tests pass but production fails | Portable SQL only; Flyway runs on both; final verification runs the live application on PostgreSQL 17 |
| 5 | Module boundaries erode or cycles appear | The modular monolith turns into a tangle | ArchUnit rules from prompt 4 on, completed in prompt 6; a rule is never deleted silently |
| 6 | Race conditions on pick-up, claim numbers or settlement | Two officers on one claim, duplicate numbers, double payment | Optimistic lock on `Claim`, row-locked counters, idempotent payment adapter, tests for the race |
| 7 | JWT misconfiguration (decoder defaults to RS256, null claim values, short secret) | Authentication fails or is weak | Exact wiring from the specification, fail-fast on a short secret, tests that log in through the real endpoint |
| 8 | `AccessDeniedException` is swallowed by the catch-all handler and becomes a 500 | Wrong status for forbidden calls | A dedicated handler and a test for it |
| 9 | Docker files are wrong because they cannot be run | Reviewers fail to start the application | Review line by line, keep them minimal, state in the journal that they were not executed |
| 10 | Output limit reached in the middle of a prompt | Half-finished stage | Finish and commit the current stage, update the stage log, resume at the next stage |
| 11 | Real company name, logo or the assessment brief text enters the repository | Policy breach | Use neutral wording ("APAC insurer"), write documents in own words, review before each commit |
| 12 | Secrets or local files committed (`.env`, `target/`, `.idea/`) | Leak or noisy history | `.gitignore` from the first commit; `git status` check before each commit; only documented local defaults in configuration |
| 13 | Specification gaps decided differently in different prompts | Inconsistent behaviour | Gaps are listed in the design documents and settled in the prompt that implements them, then recorded |

## 5. Scope decisions: what was cut and why

| Cut | Reason | Where recorded |
|---|---|---|
| Frontend | The deliverable is the backend; Swagger UI is the demo surface | [01-problem-analysis.md](01-problem-analysis.md) |
| File and document uploads | Needs storage, size limits and scanning; information responses are text-only | [ADR 0013](adr/0013-text-only-information-responses.md) |
| Message broker | The outbox gives asynchronous delivery with one deployable and one database; the relay is the seam for a broker | [ADR 0005](adr/0005-transactional-outbox-no-message-broker.md) |
| Real payment and policy-system connections | Not available; simulated behind ports | [ADR 0008](adr/0008-ports-and-adapters-for-external-integrations.md) |
| Real exchange rates | Static illustrative rates are enough for base-currency totals | [ADR 0010](adr/0010-static-illustrative-exchange-rates.md) |
| Real email delivery | A logging channel stands in | [05-integration-design.md](05-integration-design.md) |
| Claimant withdrawal | Not needed by the personas | [01-problem-analysis.md](01-problem-analysis.md) |
| Business-day SLAs | Calendar hours keep the rules simple | [NOTES.md](../NOTES.md) |
| Multiple relay instances | Needs `SKIP LOCKED` | [05-integration-design.md](05-integration-design.md) |

## 6. Definition of done

For every stage:

- `mvnw -B clean verify` is green with JDK 21.
- The stage's tests exist and pass.
- The traceability matrix and the AI journal stage log are updated.
- One or more focused commits with conventional messages.
- `git status` is clean.

For the whole project, additionally: the README has its required headings in order; `NOTES.md`, the interview guide and `prompts/README.md` exist; the OpenAPI contract is committed and tested against the live document; the design documents and ADRs are reconciled with the final code; and the application has been run and checked end to end.

## 7. How AI assistance is used and checked

An AI assistant (Claude Code) carries out each prompt: it writes the documents and code, runs the commands and reports the results. The developer saves the prompt files, reviews the output and decides on anything the prompt marks as a question.

Checks that do not rely on the assistant's word:

- Every claim of success is backed by a command that was run, with the real output recorded in the stage log of `.claude/ai-journal.md`.
- The specification is extracted from the prompt file with a shell command and compared against the source, not retyped.
- Architecture rules are enforced by tests, not by instruction.
- The prompts are kept verbatim in `prompts/`, so the instructions the assistant received can be read next to the result.
- The journal records what the assistant got wrong or retried, and what the developer must review personally.
