# Proposal

## Why

When a `run` finds no instance, or a `check` finds no counterexample, the user has to work out by hand *why*. They comment out facts one by one, or wonder whether the check passed only because the facts contradict each other. Practical Alloy's protocol chapter falls into exactly this trap: contradictory `process` effects silently made a liveness check pass. The desktop Analyzer answers the question with an unsat core, highlighting the formulas that cause the contradiction. The plugin cannot do the same: the only solver that produces cores (`minisat.prover`) is a JNI library, and the plugin deliberately does not offer JNI solvers. With SAT4J, `A4Solution.highLevelCore()` is always empty, even for trivial contradictions. The tooltip's "No unsat core is available in this case" is all users get today.

## What Changes

- **Finding the core on demand.** The "No instance found" and "No counterexample found" hints become clickable. Clicking one computes an unsat core for that result and highlights it.
  - The core is the smallest set of top-level formulas of the command (facts, signature facts, the predicate's or the negated assertion's conjuncts) that is still unsatisfiable on its own: removing any one formula makes an instance exist.
  - It is computed with the configured solver, by re-solving the command without each formula in turn. So it works with SAT4J and every other offered solver, inside the IDE.
  - The computation is an analysis like any other. It shows progress, it can be stopped from the hint, the gutter or the progress indicator, and it takes the project's single analysis slot.
- **Highlighting.** Each formula in the core is highlighted in the editor with a new, customizable "Unsat core" color (*Settings | Editor | Color Scheme | Alloy*), with a mark in the error stripe.
  - The core is highlighted in every file it touches: the command's file, opened project modules and bundled `util/*` modules.
  - The hint gains a suffix naming the core size, and its tooltip lists each core formula with its file and line.
- **Clearing.** The highlights disappear when the user presses Esc in an editor showing them, edits any file of the model, changes the analyzer settings, or runs any command. Clicking the hint again shows the core again without recomputing it while the result is current.
- **Empty core.** When the command is unsatisfiable without any of its formulas, the plugin says so. In that case the contradiction comes from the declarations (multiplicities, `one`/`lone` signatures) or the scopes, such as a `Message` scope too small for the protocol. This is the "false sense of safety" pitfall the protocol chapter warns about.
- **Outdated results.** Clicking an outdated no-instance hint does not compute a core: the positions would not match the text anymore. It shows a message asking to run the command again.

## Capabilities

### New Capabilities
- `alloy-unsat-core`: computing, showing and clearing the unsat core of a command that found no instance or no counterexample.

### Modified Capabilities
<!-- none: the existing requirements of alloy-command-execution do not forbid other clickable hints, and the new color key is not a token category of alloy-editor's syntax highlighting -->

## Impact

- **Code.**
  - `analysis/`: a new core finder that splits `Command.formula` into its top-level conjuncts and re-solves `Command.change(Expr)` without them, through `AlloyAnalysisEngine.start`.
  - `run/`: `AlloyCommandResultClickHandler`, the hints provider and the tooltip handle no-instance results and the core suffix; `CommandResult` keeps the computed core; `AlloyRunService` runs the core computation in the project's analysis slot.
  - A new project service owns the active core and its highlighters.
  - `highlighting/`: a new `TextAttributesKey` on `AlloyColorSettingsPage`.
- **Settings.** The "Unsat core minimization" and "Unsat core granularity" options keep driving the analyzer as today, but they do not affect this core: they only apply to provers, which are never offered. This is documented, not changed.
- **Dependencies.** No new ones. JNI solvers stay excluded.
- **Tests and docs.**
  - New tests cover the core finder, the click handling, the highlights and their clearing.
  - The DEV.md item "The hint of a valid check, or of a run with no instance, is not clickable" is replaced by unsat core checks.
  - The README describes the feature.
