<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Alloy 6 for IntelliJ Changelog

## [Unreleased]

### Added

- Alloy file type for `.als` files, with syntax highlighting (configurable in *Settings | Editor | Color Scheme | Alloy*) for keywords, temporal operators, operators, literals and comments.
- Structure-aware editing that works on any content, including models with errors: brace matching and auto-closing, line and block comments, folding of bodies, comments and imports, and keyword completion.
- The Alloy 6.2.0 analyzer, included in the plugin and running inside the IDE, on its own Java runtime: no separate process and no system Java installation are needed.
- Native semantic features backed by that analyzer: error and warning diagnostics (also at the `open` of a module with errors), hover and quick documentation, go to declaration (also into the bundled `util/*` modules), highlight usages, find usages, rename, the *Structure* view, breadcrumbs and *Go to Symbol*. References resolve exactly as the analyzer resolves them when the model type-checks, and by name while it does not; uses found by name are listed as unconfirmed usages, and rename waits for a model without errors.
- Completion of the names visible at the caret, under the same scope rules as resolution and also while the model has errors: binders, parameters, fields, the module's declarations and those of the modules it opens (except their `private` ones), with icons, kinds and the module they come from. After a qualifier such as `ord/` or `this/`, only that module's names are offered; after `run` only predicates and functions, after `check` only assertions, and after `extends` or `in` only signatures.
- Completion of module paths after `open`: the project files that the analyzer would load from there, by their `module` name or their location, and the bundled `util/*` modules. Files whose `module` name does not match their location are shown greyed out, as they cannot be opened from that file.
- Go to declaration on the path of an `open` declaration, which opens the project or bundled module that the analyzer loads for it.
- The bundled `util/*` modules are a read-only "Alloy 6.2.0" library, listed under *External Libraries* and indexed for *Go to Symbol* when non-project items are included; they open in Reader mode when it is enabled for library sources.
- Running `run` and `check` commands from the gutter, with their results as inlay hints at the end of each command's line: the hint names the outcome, its tooltip holds the analyzer output, and clicking it shows the instance or counterexample, or stops a running analysis. Results are marked as outdated after an edit of the file or a change of the analyzer settings, progress is a cancellable background task in the status bar, and the gutter icon of a running command is a spinner whose menu has *Stop*.
- The *Alloy Instance* tool window, on the right of the editor, which shows the instance or counterexample of a command with a header that navigates to it: a graph with the magic layout, the analyzer's text and table forms, new configurations, traces, initial states and forks (which keep the graph's theme and projections), stepping through the states of a trace, and projection over any number of signatures.
- Analyzer settings in *Settings | Languages & Frameworks | Alloy* (solver, skolem depth, overflow prevention, unsat core minimization and granularity, non-fatal warnings, recording the Kodkod input/output, partial instance inference), independent of the Alloy desktop application's preferences. Applied changes take effect with the next analysis. Besides the pure-Java SAT4J variants, the solver list offers Yices, Plingeling and Electrod (with NuSMV or nuXmv) when their executables are on the `PATH`, also for an IDE started from the desktop.

- Expectations as test outcomes: a command with `expect 0` or `expect 1` gets a green passed or red failed gutter icon after its result, and its hint says "as expected" or "contrary to expectation".
- Vacuity check of passing checks: a `check` without counterexample goes on to solve the model's facts, and the assertion's premise (`A implies B`, also under `all` and `always`), within its scope. A check whose facts or premise have no instance passed vacuously: its hint says "(vacuous)", its gutter icon is a yellow vacuous icon, and its tooltip explains why. It can be stopped without losing the check's result, and turned off with *Check vacuity of passing checks* in *Settings | Languages & Frameworks | Alloy*.
- The *Alloy Commands* run configuration, created with *Run 'All commands in a.als'* from the context menu of an Alloy file, which runs every command of the file in the IDE's test runner: one node per command with its time and analyzer output, failures for unmet expectations, failed analyses and vacuous passes, navigation to the command, *Rerun Failed Tests* and *Stop*.

### Changed

- **Breaking:** instances and counterexamples are shown in an instance preview beside the text, in the editor of each Alloy file of the project, instead of the *Alloy Instance* tool window, which is removed. Each file shows its own result while it is open; a file opens as its text, and clicking a result hint shows the preview beside it. The buttons in the editor tab show the text only, both, or the preview only, side by side or stacked. The bundled `util/*` modules open as text only.
- The plugin no longer requires the IntelliJ Platform's LSP support module (`com.intellij.modules.lsp`): it installs in any IntelliJ-based IDE from 2026.2.

### Removed

- The run configuration type for single `run` and `check` commands, its configuration producer and its Run tool window output. Commands are executed from the gutter, and all the commands of a file with the *Alloy Commands* run configuration.

### Known limitations

- Solvers that need a native library (MiniSat, Glucose, the MiniSat prover) are not available, so unsat cores are not shown.
- Analyses use the IDE's memory and threads; an analysis that runs out of memory or stack is reported as a failure, but models with very large scopes are better run in the Alloy desktop application.
- Alloy models embedded in Markdown (`.md`) files are not supported.
- Only one analysis, including the search for a new solution, runs at a time in each project.
