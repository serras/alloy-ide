# Tasks

## 1. Build setup

- [x] 1.1 Apply the `org.jetbrains.intellij.platform.grammarkit` subplugin in `build.gradle.kts`, with `generateLexer`/`generateParser` tasks writing to `build/generated/` and that directory registered as a Kotlin/Java source root; verify `./gradlew generateLexer generateParser` runs (with placeholder grammar files) and `compileKotlin` depends on them
- [x] 1.2 Create the `server-shim` Java subproject (included in `settings.gradle.kts`, Java toolchain compatible with the IDE's JBR, `compileOnly`/`implementation` on `libs.alloy.lsp`); verify `./gradlew :server-shim:jar` produces a jar
- [x] 1.3 Replace `implementation(libs.alloy.lsp)` with an `alloyServer` configuration (resolving `libs.alloy.lsp` + `:server-shim`) and copy it into `lib/server/` in `prepareSandbox`; verify after `./gradlew buildPlugin` that the zip contains `lib/server/org.alloytools.alloy.lsp-6.2.0.jar`, `org.eclipse.lsp4j-0.4.1.jar` and the shim jar, and that no Alloy/lsp4j-0.4.1 jar sits directly in `lib/`
- [x] 1.4 Set `sinceBuild` to 2026.2 in the plugin configuration and keep `<depends>com.intellij.modules.lsp</depends>`; verify `./gradlew verifyPluginProjectConfiguration` passes

## 2. Language, file type and lexer

- [x] 2.1 Register the Alloy `Language`, `.als` `LanguageFileType` and file icon in `plugin.xml`; verify with a `BasePlatformTestCase` that a `model.als` file has the Alloy file type and a `.md` file does not
- [x] 2.2 Port upstream `Alloy.lex` (tag `v6.2.0`, `org.alloytools.alloy.core/parser/`) to `Alloy.flex` for IntelliJ's `FlexLexer`, keeping its license header, covering keywords, temporal operators, prime, identifiers, numbers, strings, `//`/`--`/`/* */`/`/** */` comments and a `BAD_CHARACTER` fallback; verify with `LexerTestCase` golden tests for every token class, including invalid characters and unterminated comments/strings
- [x] 2.3 Implement the syntax highlighter and `ColorSettingsPage` with the categories listed in the spec and a demo snippet; verify with a test that each category's attribute key is reachable from the demo text and manually in `runIde` that changing "Temporal operator" recolors an open file
- [x] 2.4 Add README attribution for the ported grammar, plus a "Features" section listing file type and highlighting; verify the README renders with the new sections

## 3. Shallow PSI and native editor features

- [ ] 3.1 Write `Alloy.bnf` with paragraph rules (`module`, `open`, `sig`/`enum`, `fact`, `pred`, `fun`, `assert`, `let`, `run`/`check`) and balanced-block bodies, using `pin`/`recoverWhile` at paragraph keywords; add the `ParserDefinition`; verify with `ParsingTestCase` PSI-dump tests for each paragraph kind, a body with a type error (no PSI error), and a broken `sig {` followed by valid paragraphs
- [ ] 3.2 Implement the brace matcher (`{}`, `()`, `[]`) and closing-brace auto-insertion; verify with a `BasePlatformTestCase` typing test
- [ ] 3.3 Implement the commenter (`//` line, `/* */` block); verify with tests toggling both comment kinds
- [ ] 3.4 Implement the folding builder for multi-line bodies, multi-line block comments and consecutive `open` lines; verify with `testFolding` test data
- [ ] 3.5 Implement keyword completion that is suppressed inside comments and strings; verify with completion tests (`ab` → `abstract`; nothing inside a `//` comment)

## 4. Server shim

- [ ] 4.1 Implement the in-memory `PreferencesFactory` seeded from `alloy.ide.pref.*` system properties; verify with JUnit that a seeded value is returned by `A4Preferences.SkolemDepth.get()`, that `set()` calls do not touch the real user preference store, and that unseeded keys return upstream defaults
- [ ] 4.2 Implement the `serve` entry point (install prefs, then `AlloyLanguageServer.main` over stdio) with nothing written to stdout before the LSP stream; verify with a JUnit test that starts it as a subprocess, sends `initialize`, and receives capabilities
- [ ] 4.3 Implement `list-solvers` printing a JSON array of present solvers; verify with JUnit that the output parses and contains the SAT4J id

## 5. Language server integration

- [ ] 5.1 Implement the `LspServerSupportProvider` and `ProjectWideLspServerDescriptor`: command line with the running IDE's `java.home/bin/java`, `-cp lib/server/*`, `-Djava.util.prefs.PreferencesFactory=…`, the `alloy.ide.pref.*` properties and the shim main class; verify with a unit test of the generated command line, and manually that opening an `.als` file in `runIde` with no system Java starts exactly one server per project
- [ ] 5.2 Configure `lspCustomization` to disable code lens, document links, completion and formatting while keeping diagnostics, hover, definition, references, rename, document and workspace symbols; verify manually in `runIde` that no "Execute" lens or link appears on commands
- [ ] 5.3 Define the custom lsp4j server interface (`ListAlloyCommands`, `ExecuteAlloyCommand`, `StopExecution`, `OpenModel`) and a custom client handling `alloy/commandsListResult` and `alloy/showExecutionOutput`; verify with a unit test that deserializes sample notification JSON captured from the 6.2.0 server into the client's data classes
- [ ] 5.4 Verify the semantic features end to end with a manual `runIde` checklist added to the README "Development" section: an undefined-signature diagnostic appears and clears; hover; Cmd/Ctrl-click to a declaration in another file; Find Usages; rename across files in one undo; Structure view and breadcrumbs; Go to Symbol; killing the server process shows the failure, stderr and a restart action; closing the project terminates the process

## 6. Command execution

- [ ] 6.1 Implement the "Alloy command" run configuration type, its settings editor (file + command) and persistence; verify with a test that a configuration survives `writeExternal`/`readExternal` and that a missing file fails `checkConfiguration`
- [ ] 6.2 Implement the `RunLineMarkerContributor` on `run`/`check` keyword leaves and a `LazyRunConfigurationProducer`; verify with tests that markers appear only on command lines and that the producer creates and recognizes a configuration for a given command
- [ ] 6.3 Implement the project-level active-run service and `AlloyRunProcessHandler` (busy rejection, routing of output to the active run, dropping messages when idle); verify with unit tests that a second launch is refused with "An Alloy analysis is already running" and that messages with no active run are ignored
- [ ] 6.4 Implement console rendering of `showExecutionOutput` (bold, warning style, `lineBreak`, `replaceLast` via `\r`, hyperlinks sending `OpenModel`, termination on `RunCompleted`); verify with unit tests feeding recorded message sequences and checking console text, content types and termination
- [ ] 6.5 Implement launch: `ListAlloyCommands` round trip with a timeout, selection by label then ordinal, `ExecuteAlloyCommand` with the server's arguments, "command not found" and "language server unavailable" failures; verify with tests against a fake custom server proxy for found/not-found/timeout/server-down cases
- [ ] 6.6 Implement Stop (`StopExecution` + terminate on "Stopped" or after a timeout); verify with a unit test using the fake server proxy
- [ ] 6.7 Verify execution end to end in `runIde` and add it to the README checklist: gutter run of `run`/`check`, unsaved scope change used, progress updates in place, instance and counterexample links open the visualizer, the core link highlights core locations, Stop works during a long analysis, a model with errors reports them; document the run feature in the README "Features" section

## 7. Analyzer settings

- [ ] 7.1 Implement the application-level `AlloySettings` state (eight options, upstream defaults, `alloy.xml`) and its mapping to `alloy.ide.pref.*` values; verify with tests for defaults, a serialization round trip, and the generated property map (including the SAT4J fallback for an unavailable solver)
- [ ] 7.2 Implement solver discovery (background `list-solvers` call, session cache, loading state); verify with a unit test that parses shim output and falls back to SAT4J-only on failure
- [ ] 7.3 Implement the `Configurable` under Languages & Frameworks with restricted choices and an "(unavailable)" marker for a missing saved solver; verify with a test that `isModified`/`apply`/`reset` round-trip every option
- [ ] 7.4 On apply with changes, terminate the active run with an "interrupted by a settings change" message and restart each project's server; on apply without changes do nothing; print the solver-fallback warning at the next run start; verify with tests using the active-run service, and manually in `runIde` that the server restarts and diagnostics reappear
- [ ] 7.5 Verify isolation manually and record it in the README checklist: with skolem depth 3 set in the Alloy desktop app, the IDE runs with depth 1; after changing IDE settings and running, the desktop app's preferences are unchanged; document the settings page in the README "Features" section

## 8. Integration and release

- [ ] 8.1 Run `./gradlew check verifyPlugin buildPlugin` and verify it passes with no Plugin Verifier compatibility problems against 2026.2
- [ ] 8.2 Install the built zip into a clean IntelliJ IDEA 2026.2 and run the full README manual checklist once on macOS and once on Linux or Windows; verify every item passes
- [ ] 8.3 Update `CHANGELOG.md` "Unreleased" and the `plugin.xml` description with the delivered features and known limitations (pure-Java/PATH solvers only, external visualizer window); verify `./gradlew patchChangelog --dry-run` (or `buildPlugin`) picks up the notes
