# Prompts

Every prompt used to build this project, kept verbatim and in the order it was used. Each later prompt starts with: "Read `docs/PROJECT_SPEC.md` and `.claude/ai-journal.md`, then carry out every instruction in `prompts/prompt-0N-<topic>.md`."

1. [`prompt-01-setup-spec-design-scaffold.md`](prompt-01-setup-spec-design-scaffold.md): preflight, repository, engineering specification, design documents and decision records, buildable Maven scaffold.
2. [`prompt-02-data-layer.md`](prompt-02-data-layer.md): shared kernel, typed configuration, Flyway migrations V1 to V6, entities and repositories, demo-data seeder, unit, repository and seeder tests, verification against PostgreSQL 17.

3. [`prompt-03-security-and-identity.md`](prompt-03-security-and-identity.md): error contract and correlation ids, JWT security, login with a role dropdown, registration, user management, markets API, OpenAPI configuration, the first live smoke script, verification against PostgreSQL 17.

Prompts 4 to 7 (claims core, integration, reporting, packaging and finish) are added to this list when they are used. Any extra instruction that changes the work is saved as `prompts/prompt-0N-extra-<topic>.md` and listed here and in the AI journal.
