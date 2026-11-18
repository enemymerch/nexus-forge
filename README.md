# Nexus Forge - Local coding agents

Java 21 + Maven + LangChain4j + MCP. Only local Ollama, no API key. Inspection uses read-only list_files/read_file tools.
The development workflow runs Analyst, Developer and Tester sequentially in an isolated repository copy.

LangChain4j's `OllamaChatModel` handles model messages, tool specifications and structured responses.
Its MCP client starts our read-only Java workspace server over stdio automatically; no Node.js,
Docker, separate server installation or extra port is needed. Each role has its own connection,
history and limits. See the [LangChain4j and MCP guide](docs/langchain4j-mcp.md) for the data flow,
package responsibilities and migration details.

## Development workflow

To give Analyst a natural-language directive and let the workflow delegate development and testing:

```powershell
.\run.ps1 -Interactive
# Enter a focused directive when prompted. No task JSON needs to be authored manually.
```

Analyst discovers and reads source through MCP, proposes a bounded task and cites observed evidence.
Java validates the task, source paths and fixed verification command before starting Developer and Tester.
The generated task, plan and Analyst transcript are saved in `.agent-runs/directive-*`; implementation
and verification artifacts remain in their isolated `run-*` directories. The original PIM is not merged
automatically. One invocation handles one small task; unsupported or overly broad plans can fail validation.
`-Interactive` cannot be combined with `-Task` or `-TaskFile`. Frontend bootstrap currently covers categories.

Keep Ollama running with `qwen3:8b`, then run from this directory:

```powershell
# Existing read-only repository inspection:
.\run.ps1

# Supply your own read-only question:
.\run.ps1 -Task 'Inspect the catalog domain model and explain the relevant classes.'

# Analyze, change, write independent tests, run Gradle and produce a reviewable diff:
.\run.ps1 -TaskFile tasks/variant-option-validation.json

# Enforce existing Variant code rules for direct constructor calls:
.\run.ps1 -TaskFile tasks/variant-code-validation.json

# Explicit repository root and optional full model trace:
.\run.ps1 -TaskFile tasks/variant-option-validation.json `
    -Workspace 'C:/Users/pc/Desktop/workspace/pim/nexus-pim' -Trace
```

`-Task` and `-TaskFile` are mutually exclusive. The example development task rejects blank variant-option
codes and adds focused JUnit 5 tests. Task JSON specifies the goal, exact read/write paths, acceptance
criteria, module test task, test class and a maximum of three attempts. Arbitrary command strings are rejected.
The second task, `variant-code-validation.json`, closes the constructor validation bypass in `Variant`
and checks valid boundaries plus invalid input without requiring a database or Spring context.
For development tasks Java reads the explicitly required files through the bounded read tools before
each role reasons about them. The read-only inspection mode retains model-selected tool calls.
Developer and Tester propose complete files as `path` plus `content`; Java captures the previous file,
rejects intervening changes and applies only approved source edits. Existing/proposed workflow files
are limited to 8000 characters each.

Each run creates `.agent-runs/run-*` with a copied `workspace`, model transcripts, real check logs,
`changes.diff`, and machine-readable/human-readable reports. The original repository is never automatically
updated or merged; safe local uncommitted files are included in the snapshot and checked for unexpected changes.

Success requires passing tests on the changed copy **and failing executed regression tests on the original
code with the same Tester tests**. A model saying "done", a successful command with no fresh test results,
or an unverified baseline cannot establish success. Failures leave artifacts for inspection.

Gradle checks use an installed Gradle 8.8 executable outside the editable copy. By default they run offline
with cached dependencies; configure `agent.gradleExecutable` if automatic discovery fails, or explicitly set
`agent.offline=false` when dependencies need downloading. Model requests still go only to local Ollama.
Environment, dependency and timeout failures halt verification without asking the model to change source.

Read the [workflow guide](docs/workflow-guide.md) for the complete execution sequence, configuration,
package responsibilities, artifact layout and current limits. The following sections describe the original
read-only inspection mode.

## Package structure

Application entry point: `com.mcan.nexusforge.AgentApplication` (configured for Maven and IntelliJ).
Test packages mirror the production packages.

```text
com.mcan.nexusforge
├── AgentApplication.java
├── agent/
│   ├── AnalystAgent.java
│   ├── DeveloperAgent.java
│   ├── TesterAgent.java
│   ├── RoleSession.java
│   └── AgentRunner.java
├── llm/
│   ├── ChatModel.java
│   └── ollama/
│       ├── OllamaClient.java
│       └── LocalOllamaHttpClient.java
├── mcp/
│   ├── McpTools.java
│   └── WorkspaceMcpServer.java
├── tool/
│   ├── Tool.java
│   ├── ToolRegistry.java
│   ├── ToolOutput.java
│   └── project/
│       └── ProjectTools.java
├── workspace/
│   └── Workspace.java
└── report/
    ├── EvidenceLedger.java
    └── AnalystReport.java
