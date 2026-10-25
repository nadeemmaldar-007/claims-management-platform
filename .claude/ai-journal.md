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

## Decisions and reasons

- The JDK 21 path was taken from IntelliJ's own SDK registry rather than guessed, so the command line uses the same JDK as the IDE.
- The IntelliJ-generated `.gitignore` was replaced by the prompt's list, because the prompt requires specific entries (`data/`, `.env`, `.claude/settings.local.json`) and requires `.claude/ai-journal.md` and the Maven wrapper files to stay tracked.

## What the AI got wrong or retried

Nothing so far.

## What the developer must review personally

- The environment facts in the stage log (JDK path, Maven path), since they are specific to this machine.
- The specification in `docs/PROJECT_SPEC.md` is the source of truth for all later prompts; it was extracted mechanically and not edited.

## Limitations

To be completed as the project progresses (Docker files not executed on the dev machine, simulated external systems, static rates, single relay instance).

## Lessons learned

To be completed in the final prompt.
