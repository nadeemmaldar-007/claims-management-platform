# AI Journal

## Purpose

This journal records how AI assistance was used to build **claims-management-platform**: what was asked, what was run, what went wrong and how it was fixed. It is written for the examiner and for the developer. Entries state only what actually happened; commands are the ones really run and results are the ones really seen.

## Tools used

- **Claude Code** (model `claude-sonnet-5-5`), working directly in the project folder, which is open in **IntelliJ IDEA Community Edition 2025.2.5** on Windows 11.
- Shell used by the assistant: Windows PowerShell 5.1, with Git Bash used for POSIX tools such as `sed`.
- Build tooling: Maven Wrapper 3.9.11 (created in prompt 1, stage 3), JDK 21.

## Developer's role

The developer supplies the seven prompt files (kept verbatim in `prompts/`), confirms the environment facts, reviews the output, and decides anything the prompts mark as "stop and ask". The assistant carries out the stages, runs the commands and reports real results. The developer remains responsible for reviewing the design, the code and the claims made in the documents.

## Stage log

| Prompt | Stage | What was done | Commands actually run | Problems and fixes | Result |
|---|---|---|---|---|---|
| 1 | 0 Preflight | Inspected the project folder. Detected the shell. Located JDK 21 and IntelliJ's bundled Maven. Checked the git identity. | `Get-ChildItem -Force -Recurse -Depth 3`; `Get-Content .idea\misc.xml`; `Get-ChildItem` on `%USERPROFILE%\.jdks`, `C:\Program Files\Java`, `C:\Program Files\Eclipse Adoptium`, `C:\Program Files\Microsoft`; `Get-ChildItem -Recurse -Filter mvn.cmd` under `C:\Program Files\JetBrains`, `%LOCALAPPDATA%\Programs`, `%LOCALAPPDATA%\JetBrains\Toolbox\apps`; `Get-Content %APPDATA%\JetBrains\IdeaIC2025.2\options\jdk.table.xml`; `java -version`; `mvn.cmd -v`; `git config user.name`; `git config user.email` | JDK 21 was not in any of the four folders the prompt lists (only JDK 25 under `C:\Program Files\Microsoft`, which is the one on PATH and is not used). The project SDK is named `21` in `.idea/misc.xml`; IntelliJ's SDK registry (`jdk.table.xml`) maps that name to `%USERPROFILE%\Downloads\openjdk-21_windows-x64_bin\jdk-21`. Used that path. The folder held a stub `pom.xml` (groupId `org.example`) and an IntelliJ-generated `.gitignore`, no `Main.java`; both are replaced by the prompt's own files. | Shell: PowerShell 5.1. JDK 21: `C:\Users\nadeem.maldar\Downloads\openjdk-21_windows-x64_bin\jdk-21` (`java -version` prints `openjdk version "21"`, build 21+35-2513). Bundled Maven: `C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.5\plugins\maven\lib\maven3\bin\mvn.cmd` (Apache Maven 3.9.9, runs on JDK 21). Git identity is configured, so no stop was needed. |
| 1 | 1a Repository (commit 1) | Ran `git init -b main`. Wrote `.gitignore`, `.gitattributes` and a README stub with the five required H2 headings. The prompt file was already stored in `prompts/`. | `git init -b main`; `git add .gitignore .gitattributes README.md prompts/`; `git commit` | Git prints "LF will be replaced by CRLF" warnings because the global `core.autocrlf=true`. That is a global setting and was left untouched; `.gitattributes` governs the repository. The stub `pom.xml` was deliberately not staged. | Commit `8ffb55e chore: initialise repository and add prompt log`. |
| 1 | 1b Specification (commit 2) | Extracted the specification from the prompt file with `sed` into `docs/PROJECT_SPEC.md`. | `sed -n '/^<<<SPEC START>>>\r\?$/,/^<<<SPEC END>>>\r\?$/p' prompts/prompt-01-setup-spec-design-scaffold.md \| sed '1d;$d' > docs/PROJECT_SPEC.md`; `wc -l`; `head`; `tail`; `grep -c`; `diff` against source lines 116 to 518 | None. The marker lines appear once each as anchored lines (115 and 519). | `docs/PROJECT_SPEC.md` has 403 lines (expected 403), starts with the required title, ends with the "Definition of done" paragraph, contains no marker line, and `diff` against the source lines (CR ignored) reports no difference. No BOM. |
| 1 | 2 Design documents (commit 3) | Wrote `docs/01-problem-analysis.md`, `02-architecture.md`, `03-data-model.md`, `04-api-design.md`, `05-integration-design.md`, `06-test-strategy.md`, `PLAN.md`, `requirements-traceability.md` (R1 to R13), 13 ADRs in `docs/adr/` (0001 to 0013, one per decision in spec section 15), and the root `NOTES.md`. Updated the README stub's pointer line. All content is derived from `docs/PROJECT_SPEC.md` and marked **Planned**. | Files written with the editor tool. Checks run: a PowerShell script (kept outside the repository, in the session scratchpad) that tests Markdown table column counts, relative links, code-fence balance and Mermaid structure (quoted flowchart labels, no semicolons in sequence diagrams, state and ER line patterns); a content search for company names and marketing words | The checker was first run against a deliberately broken sample to prove it fails when it should: it reported all 7 injected defects (after two bugs in the checker itself were fixed: a one-line file returned a string, and an 8.3 short path broke relative path building). Three corrections to my own drafts: the `domain` layer rule was stricter than the spec (it may reference other modules' `domain` types), an edge label in the deployment diagram was misleading, and the settlement paragraph silently assumed the payment adapter commits in its own transaction (now stated as a design choice for prompt 5). | 23 Markdown files checked: 7 Mermaid blocks, 57 tables, 77 relative links, 0 problems; no company names or marketing words found. **Not verified:** the Mermaid diagrams were not rendered, because Node.js and Python are not installed on this machine and no Mermaid parser was available; the structural check does not prove that GitHub renders them. Open points where the spec is silent are listed in `docs/04-api-design.md` ("Details to confirm during implementation") and in `docs/01-problem-analysis.md` (open questions). |

