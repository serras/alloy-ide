# Proposal

## Why

The plugin depends on the IntelliJ Platform LSP module and on an out-of-process Alloy language server, started through the `server-shim` submodule. The plugin's custom requests to it go through brittle text messages from an old lsp4j. The cost is a second JVM, an LSP-only IDE requirement, semantic features that disappear whenever the model has an error, and instances that open in a separately managed Alloy window. Running Alloy directly from its JARs inside the IDE removes that layer. It also lets every feature use IntelliJ's own UI.

## What Changes

- **BREAKING** Remove the dependency on `com.intellij.modules.lsp`, the `server-shim` submodule, the `lib/server/` sandbox layout and the `org.alloytools.alloy.lsp` dependency. Depend on `org.alloytools.alloy.core` and `org.alloytools.alloy.application` (6.2.0) on the plugin classpath instead.
- Reimplement every semantic feature that the language server provided as native IntelliJ features backed by the in-process Alloy parser and type checker:
  - diagnostics
  - hover / quick documentation
  - go to declaration, including into the bundled `util/*` library modules
  - highlight usages and find usages
  - rename
  - structure view and breadcrumbs
  - Go to Symbol

  When the model does not type-check, resolution falls back to the best name-based scoping the plugin can do (local binders, sig fields, top-level paragraphs, opened modules), so navigation keeps working while the user is editing. Rename refuses to run in that state.
- Deepen the native parser so that field declarations, parameters, quantifier/`let`/comprehension binders and identifier references inside bodies become PSI elements.
- Execute commands in-process on a managed, stoppable analysis thread. Command listing, progress, warnings, unsat cores and results come directly from the analyzer instead of from parsed server messages.
- Clicking the spinner gutter icon of a running command opens a menu with **Stop**. A stopped command's hint says "Stopped before completion".
- **BREAKING** Show instances and counterexamples in a new **Alloy Instance** tool window instead of the Alloy visualizer window:
  - Visualization, Text and Table tabs, with the visualization using the magic layout by default.
  - A toolbar with New config, New trace, New init and New fork, a state stepper for Alloy 6 traces, and projection selection.
  - A header naming the file and command the result came from.

  Each new result replaces the window's content.
- Applying changed analyzer settings no longer restarts anything. The next analysis just uses the new values.

## Capabilities

### New Capabilities
- `alloy-semantics`: Native semantic features for Alloy models: diagnostics, quick documentation, reference resolution (exact with a fresh model, name-based otherwise), find usages, rename, structure view, breadcrumbs and Go to Symbol.
- `alloy-instance-viewer`: The tool window that shows an analysis result as a graph, text or table. It supports solution enumeration, trace state stepping and projection, and shows where the result came from.

### Modified Capabilities
- `alloy-command-execution`: The following requirements change:
  - Stop uses a gutter menu, and the hint says "Stopped before completion".
  - Server-unavailable failures go away.
  - Instances open in the tool window instead of the visualizer window.
  - Failure causes are reported by the in-process analyzer.
- `alloy-analyzer-settings`: Applying settings no longer restarts a server, and a running analysis is not interrupted. Isolation from desktop preferences must now hold inside the IDE process.
- `alloy-editor`: Parsing goes deeper than top-level paragraphs, covering fields, parameters, binders and references. Native features no longer depend on a server. The plugin now installs in any IntelliJ-based IDE from 2026.2, with or without the LSP module.
- `alloy-language-server`: All requirements are removed. The capability is replaced by `alloy-semantics` and the in-process runtime.

## Impact

- **Build:**
  - `build.gradle.kts`: the `alloyServer` configuration and sandbox copy are removed, and the Alloy JARs become `implementation` dependencies.
  - `settings.gradle.kts`: `include("server-shim")` is removed.
  - `gradle/libs.versions.toml`: the `alloy-lsp` entry is replaced by `alloy-core` and `alloy-application`.
  - `server-shim/` is deleted.
- **Code:**
  - `com.serranofp.alloy.lsp` is deleted.
  - `com.serranofp.alloy.run` is reworked: the `AlloyServerConnection`, `AlloyRunLog` and message-parsing paths are removed.
  - `com.serranofp.alloy.settings` changes: solvers are listed in-process, and the applier no longer restarts a server.
  - New packages are added for semantics, the analysis engine and the instance viewer.
  - The grammar (`Alloy.bnf`) and PSI are extended.
- **plugin.xml:** the `com.intellij.modules.lsp` dependency is replaced by `com.intellij.modules.platform`. LSP extensions are replaced by annotator, reference, find-usages, rename, structure-view, symbol-contributor, documentation and tool-window extensions. The description and known limitations are updated.
- **Tests:** LSP and shim tests are removed. New platform tests cover semantics, the engine and the viewer model.
- **Docs:** the README features and manual checklists are rewritten for the new behavior. The CHANGELOG gets a new entry.
- **Runtime:** analyses now use the IDE's heap and threads. See the design for memory, stack and cancellation trade-offs.
