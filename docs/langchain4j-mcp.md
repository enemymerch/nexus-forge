# LangChain4j and MCP

The application uses two independent connections:

```text
AgentRunner / RoleSession
  | model request
  +--> OllamaClient adapter --> LangChain4j OllamaChatModel --> local Ollama --> Qwen
  |
  | approved tool call
  +--> ToolRegistry --> LangChain4j MCP client
                         | JSON-RPC over stdin/stdout
                         v
                       WorkspaceMcpServer (separate local Java process)
                         +--> ProjectTools / ScopedReadTools --> Workspace --> files
```

LangChain4j manages model protocol conversion and the MCP client. The application deliberately owns
the agent loop: step counts, actual tool-call counts, output/context budgets, independent role histories,
evidence validation, patch scope and real verification remain Java-controlled. There is no automatic
framework tool loop or model retry that could bypass those limits. Existing single-file raw-code
proposals still work: Java supplies the approved path and retains the actual model replies.

MCP standardizes tool discovery and execution; it does not run Qwen. Ollama still serves the local
model using HTTP. MCP uses stdio here, so it opens no listening port. Only our fixed Java server class
can be launched; model output cannot select executables, server addresses or workspace configuration.

## Packages and responsibilities

| Package | Responsibility |
| --- | --- |
| `llm.ollama` | Adapt existing role histories to LangChain4j messages and responses. |
| `llm.ollama.LocalOllamaHttpClient` | LangChain4j HTTP extension enforcing the exact local endpoint, disabled redirects, bounded response bodies and explicit close. It does not construct Ollama requests. |
| `mcp.McpTools` | Launch the fixed local JVM, negotiate MCP, discover the two tools, adapt their results and close the client/server connection. |
| `mcp.WorkspaceMcpServer` | Official Java MCP SDK server exposing only `list_files` and `read_file`. stdout contains protocol messages only. |
| `tool`, `workflow.ScopedReadTools` | Validate tool arguments and results; enforce the approved read scope inside the server process. |
| `workspace` | Resolve actual paths; exclude build/binary/credential files; read bounded UTF-8 text. |
| `agent`, `workflow`, `verification`, `report` | Own orchestration, budgets, isolated edits, fixed Gradle checks and evidence. |

`AnalystAgent`, `DeveloperAgent` and `TesterAgent` are separate classes under `agent`. They extend
the shared `RoleSession` engine and each owns its role instructions. Coding roles also configure
their source-reply adapter. `WorkflowRunner` creates and coordinates these typed agents.

Each workflow role gets a distinct MCP subprocess whose root and allowed file set are fixed at startup.
Missing approved files can be created later by Java's patch mechanism and then read within that scope.
The server has no write, shell, sampling or external-model tools. MCP read access is constrained by
Java checks; it is not an operating-system sandbox. Development mode still writes only the isolated
copy and runs only the existing Java-controlled verification commands.

## Run

Keep Ollama running with `qwen3:8b` installed, then use the existing launcher:

```powershell
.\run.ps1
.\run.ps1 -Workspace 'C:/Users/pc/Desktop/workspace/pim/nexus-pim' -Task 'Inspect README and root build files.'
.\run.ps1 -TaskFile tasks/variant-code-validation.json
.\run.ps1 -Trace
```

`-Trace` prints the actual Ollama JSON request/reply and MCP messages. File contents can appear in traces.
Normal console messages remain English. Existing configuration properties remain supported, including
`agent.model`, `agent.endpoint`, `agent.workspace`, role limits and test timeouts. The endpoint must be
local HTTP with path `/api/chat`; cloud model names and redirects are rejected. For example:

```powershell
.\run.ps1 -AdditionalMavenArguments @('-Dagent.model=qwen3:8b', '-Dagent.contextTokens=4096')
```

Dependencies are pinned: LangChain4j Ollama/core `1.23.0`, LangChain4j MCP `1.23.0-beta33`,
MCP Java SDK `0.18.4`. The MCP module's beta version is its published version; it matches the same
LangChain4j release line. The client explicitly negotiates protocol `2025-11-25` with this server.
No Spring Boot or hosted model dependency is included. The first Maven run downloads Java libraries;
all runtime model requests go to the configured local Ollama endpoint.

## Verification

Run `mvn verify` with Java 21. Tests include real MCP stdio subprocess round trips, scope separation,
UTF-8, unsafe paths and excluded files, bounded output, as well as mock local HTTP tests of the actual
LangChain4j Ollama adapter. Existing workflow tests retain model fakes but now read files through real
MCP connections. A real Qwen run is a separate integration exercise, since generated plans/code are
not deterministic. A model's success claim never replaces fresh test reports or regression evidence.

The pre-migration application snapshot is under `.local-backup/langchain4j-mcp-before`.
Existing `.agent-runs` artifacts and the original PIM sources are retained.

References: [LangChain4j Ollama](https://docs.langchain4j.dev/integrations/language-models/ollama/),
[LangChain4j MCP](https://docs.langchain4j.dev/tutorials/mcp/),
[MCP Java SDK server](https://java.sdk.modelcontextprotocol.io/latest/server/).
