# Proposal

## Why

Running a `run` or `check` command today opens a Run tool window with the analyzer's raw log. To learn the one thing that matters (was an instance or counterexample found?), the user has to leave the model, read the console, and click a link in it. The run configuration and console are leftovers of the server's protocol, not a good fit for Alloy's quick edit-and-check loop. Results should sit next to the command that produced them, show at a glance whether they are still current, and keep the server interaction out of sight.

## What Changes

- Show the outcome of each executed `run`/`check` command as an inlay hint at the end of the command's line: running, instance found, no instance found, counterexample found, no counterexample found, stopped, or failed (model has errors, server unavailable, and so on).
- Make the hint clickable when there is something to open. An instance or counterexample opens the Alloy visualizer. An unsat core highlights the core in the editor. Clicking the hint of a running command stops it.
- Put everything else the analyzer reports in the hint's tooltip: the executed command, solver and scope line, CNF statistics, timings, expectation, core size, warnings, and the solver fallback warning.
- Mark results as outdated as soon as their file is edited, and after analyzer settings change. An outdated hint is dimmed and labelled as outdated, and it still opens its (old) instance when clicked.
- Show analysis progress as a cancellable background task in the status bar. Cancelling it stops the analysis.
- The gutter icon on `run`/`check` runs the command directly. If another analysis is running, the editor shows a hint saying so.
- **BREAKING**: remove the "Alloy command" run configuration type, its configuration producer, editor and Run console output. Commands can no longer be started from the run toolbar, *Run* context menu or saved configurations. Saved configurations of that type become unknown configurations.
- Fix the unsat core link: the server reports the core as a bare file path, but its `OpenModel` only handles `CORE: <file>`, so the plugin adds the prefix itself.

## Capabilities

### New Capabilities

None.

### Modified Capabilities
- `alloy-command-execution`: gutter markers run commands directly; the run configuration and console output requirements are removed; results, outdated state, progress, stopping, the busy refusal, opening instances/cores and server-unavailable reporting move to the inline hint, its tooltip and a background task.
- `alloy-analyzer-settings`: the solver fallback warning is shown in the result tooltip instead of the Run console; applying settings during an analysis marks it as interrupted on its hint, and marks existing results as outdated.

## Impact

- **Code** (`src/main/kotlin/com/serranofp/alloy/run/`): remove `AlloyCommandConfigurationType`, `AlloyCommandRunConfiguration`, `AlloyCommandRunConfigurationProducer`, `AlloyCommandSettingsEditor`, `AlloyCommandRunState`, and replace `AlloyRunProcessHandler` with a console-less run. Add a per-project store of command results, an analyzer-log interpreter, a declarative inlay hints provider, and an inlay click handler. Adapt `AlloyRunService`, `AlloyCommandLauncher`, `AlloyRunLineMarkerContributor`, and `AlloySettingsApplier`.
- **Plugin descriptor**: drop `configurationType` and `runConfigurationProducer`. Register `codeInsight.declarativeInlayProvider` and `codeInsight.inlayActionHandler`, plus a message bundle for the provider's name and description. The hints can be turned off in *Settings | Editor | Inlay Hints*.
- **Tests**: run-configuration and console-rendering tests are replaced by tests for log interpretation, result state, the hints provider, and click handling.
- **Docs**: update the README feature list and manual checklists, the CHANGELOG, and the `plugin.xml` description.
- **Specs**: the main specs under `openspec/specs/` are empty because the previous change was archived without syncing them. Its archived specs (`2026-10-19-add-alloy-intellij-plugin`) are the baseline for these deltas, and they must be synced before this change is archived.
- **Out of scope**: persisting results across IDE restarts, tracking changes in imported modules (`open`), a "run all commands" action, gutter icons that reflect the last result, and a native instance viewer.
