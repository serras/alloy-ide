# Design

## Context

- **No offered solver produces a core.** Checked on the bundled pardinus 6.2.0: `SATFactory.prover()` is true only for `minisat.prover`, a JNI library. `AlloySolvers` excludes JNI solvers, and the build strips `native/**` (see the archived `in-process-alloy` design, which already concluded that "unsat cores are not shown").
  - A spike with SAT4J gave an empty `A4Solution.highLevelCore()` (`Pair<Set<Pos>, Set<Pos>>`) for `fact { some A } fact { no A } run {}`, which the translator itself finds trivially false.
  - So the existing `AlloyAnalysisEvent.Unsat.core` is always empty in practice. The run log's "No unsat core is available in this case" line is what users always see.
- **The command's formula holds every fact.** `CompModule.resolveCommands(getAllReachableFacts())` conjoins every reachable fact into `Command.formula`. The spike printed `AND[(all x | some x . (this/A <: r)), some this/A, no (this/A <: r)]` for two facts and an empty `run`.
  - For a `check`, the formula is the facts and the negated assertion.
  - `Command.change(Expr)` returns the same command, with the same scopes, over another formula.
  - Field declarations, multiplicities and `one`/`lone`/`some` signatures are not in that formula: the translator adds them itself.
- **Unsat results keep nothing.** `AlloyCommandExecution` records them without a `CommandLink`, so their hint has no action. The hints provider only attaches the click action when `result.link != null` or while running.
- **Highlighting machinery exists in the platform.** `HighlightManager.addRangeHighlight(editor, start, end, key, hideByTextChange, hideByAnyKey, outHighlighters)` with `HighlightManager.HIDE_BY_ESCAPE` provides Esc-to-clear per editor. This is how *Highlight Usages* behaves. `AlloyPositions.file`/`textRange` already map a `Pos` (including library files) to a file and range.

## Goals / Non-Goals

**Goals:**
- A core that is correct and minimal at the top-level-formula granularity, with every offered solver, inside the IDE.
- Highlights that behave like the IDE's other transient highlights (Esc, edits).

**Non-Goals:**
- Finer granularities, such as the desktop's "Flatten once/twice" and "Expand quantifiers". The core is never smaller than a top-level conjunct.
- Treating declaration constraints (multiplicities, signature multiplicities) as core members. When they alone cause the contradiction, the user is told so instead (see the spec's "Contradiction outside the formulas").
- Enabling `minisat.prover` or any JNI solver, or running a solver in a separate process.
- Navigating between core formulas with next/previous actions. The tooltip lists them, and the error stripe marks navigate.

## Decisions

### 1. Deletion-based core over the command's top-level conjuncts

The core is computed by a new `AlloyCoreFinder` in `analysis/`, started through `AlloyAnalysisEngine.start`, so it gets the large stack, the stopping and the `StoppableSatFactory` of any analysis.

1. **Split.** Flatten `Command.formula` into its top-level conjuncts. Unwrap `ExprUnary` `NOOP`s, `ExprList` `AND`s and `ExprBinary` `AND`s, the same way the translator's `recursiveAddFormula` splits them for "Top-level" granularity. For a `check`, the negated assertion is one conjunct.
2. **Empty case.** Solve `command.change(ExprConstant.TRUE)` first. If it is unsatisfiable, the core is empty, and the result says "contradiction outside the formulas".
3. **Deletion.** Keep a working set `S`, initially all the conjuncts. For each conjunct `c` in source order, solve `command.change(and(S − c))`. If that is unsatisfiable, drop `c` from `S`; otherwise keep it. The final `S` is the core, with each member's `Expr.span()` as its position.
4. **Progress.** Each solve reports "Trying formula k of n (file:line)" to the background task.

It uses the configured solver and options, as the command did.

*Alternatives:*
- **QuickXplain / binary splitting.** It needs fewer solves when the core is small, but it is harder to make stoppable and to report progress for. Plain deletion is simpler and fine for the book's models (tens of conjuncts). It can be swapped later behind the same interface.
- **Enabling `minisat.prover`.** It is exact and fast, but it reverses the project's decision against JNI solvers, which can crash the IDE.
- **A child JVM running `minisat.prover`.** It needs the native libraries back (four platforms) and a solver protocol across processes. It also breaks the "no separate process" requirement for a non-external solver.

### 2. Unsat results keep what the core finder needs

The core finder needs the exact `Command` and `allReachableSigs` that the analysis used: re-parsing could see changed opened modules.

- `AlloyAnalysisEngine.execute(contents, …)` already parses inside the analysis. It will report the parsed module's sigs along with the command, by extending `AlloyAnalysisEvent.Executing` with `sigs`.
- `AlloyCommandExecution` then records unsat results with a new `CommandLink.Core(command, sigs, core = null)`.
- The hints provider attaches the click action for any `link`, as it does for solutions. So no-instance hints become clickable without a special case.

*Alternative:* re-parse the current text on click. That is simpler, but a module edited since the run would give positions that do not match what was analyzed.

### 3. A project service owns the shown core and its computation

`AlloyUnsatCores` (project service in `run/`) does three things:

- **Starting the computation.** It starts the core computation through `AlloyRunService`. A new `startCore`/`coreFinished` pair uses the same `active`/`enumerating` exclusion, so the busy message and the gutter spinner work unchanged. The gutter's "running" check also asks this service.
- **Recording the result.** It records progress in the result: the state stays `NoInstance`/`NoCounterexample`, the summary gets the " · computing core…" suffix, and clicking stops. On success it stores the core in the `CommandLink.Core` and updates the summary to " · core: n formulas", or to " · no formula in core" for the empty case.
- **Showing the core.** It holds the core shown in the project, as a list of `(filename, Pos)`.

**Highlights.**

- For each open editor whose file appears in the core, the service adds `HighlightManager` range highlighters with `HIDE_BY_ESCAPE | HIDE_BY_TEXT_CHANGE`, the new `ALLOY_UNSAT_CORE` key, and an error-stripe mark (`RangeHighlighter.setErrorStripeMarkColor`/tooltip).
- A `FileEditorManagerListener.fileOpened` adds them to editors opened later, so cores in modules that were not open still show.

**Clearing everywhere.**

- Pressing Esc in one editor hides only that editor's highlighters through `HighlightManager`. The service listens for that (`RangeHighlighter` disposal through the markup model's listener) and clears the shown core everywhere.
- It also clears on:
  - a document change in any Alloy file (the existing document listener in `AlloyCommandResults` notifies it);
  - `AlloySettings` generation changes;
  - `AlloyRunService.startRun`.
