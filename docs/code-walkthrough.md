# How the application works

The application gives a task to Qwen, executes validated tool requests, and returns actual tool results
to the model. Qwen never opens files directly. The Java application owns permissions and success criteria.
See the [LangChain4j and MCP guide](langchain4j-mcp.md) for the connection diagram and packages.

## Follow one inspection

1. `AgentApplication` reads configuration, creates the LangChain4j-backed `OllamaClient`, and opens
   `McpTools.connect(workspace)`. The MCP client launches `WorkspaceMcpServer` using the current Java
   executable and application classpath, then discovers `list_files` and `read_file` through MCP.
2. `AgentRunner` creates its own system/user history. The system prompt asks Analyst to inspect the
   repository using tools and support findings with Java-issued source evidence IDs.
3. `OllamaClient` converts this history and the tool descriptions to LangChain4j message types and
   calls `OllamaChatModel`. LangChain4j creates the Ollama request; the local-only HTTP extension
   sends it to the configured `/api/chat`, with thinking disabled and bounded context/output tokens.
4. Qwen may return a tool request such as `read_file` with `{"path":"README.md"}`. Java checks the
   actual call count and arguments before sending that call through the MCP client.
5. The MCP server validates the request again. `Workspace` resolves actual file paths and rejects
   paths outside the root, credentials, build outputs and binaries. Only bounded UTF-8 content is returned.
6. `EvidenceLedger` gives evidence IDs only to complete source rows actually delivered to the model.
   The resulting tool message joins this agent's history. The next model request includes it.
7. Java ends the tool phase at the configured limits and asks for structured findings.
   `AnalystReport` resolves evidence IDs to the observed paths and lines; unknown IDs are rejected.
   Source attribution proves the cited rows were observed, not that every model interpretation is correct.
8. The MCP connection/subprocess and model HTTP client close when the application finishes, including
   failure paths. Errors are reported in English.

## Development mode

`-TaskFile` selects `WorkflowRunner` instead. It copies safe source files into `.agent-runs/run-*/workspace`
and runs Analyst, Developer and Tester sequentially, each with independent history and scoped MCP tools.
These roles have separate classes in `agent`: `AnalystAgent`, `DeveloperAgent` and `TesterAgent`.
Each extends `RoleSession`, the shared conversation/tool engine. The role classes own their system
prompts; Developer and Tester also configure their raw-code reply adapter and archive directory.
`WorkflowRunner` creates these agents and coordinates their handoffs, patches and verification.
The task defines exact read/write paths; model output cannot expand them.

Developer proposes production code; Tester proposes independent JUnit tests. Their tools remain read-only.
Java's `PatchApplier` performs validated edits in the copy. `VerificationService` runs the fixed targeted
Gradle command and checks fresh JUnit XML. Success requires passing tests on modified code and failing
executed regression tests on original code with the same tests. Compilation errors or missing reports
cannot prove a regression. Nothing is automatically merged into the original PIM repository.

The [workflow guide](workflow-guide.md) describes task files, repair attempts, limits and artifacts.
