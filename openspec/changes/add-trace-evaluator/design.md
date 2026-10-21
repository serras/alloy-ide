# Design

## Context

- The tool window (`AlloyInstancePanel`) shows one `ViewerSession` at a time. Its `AlloySolutionModel` keeps the `A4Solution` and the selected `state`.
  - The panel is told of a new session through `AlloyInstanceViewer.addListener`, which calls `rebuild()`.
  - It is told of stepping directly, in `StepAction`, which calls `stateChanged()`.
- The desktop evaluator (`SimpleGUI`'s `Computer`) re-parses the model, adds every atom and skolem of the solution to the root module with `addGlobal`, parses the expression with `CompUtil.parseOneExpression_fromString(root, text)`, and evaluates it with `A4Solution.eval(expr, state)`. That call returns an `A4TupleSet`, a `Boolean`, or an integer as a `String` with an optional ` (OF)`.
- Today the plugin keeps the solution but not the module.
  - `AlloyAnalysisEngine.execute` parses its own `CompModule` for each analysis, and only `Sat(solution)` reaches `CommandLink.Solution`.
  - Enumerated solutions (`A4Solution.fork`) come from the same module.

## Goals / Non-Goals

**Goals:**
- Evaluation as the desktop evaluator does it, so that values and errors match what the book shows.
- A debugger-like UI: one expression field with history, plus watches that follow the selected state.

**Non-Goals:**
- Evaluating at the successor pane, or at several states at once. The value at the next state is available as `e'` or `after e`.
- Showing evaluation results in the graph (for example highlighting the atoms of a value).
- Evaluating without a shown result, for example against an ad-hoc instance.
- The Alloy `Computer` debugging mode that uses `SimInstance`.

## Decisions

### 1. Keep the analyzed module with the solution

- `AlloyAnalysisEvent.Sat` gains the `CompModule` that the analysis parsed.
  - `execute(contents…)` passes the module it parsed.
  - The `execute(sigs, command…)` overload has no module, and passes `null`. The evaluator then says that evaluation is not available.
- `CommandLink.Solution` keeps it as `module`, and `AlloySolutionModel` receives it.
- An enumerated session reuses the module of the session it came from, as it already reuses its theme.

Alternative considered: re-parsing the file's current text for every evaluation, as `SimpleGUI` re-parses the XML's embedded sources. That would evaluate outdated results against edited names, which the spec rules out. Re-parsing the stored sources instead would duplicate work the analysis already did.

### 2. Evaluation context per solution

`AlloySolutionModel.evaluate(text): EvaluationResult` works as follows:

1. It calls `module.clearGlobals()` and adds the solution's `getAllAtoms()` and `getAllSkolems()`, as `SimpleGUI` does.
2. It parses the text, and calls `solution.eval(expr, state)`.
3. It maps the outcome into `Tuples(List<String>)`, `Bool`, `IntValue(String)` or `Error(message)`.
   - `HigherOrderDeclException` becomes the desktop's message.
   - Any other `Err` becomes its `msg`.

Because the module's globals are mutated, every evaluation of a module runs under one lock owned by the module. This is safe across sessions that share a module, such as a forked solution and the one it came from. The analysis never uses these globals: they are consulted only by `parseOneExpressionFromString`.

### 3. Off the EDT, latest wins

Evaluations run on a pooled thread, one at a time per tool window, through a single-thread executor.

- Every request carries a generation number, taken from a counter that increases on each state change, new session and Enter.
- Results whose generation is stale are dropped when they reach the EDT.
- A Kodkod evaluation cannot be interrupted, so a superseded one simply runs to completion and is discarded.
- The panel shows "Evaluating…" for rows that are pending.

Alternative considered: evaluating on the EDT. Most evaluations are instant, but quantified formulas over larger scopes are not, and the debugger never blocks the UI.

### 4. UI layout

- The panel is a `OnePixelSplitter` (vertical) under the tabs. Its proportion and visibility are stored in `AlloyInstanceViewerState`, next to `selectedTab`. A toolbar `ToggleAction` "Evaluator" controls it.
- Inside the panel there is:
  - a caption line: "Evaluating at state *i*", plus "(outdated result)" when the session is outdated;
  - the expression field, with an "Add to Watches" action next to it (also bound to Ctrl/Cmd+Shift+Enter, as in the debugger);
  - a `Tree` whose first root is the last single evaluation, labelled as such, followed by one root per watch.

This mirrors the debugger's Variables view with inline watches.

Each row is drawn as follows:

| Row | Rendering |
|---|---|
| Set of tuples | `expr = {n tuples}`, expanding to one child per tuple; `expr = {}` when empty |
| Formula | `expr = true` / `false` |
| Integer | `expr = 3` (with ` (OF)` if reported) |
| Error | the message in the error color |

Changed watches use the debugger's "changed value" attributes (`XDebuggerUIConstants.CHANGED_VALUE_ATTRIBUTES` or the equivalent scheme key). Values are compared by their rendered text with the previous evaluation of the same watch, on the same model file.

The watches toolbar has Add, Remove, Edit (also double-click or F2), Move Up and Move Down, using the platform's `ToolbarDecorator`.

### 5. The expression field

- The field is an `EditorTextField` over an Alloy `PsiFile` fragment, created with `PsiFileFactory`, so the existing lexer highlights it. The fragment carries a user-data key, `ALLOY_EVALUATOR_FRAGMENT`.
- `AlloyCompletionContributor`, `AlloyKeywordCompletionContributor` and `AlloyExternalAnnotator` skip files with that key. Otherwise the fragment would be treated as a module with errors and wrong names.
- A small `AlloyEvaluatorCompletionContributor` serves fragments with the key. It offers:
  - the names of the session's module: sigs, fields, funs and preds, including those of opened modules, qualified by alias when ambiguous;
  - the solution's atoms and skolems.
- History is kept in memory for the tool window, at most 50 entries, and is reachable with Up/Down as in the debugger's evaluate field.

Alternative considered: a plain `TextFieldWithAutoCompletion`. It is simpler, but has no syntax highlighting, and the book's expressions are long enough that highlighting helps.

### 6. Persistence of watches

- Watches are stored per model file, keyed by the file's URL, as `Map<String, List<String>>` in a new `AlloyEvaluatorState`.
- The state is a project-level `SimplePersistentStateComponent` in `StoragePathMacros.WORKSPACE_FILE`, like the viewer's own state.
- Watches are expressions over one model's names, so sharing one list across files would mostly show errors. Per run command would be too fine: the book's watches, such as `Elected` and `node_enabled[n]`, apply to every command of the model.
- Not stored in VCS-shared files, as the debugger's watches are not.

### 7. Wiring to state changes

`AlloyInstancePanel.rebuild()` (new session) and `stateChanged()` (stepping) call the evaluator panel's `refresh(session)`. This re-evaluates the last single expression and every watch at `model.state`. No viewer API changes beyond exposing the module.

## Risks / Trade-offs

- [The `addGlobal` names shadow model names, as in the desktop evaluator] → Same behavior as the desktop, which the book uses. Atoms use `$`, so the overlap is rare.
- [A slow evaluation delays the next ones, since they run one at a time] → The UI stays responsive and stale results are dropped. A runaway evaluation only costs time, and no analysis is blocked, because evaluation is not counted as an analysis.
- [The watches of a renamed or moved file are lost] → Acceptable. The debugger loses watches in similar cases too. The stored map is pruned of URLs whose file no longer exists, when the state loads.
- [Keeping the module retains memory for every stored result] → One module per analysis, already reachable through the solution's sigs, so the extra cost is small.
- [Skipping the fragment in existing contributors could miss one] → Covered by a test that the fragment gets no diagnostics and no model-file completion.