```

Start with the [code walkthrough](docs/code-walkthrough.md) for the execution flow and each class's role.

## Run (PowerShell)

From the project root, the launcher configures UTF-8 console/pipeline encoding and starts Maven:

```powershell
.\run.ps1
# To display model request/response JSON:
.\run.ps1 -Trace
```

It uses JAVA_HOME (or the installed Corretto 21 under your user profile) and Maven on PATH
(or the local mvnd distribution). Override with `-JavaHome` and `-MavenPath` when needed.
Existing agent settings can be passed with `-AdditionalMavenArguments '-Dagent.model=qwen3:8b'`.
The script preserves Maven's exit code, including report-validation failures.

For manual Maven use:

```powershell
[Console]::InputEncoding = [System.Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$env:JAVA_HOME = 'C:/Users/pc/.jdks/corretto-21.0.1'
$mvn = 'C:/Users/pc/maven-mvnd-1.0.2-windows-amd64/mvn/bin/mvn.cmd'
& $mvn test
& $mvn exec:java '-Dagent.workspace=C:/Users/pc/Desktop/workspace/pim' '-Dagent.model=qwen3:8b' '-Dagent.endpoint=http://localhost:11434/api/chat'
```

`.mvn/jvm.config` sets UTF-8 for Maven's JVM and stdout/stderr. IntelliJ's project encoding
and the existing AgentApplication run configuration also use UTF-8; new run configurations
can use `-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8` as VM options.

Ollama must already serve qwen3:8b. Standard Maven on PATH also works: `mvn test exec:java`.
Optional JVM/Maven properties: agent.maxSteps (default 8, range 2..12), agent.maxToolCalls (default 6, range 1..12).
The final step is reserved for a report. Each AgentRunner owns its history and can run once.
Context: 4096 tokens, prediction: 1024 tokens, think=false, stream=false. One agent, no concurrency.
Console diagnostics, errors and report/audit labels are in English. Model-generated findings remain in Turkish.

## Boundaries and evidence

Workspace validates normalized paths and real paths, including links and excluded destinations.
Build directories, common credential filenames, binary extensions and non-UTF-8/control-byte content are denied.
A filename policy cannot detect every secret embedded in ordinary source or README files.
Read limit: 64000 bytes; read/list JSON: 6500 characters; per-call agent context: 2400 characters; listing: 100 entries. Aggregate tool context: 8000 characters.
Truncation is explicit. Only relative paths are accepted. Inspection exposes no write or command execution tools.
Tool failures return ERROR results. Unknown and malformed calls consume the call budget.
HTTP endpoint accepts only literal loopback names; redirects are explicitly disabled by the LangChain4j HTTP extension.
Read tools return canonical paths and numbered complete lines. EvidenceLedger adds Java-generated evidence IDs
to nonblank lines, then applies the output budget again to include the ID overhead. Budgeting drops whole rows
and marks truncated=true. Only the final delivered lines are registered as usable evidence.
Listings, errors and blank lines do not receive evidence IDs. Listings cannot establish file contents.
Final synthesis uses an Ollama JSON schema: each finding contains `claim` and `evidence_ids`, such as `["E1"]`.
The model does not copy file paths, line numbers, quotes or source kinds into the report JSON.
AnalystReport resolves IDs through EvidenceLedger; Java supplies the exact stored path, line, text and source kind.
Unknown, undelivered and stale IDs are rejected. A new successful read of a path replaces its previous IDs,
so a report must use the IDs from the latest delivered read of that file.
Unsupported findings are rejected; invalid JSON or zero accepted findings fails the run.
If a model step remains, a report with invalid references gets one report-only repair attempt, within the step limit.
Resolving an evidence ID validates its source reference, not the semantic truth of the model's interpretation.
README findings are document statements; configuration/source findings do not prove runtime behavior.
The target build/tests are never executed by Analyst. Unread content remains unknown.
Concurrent hostile changes to workspace links during a read are outside this initial version's threat model.

Original application files were preserved under .local-backup before changes (this directory had no Git metadata).

For the evidence/tracing experiment, see [docs/analyst-walkthrough.md](docs/analyst-walkthrough.md).
Use `mvn exec:java -Dagent.trace=true` to inspect request/response JSON; this prints repository excerpts too.
