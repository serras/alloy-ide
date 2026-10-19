# Design

## Context

See proposal.md (Why) for motivation. The current state of the repository:

- A plugin skeleton (`com.serranofp.alloy-ide`) built with IntelliJ Platform Gradle Plugin 2.19 and Kotlin 2.4, targeting IntelliJ IDEA 2026.2.3. `plugin.xml` depends on `com.intellij.modules.lsp` and registers no extensions. There is no source code yet.
- An uncommitted `implementation(libs.alloy.lsp)` line in `build.gradle.kts` puts `org.alloytools.alloy.lsp:6.2.0` and its whole closure (alloy core, pardinus, SAT4J, lsp4j **0.4.1**, gson, …) on the plugin classpath. The IDE ships a much newer lsp4j, so loading both in one classloader is not viable.

Facts about the upstream server (from the 6.2.0 sources) that shape the design:

- `AlloyLanguageServer.main` with no arguments speaks LSP over stdio and logs only to stderr. It advertises full text sync, definition, hover, references, rename, document/workspace symbols, **code lens** and **document links** (the last two carry `ExecuteAlloyCommand` commands). It does not implement completion, formatting or `workspace/didChangeConfiguration`.
- Custom protocol (no JSON segment, so the method names are used as is):
  - client → server requests: `ListAlloyCommands(uri)`, `ExecuteAlloyCommand([uri, index, line, character])`, `StopExecution(_)`, `OpenModel(link)`. The server declares their parameters as Gson `JsonPrimitive`/`JsonArray`/`Object`, so `params` must be the bare string (`"file:///m.als"`) or the bare array (`["file:///m.als", 1, 3, 0]`). A wrapped form such as `["file:///m.als"]` fails with "argument type mismatch".
  - server → client notifications: `alloy/commandsListResult {commands: [{title, command}]}` and `alloy/showExecutionOutput {messageType, message, link, bold, replaceLast, lineBreak}` with `messageType` sent as the **ordinal** of the enum `RunStarted | RunInProgress | RunResult | RunCompleted | Warning` (0–4), because lsp4j 0.4.1 serializes enums by ordinal. When a file has errors, `commandsListResult` has an empty `commands` list.
  - `ExecuteAlloyCommand` returns immediately and runs asynchronously. It **silently does nothing** if the analyzer (`WorkerEngine`) is already busy. Messages do not carry a run id.
  - Instance links look like `XML: <file>` and open a Swing `VizGUI` in the server process. Core links look like `CORE: <file>`, and the server re-publishes them as warning diagnostics ("part of unsat core").
- Analyzer options are read from `A4Preferences`, which stores values through `java.util.prefs.Preferences.userNodeForPackage(edu.mit.csail.sdg.alloy4.Util.class)`. That is the same store the Alloy desktop app uses. Preference ids: `SatSolver2`, `SkolemDepth3`, `NoOverflow`, `CoreMinimization`, `CoreGranularity`, `WarningNonfatal`, `RecordKodkod`, `InferPartialInstance`.
- Solvers come from `SATFactory.getAllSolvers()` filtered by `isPresent()`. The Maven artifacts only contain the pure-Java SAT4J variants. Other factories need native libraries (never present) or executables on `PATH`.
- `Alloy.lex` / `Alloy.cup` are not inside the published jars. They have to be taken from the upstream repository at tag `v6.2.0`.

## Goals / Non-Goals

**Goals:**
- Keep native (lexer/PSI) features independent of the server, so the editor stays usable if the server is down.
- Never load a server class into the IDE. The only contract between the two is JSON-RPC over stdio.
- Treat the upstream server as a black box: no patching or forking of Alloy. Any adaptation goes into a small shim that runs *inside* the server process.

**Non-Goals:**
- Resolving references natively (the PSI has no reference resolution). All semantic navigation goes through the server.
- Running several analyses in parallel in one project, or a "run all commands" action.
- Configuring the solver subprocess's memory/stack (the analyzer defaults apply).
- Supporting Alloy versions other than the bundled 6.2.0.

## Decisions

