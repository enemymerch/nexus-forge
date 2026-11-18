# Local coding workflow

Coding-role repair requests with required file reads refresh the model's active context: role instructions,
current task/diagnostics and freshly read source replace obsolete proposals and tool messages. The complete
transcript remains in the saved role history. Step, actual tool-call and cumulative output budgets remain
unchanged. Analyst evidence history and tool-free final reviews retain their normal conversation behavior.

Model calls now use LangChain4j, and all application workspace tool calls use its MCP client with
our local Java stdio server. See [LangChain4j and MCP](langchain4j-mcp.md) for connection details.
The workflow and verification rules below remain controlled by Java.

The application can now carry out a small, explicitly scoped change request. You describe the expected
behavior in a task JSON file. Java coordinates three roles, applies proposed source edits to a separate
copy, runs the allowed Gradle test task, and records the result. All model requests use the configured
local Ollama endpoint. No OpenAI key, hosted model or Spring Boot dependency is needed.

## Run a task

Start Ollama with `qwen3:8b` available. From the `ai-try-one` directory:

```powershell
# Keep using the existing read-only inspection:
.\run.ps1

# Ask a different read-only question:
.\run.ps1 -Task 'Inspect the catalog domain model. Explain which files define variant options.'

# Run the included development task:
.\run.ps1 -TaskFile tasks/variant-option-validation.json

# Enforce Variant constructor invariants:
.\run.ps1 -TaskFile tasks/variant-code-validation.json

# Select a source repository explicitly:
.\run.ps1 -TaskFile tasks/variant-option-validation.json `
    -Workspace 'C:/Users/pc/Desktop/workspace/pim/nexus-pim'

