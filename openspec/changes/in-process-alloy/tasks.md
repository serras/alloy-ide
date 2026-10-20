# Tasks

## 1. Dependencies

- [ ] 1.1 Add `alloy-core` and `alloy-application` (6.2.0) to `gradle/libs.versions.toml` and as `implementation` dependencies in `build.gradle.kts`, next to the existing server setup for now. Verify with `./gradlew buildPlugin` that the plugin's `lib/` contains the core, application, pardinus, api and libg JARs, and that `./gradlew test` still passes.
- [ ] 1.2 Add a smoke test that parses a small model with `CompUtil.parseEverything_fromString` and runs a command with `TranslateAlloyToKodkod.execute_command` on SAT4J inside the platform test. Verify that it passes, which proves the JARs load on the plugin classloader.

## 2. Model service and diagnostics

- [ ] 2.1 Implement `AlloyPositions` (`Pos` ↔ `TextRange`/`VirtualFile`, including `jar://…!/models/util/*.als` through `JarFileSystem`). Verify with unit tests for multi-line spans, tabs, project files and library files.
- [ ] 2.2 Implement `AlloyModelService`. It holds a `CachedValue` of `AlloyModelResult` (a `CompModule` with warnings, or errors) per file. The value is keyed on the PSI modification count of the file and of the project modules it opens, built from the current document texts, catches every analyzer `Throwable` except PCE, and runs off the EDT. Verify with tests for: a fresh model, a model with an error, an unsaved edit, an edited opened module invalidating its dependents, and an analyzer exception producing an "unavailable" result.
- [ ] 2.3 Implement the Alloy `ExternalAnnotator`:
  - errors and warnings at the analyzer's ranges;
  - an "opened module has errors" error at the `open` line.

  Register it in `plugin.xml`. Verify with `CodeInsightTestFixture` highlighting tests for an undefined signature, a fixed error, a warning, an error in an opened module, and the Problems view entry. Add the "diagnostics" items to the README manual checklist.

## 3. Analysis engine

- [ ] 3.1 Spike stopping. On a slow model (e.g. `run big { #Node = 30 } for 30 but 7 Int`), measure the stop latency of each mechanism:
  - reporter-callback flags;
  - thread interrupt;
  - a SAT4J handle-keeping `SATFactory` with `expireTimeout()`;
  - destroying an external solver process.

  Also check that the `CompUtil` and translator statics tolerate a concurrent semantic parse. Record the chosen mechanism and the measured gaps in `design.md` (Decisions 8 and Open Questions).
- [ ] 3.2 Implement `AlloyAnalysisEngine`/`AlloyAnalysis`:
  - a dedicated large-stack thread per analysis;
  - typed events (`Step`, `Warning`, `Sat`, `Unsat`, `Failed`, `Stopped`);
  - `stop()` with a Stopping state;
  - `OutOfMemoryError`/`StackOverflowError` turned into failures;
  - termination of any running analysis on project close.

  Verify with tests for: completion, stopping during solving, stopping during translation (Stopping, then Stopped), a forced `StackOverflowError` reported as stack exhausted, an exception reported with its message, and project disposal stopping the analysis.
- [ ] 3.3 Replace the `AlloyRunLog` text parsing with an event-to-tooltip renderer that keeps the current tooltip wording: command, solver/bitwidth line, variable and clause counts, bold result with time, warnings, core size, and no repeated progress lines. Verify by porting `AlloyRunLogTest` to event-based inputs, with the same expected tooltip text.

## 4. Command execution on the engine

