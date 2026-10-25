# PROMPT 6 OF 6: PACKAGING AND FINISH (DOCKER, FRESH-DATABASE VERIFICATION, API CONTRACT, README, INTERVIEW GUIDE, FINAL CHECKS)

You are the same senior Java / Spring Boot engineer finishing the **claims-management-platform** project inside IntelliJ IDEA on Windows 11. Before anything else, read `docs/PROJECT_SPEC.md` (sections 10, 13, 14), `.claude/ai-journal.md`, `NOTES.md`, the `docs/` folder and `prompts/README.md`. Then carry out this prompt. The code is feature-complete. This prompt packages it, proves it on a **fresh database**, writes the documents a manager and an interviewer will read, and runs the final checks.

Working rules:
- Keep a task list. Work through the stages in order without waiting for approval. Ask the developer only in the situations in section 9.
- **Do exactly what this prompt lists. No mutation testing, no extra experiments, no long polling loops.** The full `mvnw -B clean verify` runs in stage 2 (once, with the smoke test) and in stage 4 (once, at the end).
- Never claim something works unless you ran it and saw the real output. Never invent numbers, claim numbers, test counts or output: every figure in a document comes from a real run in this session.
- Use ONLY the Maven Wrapper with `-B` and `JAVA_HOME` set to the JDK 21 folder recorded in the journal (check `java -version` prints 21 first). The system `java` is JDK 25: never use it.
- **Docker is not installed on this machine: never run `docker`.** Kafka is not used. Never add a remote or push. Never change global git config. PostgreSQL 17 at `localhost:5432`, user `postgres`, password `postgres`.
- The real insurer's name must not appear anywhere in the repository (code, docs, prompts, journal, README). Stage 4 checks this.
- Flyway V1 to V6 stay locked.

---

## 1. STAGE 0: SANITY CHECK

1. Save this prompt as `prompts/prompt-06-packaging-and-finish.md` (if not there) and add its line to `prompts/README.md`.
2. `git status` clean, `git log --oneline` shows the earlier prompts' commits. Do not run the full build yet (stage 2 does).

## 2. STAGE 1: DOCKER PACKAGING (commit)

Create exactly as spec section 14 describes (read it again), reviewing every line because Docker cannot be run here:

- `Dockerfile`: multi-stage; build stage `maven:3.9-eclipse-temurin-21` copies `pom.xml` and `src` and runs `mvn -B -DskipTests package` (no `dependency:go-offline` step, to keep the file simple and safe); runtime stage `eclipse-temurin:21-jre`, non-root user (`groupadd -r app && useradd -r -g app app`), jar copied to `/app/app.jar` from `target/claims-management-platform.jar`, `EXPOSE 8080`, `ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar /app/app.jar"]`.
- `docker-compose.yml` (no `version:` key): service `postgres` (`postgres:17`, `POSTGRES_DB=claims_db`, user and password from environment with defaults `postgres`/`postgres`, named volume, `pg_isready` healthcheck, **no published host port**, so it never clashes with a local PostgreSQL); service `app` (build `.`, `DB_HOST=postgres`, `DB_PORT=5432`, `DB_NAME=claims_db`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET` with a documented dev default of at least 32 bytes, `SEED_DEMO_DATA=true`, `SEED_SAMPLE_CLAIMS=true`, ports `${APP_PORT:-8080}:8080`, `depends_on` postgres with `condition: service_healthy`, `restart: unless-stopped`).
- `.dockerignore` (target, out, .git, .idea, .claude, docs, prompts, *.log, scripts), `.env.example` (every variable the compose file reads, with the safe defaults and a comment that the JWT secret must be changed outside local use).
- Verify without Docker: YAML parses (use a quick Java or Python check if available), every `${VAR}` in compose has a default or an entry in `.env.example`, the jar name in the Dockerfile equals `finalName` in `pom.xml`, the environment variable names used in compose match those read by `application.yml` (`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `JWT_SECRET`, `SEED_*`, `SERVER_PORT`). State plainly in the journal that these files were reviewed but **not executed** on this machine.
- Check `.gitattributes`: `mvnw` and `*.sh` and `Dockerfile` use LF, `*.cmd` uses CRLF. Run `git update-index --chmod=+x mvnw` so the wrapper is executable for the manager on Linux or macOS (after it is tracked).

