# Prompts

Every prompt used to build this project, kept verbatim and in the order it was used. Each later prompt starts with: "Read `docs/PROJECT_SPEC.md` and `.claude/ai-journal.md`, then carry out every instruction in `prompts/prompt-0N-<topic>.md`."

1. [`prompt-01-setup-spec-design-scaffold.md`](prompt-01-setup-spec-design-scaffold.md): preflight, repository, engineering specification, design documents and decision records, buildable Maven scaffold.
2. [`prompt-02-data-layer.md`](prompt-02-data-layer.md): shared kernel, typed configuration, Flyway migrations V1 to V6, entities and repositories, demo-data seeder, unit, repository and seeder tests, verification against PostgreSQL 17.

3. [`prompt-03-security-and-identity.md`](prompt-03-security-and-identity.md): error contract and correlation ids, JWT security, login with a role dropdown, registration, user management, markets API, OpenAPI configuration, the first live smoke script, verification against PostgreSQL 17.

4. [`prompt-04-claims-core.md`](prompt-04-claims-core.md): claim workflow rules and claim numbers, the policy module, claim submission with policy validation and idempotency, the officer queue, pick-up and assignment, assessments, information requests, escalation, approval and rejection, the claim timeline, the write side of the outbox, the first architecture rules, and the live claims journey in the smoke script.

5. [`prompt-05-integration-and-reporting.md`](prompt-05-integration-and-reporting.md): the outbox relay with retry and backoff, event-driven notifications and their endpoints, the payment gateway port with a simulated adapter and settlement, the five reports, four sample claims seeded through the service layer, the remaining architecture rules and the JaCoCo coverage gate, and the notifications, settlement and reports steps of the smoke script.

6. [`prompt-06-packaging-and-finish.md`](prompt-06-packaging-and-finish.md): Docker packaging (reviewed, not executed here), verification on a fresh database with the smoke script run twice, the exported OpenAPI contract and its test, the README, the interview guide, the final reconciliation of the design documents, and the last repository checks.

(The plan had seven prompts until prompt 5 started; prompt 5 merged integration and reporting, see `docs/PROJECT_SPEC.md` section 16.) Any extra instruction that changes the work is saved as `prompts/prompt-0N-extra-<topic>.md` and listed here and in the AI journal.

Extra instructions saved so far:

- [`prompt-04-extra-stop-mutation-testing.md`](prompt-04-extra-stop-mutation-testing.md): stop the deliberate-defect check of the tests that was not part of prompt 4, clean up its processes and keep to the prompt.