# Show the actual model request/response JSON as well:
.\run.ps1 -TaskFile tasks/variant-option-validation.json -Trace
```

The launcher preserves the existing `-JavaHome`, `-MavenPath` and `-AdditionalMavenArguments` options.
Relative task-file paths are resolved against the launcher directory. `-Task` is a read-only question;
`-TaskFile` selects development mode. They cannot be combined. Task/path arguments are transported to
Maven using UTF-8 Base64 so embedded shell characters remain data.

The default source location is `C:/Users/pc/Desktop/workspace/pim`. In development mode, if that directory
does not itself contain the Gradle build and has exactly one suitable Gradle child, the application uses
that child. For this project it is `pim/nexus-pim`. Select an explicit `-Workspace` if discovery is ambiguous.

## What happens during one run?

1. **Java loads and validates the task.** It rejects excessive input, unsafe paths, unknown JSON keys,
   duplicate scopes and arbitrary build commands before involving the model.
2. **Java takes a snapshot.** It copies eligible current files, including safe local edits and untracked
   source, into a new run directory. It initializes a separate local Git baseline there; it does not
   reset, commit or change the source repository.
3. **Java reads the required scoped files for Analyst.** The task already names the relevant paths.
   Java dispatches `read_file` for each required file before model reasoning, using the same real
   path checks, call counts, output budgets and evidence ledger. An explicit transcript notice marks
   these Java-controlled reads. The model then returns the plan using evidence IDs from actual
   delivered rows; the final JSON schema restricts IDs to that ledger and Java checks them again.
   Partial output remains partial evidence. Required reads for Developer and Tester
   work the same way, including rereading the current edit target during repair. The original
   inspection mode still lets the model explore using `list_files` and `read_file`.
   Analyst receives structured rows with evidence IDs. Developer and Tester receive the same bounded
   source as plain code, with a file path, delivered line range and explicit truncation flag. This
   avoids spending their context on unused evidence metadata. Only complete source rows are delivered.
4. **Developer proposes complete production files.** It returns `summary` and an `edits` array whose
   entries contain only `path` and `content`. Each `content` is the complete resulting file, including
   its package declaration and closing delimiters. Java captures the current file before generation,
   rejects intervening changes, and converts the proposal into its own exact old/new replacement.
   It checks approved production paths and size limits, then writes only in the copy.
   For one approved code file, the local model returns ordinary source code (optionally fenced).
   `SingleFileCodeModel` packages that text into the validated internal proposal itself, preserving
   literal Kotlin/Java escapes. Coding roles archive their actual outbound request and raw reply
   under `<role>-raw-replies`; the role history contains the normalized proposal. Multi-file proposals
   keep the structured format. This adapter makes exactly one model call per role step.
5. **Tester writes independent regression tests.** Its own conversation receives the task and relevant
   source, and proposes complete files only for the approved test paths. For an absent test file matching
   the configured test class, Java creates an empty JUnit 5 class with package/imports inside the copy.
   Tester reads this scaffold and supplies the test methods and assertions. The scaffold has no tests
   and cannot establish success. Existing test files are preserved. Developer cannot edit those tests.
   Imports already delivered from approved production source may be reused in the empty scaffold.
6. **Java runs the configured Gradle check.** It passes a fixed executable and validated arguments,
   forces task execution, removes prior matching XML reports, captures the actual exit code and reads
   fresh JUnit XML. Skipped or absent tests do not establish success.
7. **A bounded repair loop handles failures.** Test compilation problems go back to Tester; implementation
   failures go back to Developer. Routing reads up to 64 KB of the actual command log so a shortened
   console excerpt cannot hide a failed test-compilation task. Each role retains its history and consumed model/tool budgets. The task
   also caps attempts, so failure cannot trigger an unlimited loop. Environment failures, unavailable
   dependencies and timeouts halt the run without asking a role to change source to work around them.
8. **Java checks the original behavior.** It makes another source snapshot and applies only the final
   Tester test changes. Those tests must execute and fail on the unchanged production code. A setup error,
   missing dependencies or compilation failure does not prove a useful regression test.
9. **The changed copy must be green and the baseline red.** If the original also passes, Tester must
   strengthen its tests within the remaining limits. If that cannot be established, the run reports the
   limitation and does not claim verified success.
10. **Java writes the result and diff.** The report identifies changed files, real checks, baseline evidence,
    attempts and remaining limitations. The source repository remains unchanged; inspect the artifacts
    before bringing any change into your normal development branch.

These are sequential roles using one local model, rather than three simultaneous model instances.
Each role has a separate `RoleSession`, conversation, evidence ledger and counters. This keeps the first
version practical for a machine with 8 GB VRAM and makes the control flow easier to inspect.

## The included task

[`variant-option-validation.json`](../tasks/variant-option-validation.json) asks for a small change to:

```text
services/catalog/src/main/kotlin/com/nexus/catalog/domain/model/VariantOption.kt
```

Expected behavior:

- Empty or whitespace-only `attributeCode` throws `IllegalArgumentException`.
- Empty or whitespace-only `attributeValueCode` throws `IllegalArgumentException`.
- Nonblank values are preserved exactly, including surrounding spaces.
- Six separate JUnit 5 tests cover normal valid values, exact preservation of surrounding spaces in
  both properties, empty `attributeCode`, whitespace-only `attributeCode`, empty `attributeValueCode`,
  and whitespace-only `attributeValueCode`.

Only that production file and its named test file can be changed. The configured check is
`:services:catalog:test` with the exact filter `com.nexus.catalog.domain.model.VariantOptionTest`.

To create another task, copy the example and adjust these fields:

| JSON field | Purpose and enforced limit |
| --- | --- |
| `name` | Short label, 1..120 characters |
| `goal` | Requested behavior, 1..3000 characters |
| `read_files` | 1..12 exact relative paths, using forward slashes |
| `write_files` | 1..4 approved Java/Kotlin files; the writer further requires safe paths under `src` |
| `acceptance_criteria` | 1..8 nonblank statements, each at most 600 characters |
| `gradle_task` | One module test task such as `:services:catalog:test`; no flags or command separators |
| `test_filter` | One fully qualified test class; no wildcards or extra arguments |
| `max_attempts` | 1..3 bounded attempts |

Task files must be strict UTF-8 JSON no larger than 32768 bytes, with all required fields and no unknown
or duplicate keys. Scope is enforced by Java; text inside a task, file or model response cannot expand it.
Keep tasks small enough that the relevant source and generated patch fit the model's short context.

The model-facing proposal format is `summary` plus `edits: [{"path": "...", "content": "..."}]`.
It does not ask the model to copy matching `old_text` or construct partial `new_text` replacements.
Even a small correction supplies the complete resulting file. Both an existing file captured for this
workflow and each proposed complete file are limited to 8000 characters. Prefer small files whose
contents fit a complete delivered read; truncated content remains uninspected.

Java owns the previous-content snapshot and checks that the target has not changed before applying the
proposal. It then passes exact old/new text to the internal `PatchApplier`, which still enforces approved
source paths, atomic validation and consistent CRLF handling. This separates code generation from exact
text matching and avoids partial replacements that accidentally omit or duplicate closing delimiters.

## Configuration

The launcher sets the mode for `-TaskFile`; direct Maven launches can use ordinary system properties:

```powershell
$env:JAVA_HOME = 'C:/Users/pc/.jdks/corretto-21.0.1'
$mvn = 'C:/Users/pc/maven-mvnd-1.0.2-windows-amd64/mvn/bin/mvn.cmd'
& $mvn compile exec:java '-Dagent.mode=develop' `
    '-Dagent.taskFile=tasks/variant-option-validation.json' `
    '-Dagent.workspace=C:/Users/pc/Desktop/workspace/pim/nexus-pim'