- The computed core stays stored in the `CommandLink.Core` while the result is current, so clicking the hint again re-shows it without solving.

*Alternative:* `DocumentMarkupModel` highlighters, which persist across editors of a document. They give no Esc handling, and files that are not open still need a listener, so this gains nothing.

### 4. Color key

- `AlloyHighlighterColors.UNSAT_CORE` falls back to `EditorColors.SEARCH_RESULT_ATTRIBUTES`, which every bundled scheme defines for light and dark.
- It is listed as "Unsat core" on `AlloyColorSettingsPage`.
- The demo text gets a highlighted span through `getAdditionalHighlightingTagToDescriptorMap` (`<core>…</core>`).

### 5. Outdated results and messages

- **Outdated hint.** On click, an outdated no-instance hint shows an information hint: "The model changed after this analysis. Run the command again to find its unsat core."
- **Empty core.** It shows "No formula is needed for this contradiction: it comes from the declarations (such as multiplicities and `one` signatures) or from the scopes."
- **Tooltip.** `AlloyCommandResultTooltip` appends a "Unsat core" section: either one line per core formula, as "`file.als:12` text of its first line" in source order, or the empty-core sentence.

### 6. Existing core settings

"Unsat core minimization" and "granularity" keep being passed to the analyzer unchanged. They have no effect with the offered solvers, as today. The README states that the IDE's core is computed per top-level formula, independently of those options.

## Risks / Trade-offs

- **[n+1 solves can be slow for large models or long traces.]** → The computation is explicit (a click), it shows "formula k of n", and it can be stopped like any analysis. Typical book models have tens of conjuncts.
- **[A conjunct may be satisfiable-or-not depending on the solver's bound, e.g. temporal steps.]** → Every solve uses the command's own scopes and steps, so the core is minimal for exactly the analysis that was run. The tooltip names the command and scopes.
- **[Minimality is per conjunct, not a smallest core.]** → Deletion yields a minimal core (no member can be removed), not a minimum one. The desktop's prover cores have the same property.
- **[Splitting may differ from the translator's for some shapes, e.g. `ExprQt` bodies or `let`.]** → Only `AND` and `NOOP` are split. Anything else is one conjunct, which is still correct, just coarser.
- **[`HighlightManager` highlighters are per editor; split editors of one file each need theirs.]** → The service iterates `EditorFactory.getEditors(document, project)`.
