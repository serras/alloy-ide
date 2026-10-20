# Design

## Context

See proposal.md (Why). The current run pipeline in `src/main/kotlin/com/serranofp/alloy/run/`:

- `AlloyRunLineMarkerContributor` puts `ExecutorAction` actions on the `run`/`check` keyword. They go through `AlloyCommandRunConfigurationProducer` → `AlloyCommandRunConfiguration` → `AlloyCommandRunState`, which opens the file if needed, waits up to 15 s for the server, and calls `AlloyCommandLauncher`.
- `AlloyCommandLauncher` lists the server's commands, picks the one matching the `AlloyCommandRef` (label, or index), and calls `ExecuteAlloyCommand` with the server's own arguments. Every failure goes through `AlloyRunProcessHandler.fail(message)`.
- `AlloyRunService` (project service) holds the single active run, matches `alloy/commandsListResult` to the pending request, and routes every `alloy/showExecutionOutput` to the active run. `AlloyRunProcessHandler` renders the messages into a `ConsoleView` and ends on `RunCompleted`, or after a 5 s timeout following Stop.
- `AlloySettingsApplier` fails the active run with `INTERRUPTED_MESSAGE` and restarts the servers.

The server protocol does not change (see the archived design). Facts from the 6.2.0 server sources (`AlloyTextDocumentService.solverCallbackMsgToAlloyMsg`) that drive the result parsing:

- A satisfiable result is one `RunResult` message with `link = "XML: <file>"`, `replaceLast`, `bold` and text `"   Instance found. Predicate is consistent[, contrary to expectation | , as expected]. "` (for `check`: `Counterexample found. Assertion is invalid…`), followed by a `"<n>ms.\n\n"` message.
- An unsatisfiable result is a `RunResult` with `replaceLast`, `bold` and text `"   No instance found. Predicate may be inconsistent[, contrary to expectation | , as expected]. "` (or `No counterexample found. Assertion may be valid…`), followed by a `"<n>ms.\n\n"` message. With core minimization the server first sends a `RunInProgress` "minimizing" line (`"   No counterexample found.  Assertion may be valid. <n>ms.\n"`), and the result that replaces it already carries the solving time: `"… may be valid. <n>ms.\n   "`. It continues with either `"   No unsat core is available in this case. "` plus `"<m>ms.\n\n"`, or a `"core"` message whose `link` is the **bare core file path**, followed by `" contains N top-level formulas. <m>ms.\n\n"` or `" reduced from M to N top-level formulas. <m>ms.\n\n"`.
- The server's `OpenModel` → `doVisualize` handles only `XML: `, `CORE: `, `POS: ` and `CNF: ` prefixes, so the bare core path it sends is ignored. The current console core link therefore does nothing. The VS Code client has the same issue.
- Warnings arrive as `Warning` messages. After warnings the server sends `RunResult "There were errors/warnings!"`. `RunCompleted ""` always ends a run, and `RunCompleted "Stopped"` ends a stopped one.

The platform (2026.2.3, checked in `intellij.platform.lang.jar`) provides declarative inlay hints: `InlayHintsProvider`, `InlayTreeSink.addPresentation(position, payloads, tooltip: String?, hintFormat, builder)`, `EndOfLinePosition(line)`, `HintFormat`/`HintColorKind` (`Default`, `Parameter`, `TextWithoutBackground`), `PresentationTreeBuilder.text(text, InlayActionData)`, and `InlayActionHandler.handleClick(editor | EditorMouseEvent, payload)`.

## Goals / Non-Goals

**Goals:**
- Keep the server protocol, command identity (`AlloyCommandRef`) and launch logic (`AlloyCommandLauncher.select`) as they are. Only the presentation layer changes.
- Make the interpretation of analyzer output a pure function that can be unit-tested against the captured `showExecutionOutput.json`.
- The inlay provider only reads state. It never talks to the server.

**Non-Goals:**
- Dependency-aware staleness (edits in a module opened with `open`).
- Persisting results across IDE restarts or sharing them between projects.
- Running several analyses at once (the server still allows only one).

## Decisions

### 1. Result store: `AlloyCommandResults` project service
A project service holds `Map<VirtualFile, Map<AlloyCommandRef, CommandResult>>`, guarded by a lock. `CommandResult` is immutable and holds:
- `state`: `Running`, `InstanceFound`, `CounterexampleFound`, `NoInstance`, `NoCounterexample`, `Stopped`, `Failed`;
- `summary` (hint text);
- `details`: a `CommandDetails` with the solver fallback `warnings`, the `log` (`AlloyRunLog.lines`) and the `failure` text, kept apart so the tooltip can order and cap them (see decision 4);
- `link`: the `XML: …` or `CORE: …` to open, or null;
- `documentStamp`: the store's edit count of the document (`AlloyCommandResults.stamp(document)`) when the command was launched;
- `settingsGeneration`: value of a counter in `AlloySettings` when the command was launched. The counter is not persisted, and it is incremented by `AlloySettingsApplier.settingsChanged`.