### 1. Lexer: JFlex port of upstream `Alloy.lex`; parser: Grammar-Kit BNF
Copy `Alloy.lex` from upstream `v6.2.0`, keep its license header, and adapt it to IntelliJ's `FlexLexer` skeleton (an `IElementType` per token class, `BAD_CHARACTER` fallback, and states for block comments). Generate the parser from a hand-written `Alloy.bnf` whose top-level rules mirror the paragraph productions of `Alloy.cup`. Run both generators with the `org.jetbrains.intellij.platform.grammarkit` subplugin of the IntelliJ Platform Gradle Plugin into `build/generated/`, registered as a source root. The standalone `org.jetbrains.grammarkit` plugin is archived and cannot resolve the 2026.2 platform.
- *Why over a hand-written lexer:* a mechanical port keeps token coverage identical to the upstream lexer, which the spec requires, and makes later upstream diffs easy to apply.
- *Why over reusing upstream `CompLexer` at runtime:* that would pull Alloy core into the IDE classloader, which this design explicitly avoids.

### 2. Shallow PSI with balanced blocks
Bodies are parsed by a recursive `block ::= '{' block_item* '}'` where `block_item` is any non-brace token or a nested block (same idea for `[...]` parameter lists and command scopes). Each paragraph rule uses `pin` and a `recoverWhile` that stops at the next paragraph-start keyword, so one broken paragraph does not poison the rest of the file. The PSI has element types for the paragraphs and commands (with a name child where present). That is enough for folding, run markers and command lookup.
- *Alternative:* a full expression grammar. Rejected (out of scope in the proposal). Alloy's operator precedence and its ambiguities around `[]`, `.` and `'` are expensive to reproduce, and the server already type-checks.

### 3. LSP integration through the platform LSP API
An `LspIntegrationProvider` starts a `ProjectWideLspClientDescriptor` for files with the Alloy file type (extension point `com.intellij.platform.lsp.integrationProvider`). In 2026.2 these replace the deprecated `LspServerSupportProvider`/`ProjectWideLspServerDescriptor`, which the Plugin Verifier would report. `lspCustomization` disables code lens and document links, which removes the server's Execute affordances. It also disables completion and formatting so the native keyword completion is used. Diagnostics, hover, definition, references, rename, document symbols (structure/breadcrumbs) and workspace symbols keep the platform defaults. The descriptor overrides `createLsp4jClient` to handle the two `alloy/*` notifications, and `lsp4jServerClass` to an interface declaring the four custom requests. The IDE bundles lsp4j 0.24, which wraps a single `String` or `List` parameter in an extra array but writes Gson elements unchanged. So the interface declares the parameters as `JsonPrimitive`/`JsonArray`/`JsonObject`, mirroring upstream, and typed helper functions (`listAlloyCommands(uri)`, `executeAlloyCommand(args)`, `stopExecution()`, `openModel(link)`) build them. The client decodes `messageType` from its ordinal.
- *Why not LSP4IJ:* the target platform provides every feature needed, and the proposal limits the plugin to IDEs that have `com.intellij.modules.lsp`.

### 4. Server distribution in `lib/server/`, launched on the IDE's JBR
Add a Gradle configuration `alloyServer` that does **not** extend `implementation`/`runtimeClasspath`. It resolves `libs.alloy.lsp` plus the shim jar (decision 5). The `prepareSandbox` task (and so `buildPlugin`) copies that configuration into `<plugin>/lib/server/`. The IDE builds the plugin classloader only from jars directly inside `lib/`, so nothing in the subdirectory is ever loaded. At runtime the descriptor builds a `GeneralCommandLine` with:
- `<java.home of the running IDE>/bin/java`, `-cp <plugin>/lib/server/*`, the shim main class,
- one `-Dalloy.ide.pref.<id>=<value>` per analyzer setting (decision 6),
- no `java.awt.headless`, so the visualizer can open. On macOS also `-Dapple.awt.application.name=Alloy`.

Remove `implementation(libs.alloy.lsp)`.
- *Alternative:* shading/relocating lsp4j 0.4.1 into the plugin. Rejected: the server would still run in-process, sharing the IDE's AWT and `System.exit` on `exit`.

### 5. Server-side shim (`server-shim` Gradle subproject, plain Java)
A small jar compiled against `alloy.lsp`, with two entry modes:
- `serve` (default): installs the isolated preferences (decision 6), then calls `AlloyLanguageServer.main(new String[0])` (stdio).
- `list-solvers`: prints one JSON line `[{"id":…,"name":…}]` for `SATFactory.getAllSolvers()` filtered by `isPresent()`, then exits.

It is the only code that touches Alloy classes. It gets plain JUnit tests in its own subproject.

### 6. Settings isolation via a custom `PreferencesFactory`
The shim sets `java.util.prefs.PreferencesFactory` (as a `-D` on the command line, so it applies before any `Preferences` class initialization) to an in-memory `AbstractPreferences` implementation. On first access, the user node is seeded from `alloy.ide.pref.*` system properties. Writes stay in memory and are discarded on exit. This covers both directions of isolation (nothing read from, nothing written to the desktop store), including writes made by `VizGUI`. Settings changes take effect by restarting the server, because the server has no configuration request.
- *Alternative:* a `-Duser.home` override. Rejected: on macOS/Windows the preference store does not live under `user.home`, and it would also move the server's temp and solver directories.

### 7. Settings model and page
An application-level `PersistentStateComponent` (`AlloySettings`, stored in `alloy.xml`) holds the eight options with the upstream defaults. Each option maps to its preference id and string encoding (an int choice is stored as its integer, a boolean as `y`/`n` because `A4Preferences.BooleanPref` treats anything other than `y` as false, the solver as its `SATFactory` id, e.g. `sat4j`). A `Configurable` under `Languages & Frameworks` renders them with the Kotlin UI DSL. The solver combo box is filled from `list-solvers`: the call runs in the background when the page opens, its result is cached for the IDE session, and the page shows a loading state meanwhile. If a saved solver is not in the list, the page shows it as "(unavailable)". At launch, the plugin then sends `sat4j` instead and prints a warning at the start of the next run. On `apply()` with modifications, the plugin terminates any active Alloy run (decision 9) and calls `LspClientManager.getInstance(project).stopAndRestartClientsIfNeeded(AlloyLspIntegrationProvider::class.java)` for each open project.

### 8. Command identity: gutter by PSI, execution by server list
The `RunLineMarkerContributor` attaches to the `run`/`check` keyword leaf of each top-level command PSI element. The run configuration (`AlloyCommandRunConfiguration`, created by a `LazyRunConfigurationProducer`) stores the file path, the command's label (if any), and its ordinal among the file's commands. The label is the first name before the scope: `show` in `run show {...}`, `run show for 3` and `run show target`; `run {...}` has none. In a `=>` chain only the first command gets a gutter icon and a label.

At launch, the plugin sends `ListAlloyCommands(uri)`, waits (with a timeout) for `alloy/commandsListResult`, and picks the entry by label when there is one, otherwise by ordinal. The server's titles look like `Run show for 3` or `Check noCycles for 3`, so the label is the word after `Run`/`Check`. It then calls `ExecuteAlloyCommand` with **the server's own** `[uri, index, line, character]` arguments. Taking positions from the server avoids having to reproduce exactly where Alloy's `Command.pos` points. If the server reports a parse error (empty list) the run reports the errors. If the label/ordinal no longer exists, the run fails with "command not found".

The server only analyzes text it received through `didOpen`/`didChange`, and silently ignores `ExecuteAlloyCommand` for a document it has not seen. So the launch first opens the model file in an editor if it is not open yet (without focusing it, for example when a saved configuration is started from the toolbar). The platform then sends `didOpen` and keeps the server in sync with every edit, so unsaved edits are analyzed, as the spec requires. Opening the file also starts the server if needed, so the launch waits (up to 15 s) for a running server before it reports the server as unavailable.

### 9. Run console: a process-less `ProcessHandler` per run
`AlloyCommandRunState` creates an `AlloyRunProcessHandler` (no OS process) and registers it as the project's single *active run* in a project service. If an active run already exists, launch fails with "An Alloy analysis is already running". This check is needed because the server would drop the second request silently. The custom LSP client routes every `showExecutionOutput` to the active handler, which renders it on the console:
- The text is printed exactly as received: it already contains its line breaks. `lineBreak = false` only marks a message that the next one continues (`"   Instance found. Predicate is consistent. "` is followed by `"4ms.\n\n"`), so it adds nothing; the upstream VS Code client ignores it too.
- `bold` → a bold console content type of the plugin's own (the platform's system output type is grey in most themes); `Warning` → warning content type.
- `replaceLast` → the text is written prefixed with `\r`, relying on the console's carriage-return handling to overwrite the current line. The server sets it to replace the previous message, which for instances and counterexamples is the "Writing the XML file..." line without a trailing newline, so the overwrite is exact. When the previous message ends with a newline (for example "Solving..." before "No instance found"), the previous line stays, much as in the desktop application.
- `link` → `printHyperlink` with the message text as the link text, and a `HyperlinkInfo` that sends `OpenModel(link)` (instances open in the server's visualizer; cores make the server publish core diagnostics).
- `RunCompleted` → `notifyProcessTerminated(0)` and clears the active run.

`destroyProcess()` sends `StopExecution`. The server answers with `RunCompleted "Stopped"`, which ends the handler. If no answer arrives within a timeout, the handler terminates anyway. If the server is not running at launch, or dies during a run (reported through the descriptor's `LspServerListener.serverStopped`), the handler prints an "Alloy language server is unavailable" error and terminates. If Stop is pressed while the command list is still being fetched, `ExecuteAlloyCommand` is never sent.

### 10. Testing strategy
- Lexer: `LexerTestCase` with golden files covering every token class, plus a lexer-restart (incremental highlighting) check.
- Parser: `ParsingTestCase` with `testData/parser/*.als` + expected PSI dumps, including recovery cases.
- Editor features: `BasePlatformTestCase` for highlighting, brace matching, commenter, folding (`testFolding`), completion and run line markers / configuration producer.
- Settings: serialization round-trip and the mapping to `-Dalloy.ide.pref.*` arguments.
- Console rendering: unit tests feeding `showExecutionOutput` messages into `AlloyRunProcessHandler` with a test console.
- Shim: JUnit for preference isolation (seeded values visible, the real user store untouched) and `list-solvers` output.
- End-to-end server behaviour (diagnostics, navigation, run, visualizer): a manual checklist in `runIde`, because platform LSP tests need a live server process.

## Risks / Trade-offs

- [The `\r` overwrite in the console may not hold when the handler prints in several chunks] → Unit-test the rendering. If it proves unreliable, print only the final progress line and show the in-progress text in the run tab's status instead.
- [`alloy/showExecutionOutput` carries no run id, so late messages from a stopped run could reach the next run] → The plugin allows only one active run, and a run ends only on `RunCompleted` (or a stop timeout). Messages arriving with no active run are dropped.
- [The `ListAlloyCommands` round trip adds latency and relies on the reply notification arriving] → Use a short timeout with a clear error. The list is cheap because the server caches the parsed module.
- [The visualizer is a Swing window of another process: no IDE theming, and on macOS a separate Dock icon] → Accepted for this change (a native visualizer is out of scope). Set the app name so the window is identifiable.
- [The server and the desktop app share the analyzer temp directory (`alloy4tmp40-<user>`)] → Upstream already supports concurrent use, since names are randomized per run. Accepted.
- [Large plugin size (~tens of MB) due to the server closure] → Accepted. The jars are required at runtime.
- [Upstream grammar license obligations] → Keep the upstream license header in the ported `.flex`, and add attribution to the README.
- [A future platform release changing the LSP customization API] → The plugin pins `sinceBuild` to 2026.2. API usage stays in one provider/descriptor file.

## Migration Plan

No user data to migrate. Removing the `implementation(libs.alloy.lsp)` dependency is safe because no code uses it yet. Rollback means reverting the change. Settings live in a new `alloy.xml` and do not interact with anything else.
