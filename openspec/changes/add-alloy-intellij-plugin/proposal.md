# Proposal

## Why

Alloy 6 users have no first-class support in IntelliJ-based IDEs: the plugin skeleton in this repository declares the LSP module dependency but provides no language support at all. The Alloy project already ships a language server (`org.alloytools.alloy.lsp`) with semantic features and command execution, and the IntelliJ Platform (2026.2) now supports every LSP feature that server offers. Together with a native lexer and a shallow parser derived from Alloy's own grammar, this lets us deliver a full editing and analysis experience without reimplementing Alloy's type checker.

## What Changes

- Register an Alloy 6 language and `.als` file type, with a native JFlex lexer ported from the upstream `Alloy.lex` (keywords, temporal operators, primes, numbers, strings, comments) and a configurable syntax highlighter.
- Add a shallow PSI parser derived from the top-level productions of the upstream `Alloy.cup` (`module`, `open`, `sig`/`enum`, `fact`, `pred`, `fun`, `assert`, macros, `run`/`check` commands); bodies are kept as balanced blocks rather than full expression trees.
- Provide native editor conveniences built on the lexer and PSI: brace matching, line/block commenting, code folding, and keyword completion.
- Integrate the Alloy language server through the IntelliJ LSP API: diagnostics, hover, go to definition, find usages, rename, structure view / breadcrumbs, and Go to Symbol.
- Run the language server out-of-process on the IDE's bundled runtime, with its dependency closure (including the legacy lsp4j 0.4.1) shipped separately from, and never loaded into, the plugin classloader.
- Execute Alloy `run`/`check` commands natively: gutter run markers and an "Alloy command" run configuration that drive the server's custom execution protocol, stream results into the Run console, support stopping, and open instances/cores via clickable links. The server's own code lenses and command links are disabled so there is a single run UI.
- Add an IDE-level settings page for the analyzer options (solver, skolem depth, overflow prevention, core minimization and granularity, non-fatal warnings, record Kodkod, infer partial instance). Settings are isolated from the Alloy desktop application's preferences, the solver list reflects solvers actually available to the server, and changing settings restarts the server.
- Remove the direct `implementation` dependency on `org.alloytools.alloy.lsp` from the plugin's compile/runtime classpath in favor of a dedicated server distribution configuration.

## Capabilities

### New Capabilities
- `alloy-editor`: Alloy language and file type registration, lexing and syntax highlighting (with color settings), shallow PSI structure, brace matching, commenting, folding, and keyword completion.
- `alloy-language-server`: Lifecycle and packaging of the out-of-process Alloy language server and the semantic features delegated to it (diagnostics, hover, navigation, references, rename, document and workspace symbols).
- `alloy-command-execution`: Running and stopping Alloy `run`/`check` commands from the editor and run configurations, presenting analyzer output, and opening resulting instances and unsat cores.
- `alloy-analyzer-settings`: IDE-level analyzer options, their isolation from the Alloy desktop application's preferences, discovery of available solvers, and applying changes to the running server.

### Modified Capabilities

None (no existing specs).

## Impact

- **Build**: `build.gradle.kts` and `gradle/libs.versions.toml` gain the Grammar-Kit/JFlex generation setup, a separate configuration for the server's runtime classpath bundled into the plugin distribution (e.g. `lib/server/`), and a small server-side shim jar; the current `implementation(libs.alloy.lsp)` line is removed.
- **Plugin descriptor**: `plugin.xml` registers the file type, language, parser definition, highlighter, color settings page, brace matcher, commenter, folding builder, completion contributor, LSP integration provider, run line marker contributor, run configuration type, and settings configurable.
- **Platform**: Targets IntelliJ IDEA 2026.2 and depends on `com.intellij.modules.lsp`, so the plugin is limited to IDEs that provide LSP support.
- **Runtime**: Spawns one Java subprocess per project for the language server. The Alloy visualizer opens as a Swing window owned by that subprocess. Only pure-Java solvers (SAT4J) and solvers found on `PATH` are available, because the Maven artifacts ship no native solver libraries.
- **Out of scope**: Alloy Markdown (`.md`) models, a native instance visualizer, full expression-level PSI, formatting, and per-project settings.