- [ ] 4.1 List commands from `CompModule.getAllCommands()` and map them to PSI commands by position. Keep the "Command not found" and "Model has errors" failures. Verify by updating `AlloyCommandLauncherTest` to the in-process path and checking that the model-with-errors and unknown-command scenarios still pass.
- [ ] 4.2 Rework `AlloyCommandExecution`, `AlloyCommandTask` and `AlloyRunService` to run on `AlloyAnalysisEngine` with the analyzer options from `AlloySettings`, and remove `AlloyServerConnection`/`LspAlloyServerConnection` and the server-unavailable states. Verify that `AlloyRunServiceTest`, `AlloyRunLaunchTest` and `AlloyCommandResultsTest` pass on the in-process path, including unsaved-edit scope, busy refusal, and edits during a run outdating the result.
- [ ] 4.3 Implement stopping with the outcome text "Stopped before completion" (and "Stopping…" while pending), from the running hint and from the progress indicator. Verify with `AlloyCommandResultHintsProviderTest`/`AlloyCommandResultClickHandlerTest` cases for each entry point, and that the tooltip keeps the output received before the stop.
- [ ] 4.4 Make the spinner gutter icon open a popup with a Stop action. Verify with an `AlloyRunMarkersTest` case that the running marker's action group contains Stop and that invoking it stops the execution. Add the gutter Stop menu to the README manual checklist.
- [ ] 4.5 Show unsat cores from `A4Solution.highLevelCore()` as editor warnings, keeping "no core available" as not clickable. Verify with tests for a valid check with core minimization and for a result without a core.
- [ ] 4.6 Settings:
  - build `A4Options` per run;
  - list solvers in-process with `SATFactory` (session-cached);
  - make `AlloySettingsApplier` only bump the settings generation, with no restart, so a running analysis continues and is outdated when its result arrives.

  Verify by updating `AlloySolversTest`, `AlloySettingsApplierTest` and `AlloySettingsTest`, and by adding a test that the desktop `java.util.prefs` node is unchanged after a run.

## 5. Deeper PSI

- [ ] 5.1 Extend `Alloy.bnf` with `field_decl`, `param_decl`, quantifier/`let`/comprehension binders, `enum_member` and `ref`, using pins and `recoverWhile` so that broken binders fail locally. Verify that every existing `AlloyParserTest`, folding and brace test passes unchanged, and that new parser tests cover the declaration and reference scenarios, including `all x: | some x`.
- [ ] 5.2 Make declarations `PsiNameIdentifierOwner` and add `AlloyNameIndex` stubs for top-level declarations and fields. Verify with tests that the stub index finds declarations across two project files without parsing them.

## 6. References, usages and rename

- [ ] 6.1 Implement `AlloyReference` (`PsiPolyVariantReference`), using exact resolution through `find`/`referenced` when the model is fresh and the name-based scope chain otherwise (binders, then sig fields, then top-level, then opened modules with an alias, then built-ins). Verify with resolve tests for each scenario of "Go to declaration", "Exact resolution for a valid model" and "Name-based resolution for a model with errors": a signature, across files, into `util/ordering`, a local binder, an overloaded field, shadowing, an ambiguous name, and `ord/first`.
- [ ] 6.2 Implement Find Usages (`FindUsagesProvider`, word scanner, `UsageTypeProvider` separating unconfirmed usages) and highlight usages in the file. Verify with `myFixture.findUsages`/`testHighlightUsages` tests for a predicate used in two files, and for a use in a file with errors classified as unconfirmed.
- [ ] 6.3 Implement rename:
  - a processor that refuses when an affected file lacks a fresh model;
  - refusal for library declarations;
  - in-place rename for local binders.

  Verify with `myFixture.renameElementAtCaret` tests for renaming a signature across files (with a single undo), a local binder, refusal on a model with errors, and refusal on a library declaration.

## 7. Documentation, structure and symbols

- [ ] 7.1 Implement quick documentation: `Clause.explain()` plus the doc comment for a fresh model, and the declaration source plus the doc comment otherwise. Verify with documentation tests for a field, a documented predicate, and a model with errors.
- [ ] 7.2 Implement the structure view (fields nested under sigs) and breadcrumbs. Verify with `myFixture.testStructureView` and breadcrumbs tests, including a file with a type error.
- [ ] 7.3 Implement the Go to Symbol contributor over `AlloyNameIndex`. Verify with a test that searching a predicate name finds it in another project file and navigates there. Add the semantic-features items (hover, navigation into `util/*`, find usages, rename, structure, Go to Symbol) to the README manual checklist.