A result is **outdated** when the current edit count or settings generation differs from the recorded one. `outdated(result, document)` says why (`FileChanged` or `SettingsChanged`), and `isOutdated` wraps it. This is computed on read: the daemon re-runs inlay providers after every edit anyway. An edit during a run makes the result outdated when it arrives (spec: "Edit during a run").

The edit count is not `Document.modificationStamp`: undo restores the previous stamp, so an undone edit would make a result current again, contrary to the spec's "Undoing the edit" scenario. Instead, the service registers a `DocumentListener` on the editor event multicaster (disposed with the service), which increments an `AtomicLong` kept in the document's user data on every change, undo included. The count only grows.

Every mutation calls `DaemonCodeAnalyzer.restart(psiFile, reason)` for that file on the EDT, so the provider runs again. A settings change restarts the daemon for the whole project.
- *Alternative:* `Document.modificationStamp`. Rejected: undo restores it (see above).
- *Alternative:* explicit "dirty" flags per result, set by a `DocumentListener`. Rejected: the listener is needed anyway, and a monotonic count compared on read needs no per-result bookkeeping.
- *Alternative:* key by command PSI pointer (`SmartPsiElementPointer`). Rejected: pointers survive edits but not every reparse of the shallow PSI, and the existing `AlloyCommandRef` already identifies the command to run. If unlabelled commands are reordered, a result may move to another command, but it is outdated at that point anyway.

### 2. Interpreting analyzer output: `AlloyRunLog`
A pure class that `accept(message)`s `AlloyExecutionMessage`s and exposes:
- `lines`: the log as `Line`s of `Span`s (one span per message part, with `bold` and `warning` flags), without trailing blank lines, and with warnings kept. `replaceLast` removes the **last line** of the log, the progress line being replaced: the unfinished line ("Writing the XML file..."), or else the last complete line ("Solving...", the "minimizing" line). This is what the desktop reporter does (`setLength(len3)`). Dropping the whole previous message instead would also drop the CNF statistics that the `solve` message prints before "Solving...", and the tooltip must show them;
- `progress`: the last non-empty `RunInProgress` line ("Generating CNF...", "Solving..."), for the progress indicator;
- `outcome`: derived from `RunResult` messages. A link starting with `XML: ` together with text containing `Counterexample found`/`Instance found` gives `CounterexampleFound`/`InstanceFound`. `No counterexample found`/`No instance found` gives the negative states. `contrary to expectation` sets an `unexpected` flag. A `RunResult` whose `link` does not start with a known prefix (`XML: `, `CORE: `, `POS: `, `CNF: `) is the core: the link is stored as `CORE: <path>` (see Context). The time is the first `<n>ms.` in the outcome message or a later `RunResult`: the solving time, whether it is in the next message (no core minimization) or in the outcome message itself (with it). The core's own time is not shown in the summary.
- `completed`: whether `RunCompleted` was seen, and its text (`""`, `"Stopped"` or `"failure"`).

All members are synchronized and return snapshots: messages arrive on the server's thread while the progress indicator reads `progress` from another.

Summary texts: `Instance found`, `Counterexample found`, `No instance found`, `No counterexample found`, followed by `, contrary to expectation` when flagged and ` · <n> ms` when known. `Stopped`. `Running…`. If a run completes with no recognized `RunResult` (for example only warnings and "There were errors/warnings!"), the result is `Failed` with summary `Analysis failed`.
- *Why text matching:* the protocol carries no structured outcome. The strings come from the pinned 6.2.0 server and are covered by the captured fixture, and the spec pins the server version.

### 3. Executions without a console: `AlloyCommandExecution`
`AlloyRunProcessHandler` is replaced by `AlloyCommandExecution(service, results, file, command, documentStamp, settingsGeneration, stopTimeoutMillis)`. It keeps the lifecycle API that `AlloyCommandLauncher` and `AlloyRunService` use: `connection`, `warn`, `fail`, `terminate()` (ends as `Stopped`, for example when the project closes), `isFinished`, `waitFor(timeout)`, `render(message)` (now feeding its `log: AlloyRunLog`), and `stop()` (was `destroyProcessImpl`, with the same 5 s confirmation timeout). It writes to `AlloyCommandResults` when started (`Running`), on every message and warning while running (still `Running`, with the output so far for the tooltip), and when it finishes. All of this happens under the execution's lock, so a late `Running` snapshot can never overwrite the final result. On `RunCompleted` the state comes from the log's outcome; with no outcome it is `Stopped` for `"Stopped"` and `Failed` (`Analysis failed`) otherwise. `fail(summary, details, state = Failed)` takes a short summary plus details:

| Cause | Summary | Details |
|---|---|---|
| server not running / died | `Server unavailable` | `AlloyRunService.SERVER_UNAVAILABLE_MESSAGE` |
| empty command list | `Model has errors` | "<file> has errors, see the problems reported in the editor" |
| command not listed | `Command not found` | existing message |
| list timeout / error | `Analysis failed` | existing message |
| settings applied | `Interrupted` | `AlloySettingsApplier.INTERRUPTED_MESSAGE` |
| stop not confirmed (state `Stopped`) | `Stopped` | "The Alloy language server did not confirm that the analysis stopped" |

`AlloyRunService` keeps its single-active-run contract, with `activeExecution` instead of `activeRun`. `startRun(execution)` returns `false` when another execution is active, and leaves the store untouched. Otherwise it registers the execution and then, outside the service's lock, calls `execution.started()`, which records `Running`. Busy refusal is the caller's to report (the busy hint in decision 6).

### 4. Tooltip content
`AlloyCommandResultTooltip.html(result, outdated)` builds the tooltip from `CommandResult.details`. In order, it contains: an italic "Outdated: the file changed after this analysis" line (or "…the analyzer settings changed…") when outdated, the solver fallback warnings, the last 40 log lines (preceded by "…" when there are more), and the failure details. Lines are joined as HTML (`<html>`, escaped text, `<br>`, `<b>` for bold spans, and leading spaces as `&nbsp;` so the analyzer's indentation survives), because the declarative tooltip is a single string and platform hint tooltips render HTML.
- *Risk:* if the tooltip turns out to be shown as plain text, fall back to plain text with `\n`. This is a check in the `runIde` checklist (see Risks).

### 5. Inlay provider and click handler
`AlloyCommandResultHintsProvider : InlayHintsProvider` is registered with `codeInsight.declarativeInlayProvider` (language `Alloy`, `isEnabledByDefault="true"`, group `OTHER_GROUP`, a `providerId`, and a name/description from a new `messages/AlloyBundle.properties`). Its `SharedBypassCollector` visits top-level `AlloyCommandDecl`s (the same ones the gutter uses), looks up `AlloyCommands.ref(command)` in the store, and adds one presentation at `EndOfLinePosition(line of the command's first token)`:
- a current result uses `HintFormat.default`; an outdated one uses `HintColorKind.TextWithoutBackground` (dimmed, no background) and the suffix ` (outdated)`;
- when the result has a `link`, or is `Running`, the text is wrapped in `InlayActionData(StringInlayActionPayload(<encoded ref>), HANDLER_ID)`. The platform then shows it as clickable on hover.

`AlloyCommandResultClickHandler : InlayActionHandler` (registered with `codeInsight.inlayActionHandler`) decodes the ref, finds the file from the editor, and either stops the active execution (`Running`) or sends `OpenModel(link)` through `LspAlloyServerConnection.find(project)`. If the server is not running, it shows an editor error hint with the "unavailable" message.
- *Alternative:* raw `InlayModel.addAfterLineEndElement` with a custom renderer. It gives full control over styling (italics, strikethrough), but the plugin would have to manage creating, moving and disposing inlays per editor, and the user could not turn the hints off in *Settings | Editor | Inlay Hints*. Rejected.
- *Alternative:* code vision (lenses above the line). Rejected: the request asks for end-of-line hints.

### 6. Starting a command: gutter action and background task
`AlloyRunLineMarkerContributor` returns an `Info` whose single action is a new `AlloyRunCommandAction` (icon `AllIcons.RunConfigurations.TestState.Run`, text "Run <displayName>") instead of `ExecutorAction.getActions(0)`. The action resolves the command from the gutter element and calls `AlloyRunService.launch(file, ref, editor)`:
1. If a run is active, show `HintManager.showInformationHint(editor, BUSY_MESSAGE)` and stop. The store is not touched.
2. Record `Running` with the current document stamp, and register the execution as active.
3. Start a `Task.Backgroundable` ("Alloy: Run show", cancellable). On its pooled thread it waits for a server connection (the existing 15 s `awaitConnection`), then calls `AlloyCommandLauncher.launch`, and then waits until the execution finishes. While it waits it updates `indicator.text2` from `AlloyRunLog.progress` and polls `indicator.isCanceled` to call `execution.stop()`.

The file is open in an editor, because the gutter is clicked there, so the server already has its text and the "open the file first" step of `AlloyCommandRunState` goes away.
- *Alternative:* a non-blocking `ProgressIndicator` driven only by callbacks. Rejected: `Task.Backgroundable` is the standard cancellable status-bar progress, and keeping one pooled thread blocked during the single allowed analysis costs little.

### 7. Removals and descriptor
Delete `AlloyCommandConfigurationType`, `AlloyCommandRunConfiguration` (with `AlloyCommandOptions`), `AlloyCommandRunConfigurationProducer`, `AlloyCommandSettingsEditor`, `AlloyCommandRunState`, `AlloyConsoleSink`/`ConsoleViewSink`, and `BOLD_OUTPUT`. In `plugin.xml`, remove `configurationType` and `runConfigurationProducer`, add the inlay provider, the action handler and `<resource-bundle>`. `AlloyCommandRef`, `AlloyCommands`, `AlloyCommandLauncher`, `AlloyServerConnection` and the LSP layer stay.

### 8. Testing
- `AlloyRunLogTest` (plain JUnit): the three captured runs (instance, counterexample, stopped) give the expected outcome, summary, log lines (with the replaced progress line gone) and progress, plus synthetic unsat/core/no-core/warning/expectation sequences built from the server strings quoted above.
- `AlloyCommandResultsTest` (`BasePlatformTestCase`): outdated after `document.insertString`, after a real `UndoManager` undo, after a settings-generation bump, and current again after a relaunch. Light tests share their project, so the store has a `@TestOnly clear()`.
- `AlloyRunServiceTest` and `AlloyCommandLauncherTest` are ported to assert stored results (busy refusal, failures → summaries, stop with and without confirmation, server stopped, solver warning in details) using `FakeServerConnection` and a `newExecution` helper. `AlloySettingsApplierTest` covers the settings interrupt and the generation counter.
- `AlloyCommandResultTooltipTest` (plain JUnit): rendered HTML for a captured run, escaping, outdated notices, warning and failure placement, and the 40-line cap.
- `AlloyCommandResultHintsProviderTest`: runs the provider's collector over a fixture file with a recording `InlayTreeSink`, and asserts the positions (end of first line), texts, formats (outdated → `TextWithoutBackground`), tooltips and action payloads (only for links/running).
- Click handler: the payload for an instance → `FakeServerConnection.opened == ["XML: …"]`, core → `["CORE: …"]`, running → `stops == 1`.
- `AlloyRunMarkersAndProducerTest` keeps the marker tests and drops the producer tests. `AlloyCommandRunConfigurationTest` is deleted. `AlloyRunRenderingTest` and `RecordingSink` go together with `AlloyRunProcessHandler` (task 3.3), because they test only the console. Until task 4.3 removes it, `AlloyCommandRunState` runs an execution without a console and returns no execution result.
- `runIde` manual checklist in the README replaces "Manual checklist: command execution".

## Risks / Trade-offs

- [Outcome detection depends on English server strings] → They come from the pinned 6.2.0 server and are tested against the captured fixture. Unknown output degrades to `Analysis failed`, and the tooltip still shows the full log.
- [Declarative tooltip may not render HTML or long multi-line text well] → Check early in `runIde` (manual checklist item). Fall back to plain text, and cap the log at the last ~40 lines with a "…" marker.
- [`HintColorKind.TextWithoutBackground` may be too subtle as the only cue] → The ` (outdated)` suffix is the primary cue, and the dimmed style is secondary. The tooltip also says so.
- [Results keyed by `AlloyCommandRef` can attach to a different unlabelled command after reordering] → Such results are outdated by then. Accepted.
- [Core link fix assumes the bare path is a core file] → Only links without a known prefix are prefixed with `CORE: `. Covered by a manual check with core minimization on.
- [Losing run configurations and the Run tool window] → Explicit decision of this change (proposal, **BREAKING**). Re-running is one gutter click.

## Migration Plan

Saved "Alloy command" run configurations become unknown configurations in `workspace.xml`. The platform shows them as broken, and the user can delete them. No data migration is needed. Before archiving, the main specs must exist: sync `openspec/changes/archive/2026-10-19-add-alloy-intellij-plugin/specs/*` into `openspec/specs/` first, because archive refuses MODIFIED/REMOVED deltas against missing specs. Rollback means reverting the change.