```

| Setting | Use |
| --- | --- |
| `agent.mode` | `inspect` by default; `develop` starts the scoped workflow |
| `agent.task` | Custom read-only question for inspection |
| `agent.taskFile` | Development task JSON path |
| `agent.workspace` | Source repository location |
| `agent.model` | Local Ollama model, default `qwen3:8b` |
| `agent.endpoint` | Local chat endpoint, default `http://localhost:11434/api/chat` |
| `agent.maxSteps` | Per-role model-call budget; default 16 in development, 8 in inspection; cumulative across repairs |
| `agent.maxToolCalls` | Per-role dispatch-attempt budget; default 10 in development, 6 in inspection; invalid calls consume it too |
| `agent.contextTokens` | Ollama context window; default 6144 in development, 4096 in inspection; allowed range 2048..8192 |
| `agent.predictionTokens` | Output token limit; default 2048 in development, 1024 in inspection; allowed range 256..4096 |
| `agent.trace` | Prints model request/response JSON; includes repository excerpts |
| `agent.runsDirectory` | Run-artifact directory; default `.agent-runs` under the application working directory |
| `agent.gradleExecutable` | Installed Gradle executable outside the editable copy |
| `agent.offline` | `true` by default; `false` allows Gradle to obtain missing dependencies |
| `agent.checkTimeoutSeconds` | Per-command timeout; default 180 seconds, allowed range 5..600 |

For example, to use an explicitly installed Gradle distribution:

```powershell
.\run.ps1 -TaskFile tasks/variant-option-validation.json `
    -AdditionalMavenArguments '-Dagent.gradleExecutable=C:/Tools/gradle-8.8/bin/gradle.bat'
```

The application discovers an existing Gradle 8.8 distribution in the user's Gradle wrapper cache when
no override is supplied. It uses the installed executable because binary files, including the wrapper
JAR, are excluded from source copies. Allowing dependency downloads does not change model routing;
the LLM remains local. Console diagnostics and workflow artifacts use English.

If offline dependency resolution is the reported blocker, allow Gradle to fetch dependencies explicitly:

```powershell
.\run.ps1 -TaskFile tasks/variant-option-validation.json `
    -AdditionalMavenArguments '-Dagent.offline=false'
```

Missing dependencies, environment setup errors and timeouts are reported as incomplete verification.
The workflow stops on those conditions instead of generating unrelated source repairs.

`RoleSession` enforces 2..24 model steps, 1..20 tool dispatches and a configurable constructor limit of
4000..24000 serialized history characters. The development workflow uses a fixed 20000-character cap.
Each tool result is bounded after evidence IDs are added: at most 2400 characters per result and 8000
tool-output characters per role in total. Java preserves its history; overflowing that character limit
fails explicitly. A model response cannot exceed
16000 characters. Limits still apply to repair requests.

