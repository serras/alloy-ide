# Tasks

## 1. Spec baseline

- [x] 1.1 Sync the archived specs of `2026-10-19-add-alloy-intellij-plugin` into `openspec/specs/` (`alloy-editor`, `alloy-language-server`, `alloy-command-execution`, `alloy-analyzer-settings`), each with its `## Purpose`; verify `openspec list --specs` lists the four capabilities and `openspec validate inline-command-results --strict` no longer reports "Archive would refuse this delta"

## 2. Analyzer output interpretation

- [ ] 2.1 Add `AlloyRunLog` (design decision 2): log lines with `replaceLast` applied, warnings kept, bold flags, latest progress line, outcome (instance/counterexample/no instance/no counterexample/none), `unexpected` flag, time in ms, link (`XML: …`, or a bare core path normalized to `CORE: <path>`), and completion with its text; verify with `AlloyRunLogTest` over the three captured runs in `testData/lsp/showExecutionOutput.json` (instance, counterexample, stopped)
- [ ] 2.2 Add synthetic message sequences to `AlloyRunLogTest`, using the 6.2.0 server strings quoted in design.md: unsat with and without core minimization, "No unsat core is available", "contrary to expectation", warnings followed by "There were errors/warnings!" with no result (→ none); verify the summaries `No counterexample found · 3 ms`, `Instance found, contrary to expectation · 4 ms` and the `CORE: ` link in the tests

## 3. Result store and console-less execution

- [ ] 3.1 Add a settings generation counter to `AlloySettings` (not persisted) and increment it in `AlloySettingsApplier.settingsChanged(project, restart)`; verify in `AlloySettingsApplierTest` that the counter increases on a change and stays the same when nothing is applied
- [ ] 3.2 Add the `AlloyCommandResults` project service with immutable `CommandResult` (state, summary, details, link, document stamp, settings generation), `isOutdated(result, document)`, and a daemon restart of the affected file after each update; verify with `AlloyCommandResultsTest` that a result is current after recording, outdated after `insertString`, still outdated after undo, outdated after a generation bump, and current after it is recorded again
- [ ] 3.3 Replace `AlloyRunProcessHandler` with `AlloyCommandExecution` (design decision 3): feeds `AlloyRunLog`, writes `Running`/final results to the store, `fail(summary, details)`, `stop()` with the 5 s confirmation timeout, `warn` added to the details; adapt `AlloyRunService` (active execution, `executionOutput`, `serverStopped`, `dispose`) and `AlloyCommandLauncher` to it, mapping failures to the summaries in the design table; verify by porting `AlloyRunServiceTest` and `AlloyCommandLauncherTest` to assert stored results (busy refusal, server unavailable, model has errors, command not found, list timeout, stop confirmed and unconfirmed, server stopped, solver fallback warning in details)
- [ ] 3.4 Change `AlloySettingsApplier` to fail the active execution with summary `Interrupted` and `INTERRUPTED_MESSAGE`; verify `AlloySettingsApplierTest.testActiveRunIsInterruptedAndServerRestarted` against the stored result instead of a console sink
- [ ] 3.5 Build the tooltip HTML from a result (outdated notice for a file edit or a settings change, solver warning, escaped log lines with bold, failure details, capped at ~40 lines); verify with unit tests on the rendered string, including HTML escaping of `<`/`&` in log text

## 4. Launching from the gutter

- [ ] 4.1 Add `AlloyRunService.launch(file, ref, editor)`: busy check with an editor information hint, record `Running`, and a cancellable `Task.Backgroundable` named after the command that waits for the server (15 s), calls the launcher, updates `text2` from the log's progress, and calls `stop()` when cancelled; verify with tests using `FakeServerConnection` that a launch stores `Running` then the result, a second launch while busy leaves the store unchanged, and cancelling the indicator sends one stop
- [ ] 4.2 Replace the `ExecutorAction` actions in `AlloyRunLineMarkerContributor` with an `AlloyRunCommandAction` that calls `launch` for its command; verify in `AlloyRunMarkersAndProducerTest` (renamed `AlloyRunMarkersTest`) that markers still appear only on top-level `run`/`check` and that performing the marker action launches that command's ref
- [ ] 4.3 Delete `AlloyCommandConfigurationType`, `AlloyCommandRunConfiguration`, `AlloyCommandRunConfigurationProducer`, `AlloyCommandSettingsEditor`, `AlloyCommandRunState`, the console sink types and their tests (`AlloyCommandRunConfigurationTest`, `AlloyRunRenderingTest`, the producer tests), and remove `configurationType`/`runConfigurationProducer` from `plugin.xml`; verify `./gradlew check` passes and `grep -r "RunConfiguration\|ConsoleView" src/main` finds nothing in the `run` package

## 5. Inlay hints and clicks

- [ ] 5.1 Add `messages/AlloyBundle.properties` with the provider name and description, and register it as `<resource-bundle>`; verify the plugin loads in tests (`./gradlew test`) and `verifyPluginProjectConfiguration` passes
- [ ] 5.2 Implement `AlloyCommandResultHintsProvider` (design decision 5): one hint per top-level command with a stored result, at `EndOfLinePosition` of the command's first line, outdated → `TextWithoutBackground` plus ` (outdated)`, tooltip from 3.5, click action data only for results with a link or `Running`; register it with `codeInsight.declarativeInlayProvider` (language `Alloy`, enabled by default); verify with `AlloyCommandResultHintsProviderTest` using a recording `InlayTreeSink`: no hints without results, the right line for a multi-line command, only the executed command gets a hint, outdated styling after an edit, no action for `No counterexample found` without a core
- [ ] 5.3 Implement `AlloyCommandResultClickHandler` and register it with `codeInsight.inlayActionHandler`: running → stop the active execution; link → `OpenModel(link)` through the server connection; no server → editor error hint; verify with tests that an instance click opens `XML: …`, a core click opens `CORE: …`, a running click sends one stop, and an outdated instance still opens

## 6. Documentation and integration

- [ ] 6.1 Update the README (feature "Running commands", replace "Manual checklist: command execution" with inline-result checks: hint states, tooltip renders as HTML with line breaks, dimmed and "(outdated)" after an edit, click opens the visualizer, click shows the core with core minimization on, click on running stops, status-bar progress and cancel, busy hint, server down, no tool window opens, hints can be turned off in *Settings | Editor | Inlay Hints*; update the settings checklist items that mention the Run console), the CHANGELOG (Changed: inline results; Removed: Alloy command run configurations; Fixed: core link) and the `plugin.xml` description; verify the README and CHANGELOG have no remaining mention of the Run console or the *Alloy command* configuration (`grep -n "Run console\|Alloy command" README.md CHANGELOG.md src/main/resources/META-INF/plugin.xml`)
- [ ] 6.2 Run the full build and verification; verify `./gradlew check buildPlugin verifyPlugin` succeeds with no new Plugin Verifier problems (in particular no experimental/internal API usage from the declarative inlay API)

## Workflow follow-up

- Run the README manual checklist for inline command results in `runIde`, especially the tooltip rendering (design risk 2).
- Archive the change once the main specs from task 1.1 are in place.