## 8. Instance viewer

- [ ] 8.1 Spike `VizGraphPanel` inside a tool window with the IDE frame as its parent. Check rendering, scrolling, zoom and the projection atom combos, and add the `edu.mit.csail.sdg.alloy4viz.AlloyIdeMagic` bridge for `MagicLayout.magic`. Verify that a test can construct a `VizState` from an `A4Solution`'s XML and apply the magic layout and colors without exceptions.
- [ ] 8.2 Implement `AlloyInstanceViewer` (a project service with one `ViewerSession` that replaces the previous one) and the "Alloy Instance" tool window. It has the empty state, and a header (file, command text, line, instance/counterexample kind, outdated mark) that navigates to the command. Wire the result hint's click to it in place of `OpenModel`. Verify with tests that opening two results leaves only the second session, that the header text matches, that navigation moves the caret to the command, and that an edit marks the session outdated.
- [ ] 8.3 Implement the Visualization, Text (`toString(state)`) and Table (`format(state)`) tabs, with the last selected tab remembered and Visualization as the default. Verify with tests on the panel model that the text and table contents match the analyzer's output for the current state and that the tab choice persists across sessions. Verify by a test that `java.util.prefs` is untouched.
- [ ] 8.4 Implement the state stepper ("State i of n", loop marker, previous/next following the loop, hidden for single-state results). Verify with tests on a 3-state trace that loops back to state 1, and on a static result.
- [ ] 8.5 Implement New config/trace/init/fork:
  - the actions call `fork(-3|-1|-2|0|state+1)` through `AlloyAnalysisEngine`;
  - they have a progress indicator and can be stopped;
  - they respect the busy rule;
  - they are enabled per the static or temporal model;
  - they are disabled when the result is outdated;
  - the viewer reports "No more solutions" when nothing new is found.

  Verify with tests on a static model and a temporal model for enablement, a successful New config, New fork agreeing up to the current state, the no-more-solutions message, the busy refusal and stopping.
- [ ] 8.6 Implement the projection dropdown (`canProject` types, none option, magic layout's default reflected). Verify with tests that projecting `State` changes the `VizState`'s projected types and that removing it clears them. Add the instance viewer items (graph rendering, magic layout, tabs, stepper, new-solution buttons, projection, header navigation) to the README manual checklist.

## 9. Remove the language server

- [ ] 9.1 Delete `com.serranofp.alloy.lsp`, its tests, `AlloyRunLog`'s message-type remnants and the LSP extension. Replace `<depends>com.intellij.modules.lsp</depends>` with `com.intellij.modules.platform`. Verify that `./gradlew check` passes and that a search for `lsp4j`, `platform.lsp` and `AlloyServerConnection` in `src/` finds nothing.
- [ ] 9.2 Remove `server-shim/`, `include("server-shim")`, the `alloyServer` configuration, the `PrepareSandboxTask` copy and the `alloy-lsp` catalog entry. Verify that `./gradlew buildPlugin` produces a distribution without `lib/server/`, and that `./gradlew verifyPlugin` reports no problems for 2026.2.3.
- [ ] 9.3 Update the `plugin.xml` description and known limitations, and update the README (features; replace the language-server checklist with semantics, execution and viewer checklists; settings no longer restart anything). Add a CHANGELOG entry covering the in-process analyzer, native semantics, the tool window and the removed LSP requirement. Verify by reviewing that no document mentions a language server or the separate visualizer window.

## 10. Integration

- [ ] 10.1 Run `./gradlew check` and `./gradlew verifyPlugin` with everything in place. Verify that both pass with no LSP classes on the classpath. Then go through the README manual checklists in `runIde` (left to the user, as recorded in the checklist).