The Java character cap and Ollama's token window measure different things. The application does not
calculate the exact token count, so 20000 serialized characters do not guarantee a fit in a model's
token window. A real Qwen repair run with a 4096-token context lost earlier context; development now
defaults to 6144 tokens and uses focused reads to reduce that pressure. Ollama may still truncate older
input when its window is exceeded, even though the complete Java history remains in the transcript.
Keep tasks and repair conversations short. Increasing
`agent.contextTokens` changes the model window and uses more memory; choose it with the local GPU budget
in mind.

## Artifacts and review

Development runs are stored under `.agent-runs/run-*`:

```text
run-*/
  workspace/                 Editable source snapshot and its independent Git baseline
  task.json                  The validated task
  analysis.json              Analyst plan with checked evidence references
  developer-*-proposal-*.json Proposed initial/repair production edits
  tester-tests*.json         Independently proposed test edits
  *history.json              Separate role conversations with actual tool outputs
  checks/                    Actual changed-workspace command logs
  baseline/                  Original-code copy and regression-check artifacts
  changes.diff               Final diff against the snapshot baseline
  report.json                Structured outcome
  report.md                  Human-readable outcome and limitations
```

Read `report.md` first. Then inspect `changes.diff`, the changed tests and the corresponding logs.
The XML files inside each test workspace show which tests actually executed. A failed run can still
contain useful proposed changes, but its diff is not marked as verified. There is no automatic merge
or overwrite of the original repository.

## Package responsibilities

| Package/class | Responsibility |
| --- | --- |
| `AgentApplication` | Reads configuration, selects mode and connects dependencies |
| `config.DevelopmentTask` | Validates the user task and bounded scope |
| `agent.AgentRunner` | Existing read-only Analyst inspection loop |
| `agent.RoleSession` | One role's history, model/tool loop, schema final step and budgets |
| `agent.AnalystAgent` | Analyst instructions and evidence-backed planning session |
| `agent.DeveloperAgent` | Developer instructions and production-code reply adapter |
| `agent.TesterAgent` | Tester instructions, test-code reply adapter and review session |
| `workflow.WorkflowRunner` | Role handoffs, source/test ownership, checks, repairs and reports |
| `workflow.ScopedReadTools` | Read tools limited to task-relevant paths |
| `workflow.GradleChecks` | Builds the application-owned Gradle check arguments |
| `workspace.Workspace` | Bounded real-path-checked file reads and listings |
| `workspace.run.IsolatedWorkspace` | Safe source snapshot, original hashes and independent Git diff |
| `workspace.run.PatchApplier` | Internal exact scoped source edits after Java converts complete-file proposals |
| `verification` | Timeout, process output, exit code and fresh JUnit XML validation |
| `verification.CheckDiagnostics` | Bounded actual-log inspection for compilation/discovery failure routing |
| `report.EvidenceLedger` | Records only source lines actually delivered to the role |
| `llm.ollama.OllamaClient` | LangChain4j Ollama adapter; arbitrary response schemas for final calls |
| `mcp.McpTools` | LangChain4j MCP client; fixed local stdio subprocess per role |
| `mcp.WorkspaceMcpServer` | Official SDK server exposing scoped read-only workspace tools |
| `llm.SingleFileCodeModel` | Audits raw single-file code replies and packages their exact source into internal proposals |

## What a successful result proves

Java requires real check evidence; model statements cannot replace it. A successful targeted run shows
that the selected regression class executed successfully against the changed snapshot and failed against
the original implementation. It also confirms the application did not observe changes to included original
source files while running. Evidence IDs establish source references, not the semantic correctness of a claim.

This is a focused workflow, not an unrestricted coding assistant. It does not prove the whole PIM build,
every test suite, service integration, database behavior or production readiness. The selected tests can
miss requirements, and generated tests still deserve review. Existing unrelated source problems or missing
offline dependencies may prevent a check; those are reported as incomplete verification rather than success.

The copy excludes build output, binary and credential filenames, and rejects symbolic-link/junction paths.
A filename policy cannot discover every secret embedded in normal source text. Workspace isolation protects
the original from the application's edit operations; Gradle still runs project code as the current user,
so the copy is not an operating-system process sandbox.

To learn the implementation, read `AgentApplication`, `DevelopmentTask`, `WorkflowRunner`, then follow
one `RoleSession` through **model request -> tool dispatch -> bounded result -> final JSON**. Compare its
history artifact with the real Gradle logs and the final diff.