Commit: `build: add dockerfile, docker compose, dockerignore and env example`.

## 3. STAGE 2: FRESH-DATABASE VERIFICATION AND API CONTRACT EXPORT (no commit until the end of the stage)

1. **Reset the developer's local `claims_db`** so the verification starts from a clean state (it currently holds smoke-test data). Only the database `claims_db` may be touched; never any other database. Find `psql` (`where psql`, else `C:\Program Files\PostgreSQL\17\bin\psql.exe`); with `PGPASSWORD=postgres` run against the `postgres` database: `DROP DATABASE IF EXISTS claims_db WITH (FORCE);` then `CREATE DATABASE claims_db;`. If `psql` cannot be found or fails, **stop and ask** the developer to do it in pgAdmin (right-click `claims_db`, Delete/Drop with Force, then right-click Databases, Create, Database, name `claims_db`, Save) and wait for "done".
2. Run `mvnw -B clean verify` once. It must be BUILD SUCCESS. Record the real numbers: tests run, failures, errors, skipped, JaCoCo line coverage, the number of ArchUnit rules.
3. Start the jar against the fresh database (JDK 21, log to `target\verify-app.log`, remember the process id; if 8080 is busy use `SERVER_PORT=8081` and tell the developer). Wait for `Started ClaimsManagementPlatformApplication` (maximum 120 seconds). Confirm in the log that Flyway applied V1 to V6 and the seeder created the 13 users, the policies and the four sample claims.
4. Observe and record the **real** claim numbers of the four sample claims and their statuses (log in as `manager.apac` through a short script or the smoke script's helpers and call `GET /api/v1/claims`), and what the next new MOTOR and PROPERTY numbers are.
5. Run `java scripts/SmokeTest.java http://localhost:<port>` **twice**. Every step must PASS both times; fix the code (never the expectation) if not. Save the printed output of the second run to `docs/smoke-test-output.txt` (plain text, no secrets).
6. **Export the API contract**: `GET /v3/api-docs.yaml` from the running app and save it to `src/main/resources/openapi/claims-api.yaml` (UTF-8, no BOM, LF). Check it contains `bearerAuth`, the login `role` enum with three values, `/api/v1/claims`, `/api/v1/claims/queue`, `/api/v1/claims/{claimNumber}/settle`, `/api/v1/reports/exposure`, `/api/v1/notifications`, `/api/v1/policies/mine`.
7. Check the log: no `ERROR` lines, no unexpected stack traces, no password, token, claim description or request body logged.
8. Stop the application by process id; confirm the port is free.

## 4. STAGE 3: THE CONTRACT TEST (commit)

Add `OpenApiContractTest` (integration, profile `test`): load the committed `src/main/resources/openapi/claims-api.yaml` with Jackson's YAML mapper (it is already on the classpath through springdoc; if not, add `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml` in test scope with the Boot-managed version), fetch `/v3/api-docs` through MockMvc, build the set of `"METHOD /path"` strings from both documents and assert the two sets are **equal** (on failure the message lists what is missing on each side). Run only this test class (`-Dtest=OpenApiContractTest`) to confirm it passes.

Commit: `feat(api): commit the exported openapi contract and add a contract test`.

## 5. STAGE 4: DOCUMENTS (commit)

Write for two readers: a manager who will run the project and an interviewer who will ask how it works. Plain English, accurate, no filler, no marketing adjectives. Use the real figures recorded in stage 2. Never mention the real insurer's name; the product is "an APAC property and casualty insurer".

1. **`README.md`** with these H2 sections **in this order** (spec section 14 is the source): `Overview`, `Architecture`, `Setup`, `API Reference`, `Testing`, `AI Journal and Prompts`.
   - **Overview**: a three-sentence pitch; the feature list by persona; the tech stack; a **criteria-to-evidence table** with the six areas Planning, Architecture and Design Thinking; Service and API Design; Clean Architecture and Engineering Discipline; Backend Test Automation; Data and Integration Implementation; Backend Functional Completeness, each mapped to concrete files and docs; and the **links table**: Swagger UI `http://localhost:8080/swagger-ui/index.html`, `http://localhost:8080/swagger-ui.html`, OpenAPI JSON `http://localhost:8080/v3/api-docs`, health `http://localhost:8080/actuator/health`, GitHub repository `https://github.com/<your-username>/claims-management-platform` (keep the placeholder), `NOTES.md`, `docs/` design documents, `docs/adr/`, `prompts/`, `.claude/ai-journal.md`, `docs/INTERVIEW_GUIDE.md`, `src/main/resources/openapi/claims-api.yaml`, `docs/smoke-test-output.txt`.
   - **Architecture**: the Mermaid diagrams from `docs/` (module and component graph, entity relationships, claim state machine, sequence from submission through the outbox to a notification, local versus Docker deployment), the layer rules, the module dependency table (with the `identity -> market` amendment), the main trade-offs and what was deliberately left out. Check each Mermaid block for valid syntax by reading it carefully (it cannot be rendered here): correct diagram keyword, balanced brackets and quotes, no unescaped special characters in labels.
   - **Setup**: prerequisites (JDK 21, PostgreSQL 17); the one-time `CREATE DATABASE claims_db;` step with the pgAdmin click path; **three ways to run**: IntelliJ run button, `mvnw`, Docker Compose (say honestly that Compose is provided for the reviewer and was not executed on the author's machine); the **13 demo accounts** table (user id, password, role, market); configuration variables table; troubleshooting (database missing, wrong password, port 8080 busy, wrong JDK, 401 versus 403 versus 404, Docker not installed, Flyway checksum error after changing a migration); how to push to GitHub with the placeholders (`git remote add origin ...`, `git push -u origin main`).
   - **API Reference**: a Swagger walkthrough (login with the role dropdown, Authorize, submit a claim, pick up, assess, approve, settle, notifications, reports) **using the real claim numbers observed in stage 2**; the markets table with currencies and approval thresholds; claim number rules; the workflow table and business rules; the endpoint summary table (copy the final catalogue from `docs/04-api-design.md`); the error contract with an example body; the status code policy; link to the contract file.
   - **Testing**: how to run (`mvnw -B clean verify`, a single class, the smoke script), the real test counts and coverage, the test pyramid, the ArchUnit rules, the quality gates, what is not tested and why (Docker files, real external systems).
   - **AI Journal and Prompts**: how AI was used (prompt-driven, six staged prompts, every prompt saved in `prompts/`), links to `.claude/ai-journal.md` and `prompts/README.md`, and a short honest statement of what the developer reviewed personally.
2. **`docs/INTERVIEW_GUIDE.md`**: a 2-minute pitch; a **5-minute Swagger demo script** (exact steps and which account for each step, in the order that tells the story: claimant submits, officer queue, pick-up, assessment, information request, escalation, manager approval, settlement, notification, manager report); an architecture walkthrough; and **15 likely client questions with honest answers that match the implementation** (module boundaries and how they are enforced, outbox versus a broker, idempotency, concurrency on pick-up and claim numbers, why 404 and not 403, security design and its limits, currency handling, how reports are computed and their scale limit, what is simulated, testing strategy, how the six markets are modelled, error handling, what you would build next, what you would change for production, how AI was used).
3. Reconcile the rest: `docs/` design documents and every ADR (status Accepted, remove "planned" wording, note any decision that changed during the build), `docs/requirements-traceability.md` (every row has a status and test class names, no row left "planned"), `docs/04-api-design.md` (matches the OpenAPI contract), `NOTES.md` (final decisions and assumptions in one readable list, plus the limitations: no login throttling or lockout, no refresh tokens, a role change applies at the next login, single-instance relay, in-memory report aggregation, static exchange rates, simulated payment, policy and email systems, text-only information responses, Docker files not executed on the author's machine), `prompts/README.md` (all six prompt files in order with one line each, plus any extra prompt files), `.claude/ai-journal.md` (final entry for this prompt, the lessons learned and what the AI got wrong or retried, what the developer must review personally).

Commit: `docs: add readme, interview guide and final reconciliation of design documents`.

## 6. STAGE 5: FINAL CHECKS (commit only if something had to be fixed)

1. `mvnw -B clean verify` one last time: BUILD SUCCESS; report the real numbers.
2. Repository hygiene, each checked with a real command: `git status` clean; `git ls-files` contains no `target/`, `out/`, `.idea/`, `*.log`, `node_modules`, `.claude/settings.local.json` (add to `.gitignore` and untrack if needed, but keep `.claude/ai-journal.md` tracked); no file over 1 MB; a case-insensitive search of all tracked files for the pattern `ch[u]bb` finds nothing (if it finds something, remove it); a search for `TODO|FIXME|XXX` in `src` and the documents shows nothing unresolved; no `System.out`, no `printStackTrace` in `src/main`; no real secrets (the documented local-development defaults are fine).
3. Every relative link in `README.md`, `NOTES.md`, `docs/*.md` and `prompts/README.md` points to a file that exists (write a short throw-away check, run it, delete it); every file in `prompts/` is listed in `prompts/README.md`.
4. Fresh-clone sanity check: `git clone` the local repository into a temporary folder outside the project (for example under the user's temp directory), run `mvnw -B -DskipTests package` there with JDK 21 and confirm the jar is produced, then delete the temporary folder. This proves that nothing needed is missing from the repository.
5. `git log --oneline` shows a readable history (conventional messages). Do not rewrite history.

If anything had to be fixed, commit it: `chore: final hygiene fixes`. `git status` must end clean.

## 7. KNOWN PITFALLS FOR THIS PROMPT

1. `DROP DATABASE` fails while connections are open: use `WITH (FORCE)` and make sure the application is stopped first.
2. The sample claims are seeded only when no claims exist, so they appear only on the fresh database; the real numbers come from that run.
3. `/v3/api-docs.yaml` must be fetched from the running app with a plain HTTP client (the smoke script's helper or a short Java snippet), not with PowerShell's `curl` alias.
4. Mermaid breaks on unescaped parentheses, colons and quotes inside labels: quote labels (`A["text (detail)"]`).
5. The Dockerfile must copy a jar whose name equals `<finalName>` in the `pom.xml`.
6. Windows paths in documents: use forward slashes in commands meant for the manager (Linux or macOS), and show the Windows variant only where it differs.
7. Do not describe anything as tested unless a test or the smoke script really exercises it.
8. Every Maven command needs `JAVA_HOME` = JDK 21.

## 8. WHAT THE DEVELOPER DOES AFTER THIS PROMPT (put it in the final report)

Replace `<your-username>` in `README.md`, create an **empty** GitHub repository named `claims-management-platform` (no README, no license), then in Git Bash inside the project folder run `git remote add origin https://github.com/<your-username>/claims-management-platform.git`, `git branch -M main`, `git push -u origin main`; check the README renders and the Mermaid diagrams display on GitHub; send the link.

## 9. STOP AND ASK (the only reasons to pause)

- `psql` is not found or the reset fails (give the pgAdmin steps and wait).
- PostgreSQL refuses the connection or password (show the exact error).
- Dependencies cannot be downloaded (show the exact error; never invent proxy settings).
- The same failure persists after three real fix attempts: stop and report what you tried.

If you run out of room, finish the current stage, commit, update the stage log in `.claude/ai-journal.md`, and tell the developer which stage is next. When the developer replies "continue", read the stage log and resume.

## 10. FINAL REPORT FOR THIS PROMPT (short, plain language)

Report: files created, the commits (`git log --oneline`), the real final `mvnw -B clean verify` result with test counts and coverage, the smoke script result (both runs), the real sample claim numbers, the fresh-clone check result, anything not verified and why (Docker files), and the exact steps from section 8. End with: "Prompt 6 complete. The project is ready to push."

Begin with Stage 0 now.