## Decisions and reasons

- The JDK 21 path was taken from IntelliJ's own SDK registry rather than guessed, so the command line uses the same JDK as the IDE.
- The IntelliJ-generated `.gitignore` was replaced by the prompt's list, because the prompt requires specific entries (`data/`, `.env`, `.claude/settings.local.json`) and requires `.claude/ai-journal.md` and the Maven wrapper files to stay tracked.

## What the AI got wrong or retried

- Stage 0: assumed JDK 21 would be in one of the four folders the prompt lists; it was not. Found it through IntelliJ's own SDK registry instead.
- Stage 2: three first drafts were corrected after re-reading them against the spec (see the stage 2 row): an over-strict `domain` layer rule, a misleading diagram label, and an unstated assumption about payment-record transactions.
- Stage 2: the first version of the docs checker had two bugs of its own (single-line file handling, short-path handling); both were found by running it against a broken sample before trusting its "0 problems" result.

## What the developer must review personally

- The environment facts in the stage log (JDK path, Maven path), since they are specific to this machine.
- The specification in `docs/PROJECT_SPEC.md` is the source of truth for all later prompts; it was extracted mechanically and not edited.
- The Mermaid diagrams in `docs/`, by opening them on GitHub or in an IDE preview, since they were not rendered here.
- The working assumptions where the spec is silent (`docs/04-api-design.md`, "Details to confirm during implementation"), for example which status code a non-assigned officer receives.
- The design documents as a whole: they describe planned behaviour and are written by the assistant from the spec, so the developer should be able to explain every decision in them.

## Limitations

To be completed as the project progresses (Docker files not executed on the dev machine, simulated external systems, static rates, single relay instance).

## Lessons learned

To be completed in the final prompt.
