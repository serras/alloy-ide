# Tasks

## 1. Module kept with the solution

- [ ] 1.1 Add the parsed `CompModule` to `AlloyAnalysisEvent.Sat` (null for the `execute(sigs, command…)` overload) and to `CommandLink.Solution`, and pass it from `AlloyCommandExecution` (design §1). Verify with an `AlloyAnalysisEngineTest` case: `execute` of a model with `run show` ends in a `Sat` whose module declares `show`. Also verify that the existing engine and run tests pass.
- [ ] 1.2 Give `AlloySolutionModel` the module. `AlloyInstanceViewer.open` passes the link's module, and `finished` passes the previous session's module to the enumerated one. Verify with an `AlloyNewSolutionTest` case: after New Config, the new session's model has the same module instance as the previous one.

## 2. Evaluation

- [ ] 2.1 Add `AlloySolutionModel.evaluate(text)` with the `EvaluationResult` cases `Tuples`, `Bool`, `IntValue` and `Error` (design §2). It clears the module's globals and adds the atoms and skolems under the module's lock, parses with `CompUtil.parseOneExpression_fromString`, and evaluates at `state`. Verify with a new `AlloyEvaluatorTest` on `STATIC`, `LASSO` and a model with a predicate and a skolem:
  - `succ` gives its tuples in the analyzer's order; `some succ` gives `true`; `#Node` gives the count; `none` gives no tuples;
  - `Undefined` and `some (succ` give the analyzer's errors; a higher-order quantification gives the desktop's message;
  - on `LASSO`, `Cur`, `Cur'`, `after Cur = V2` and `always Cur in V2 + V3` give the spec scenarios' values at states 0 and 1;
  - a predicate call and a skolem evaluate;
  - after `New Config`, an atom that no longer exists is reported as not found.
- [ ] 2.2 Add the evaluator's runner: a single-thread executor with generation numbers, whose stale results are dropped on the EDT (design §3). Verify with a test that queues an evaluation at state 0, selects state 1 before it completes (with an injected slow evaluator), and checks that only the state 1 value is delivered.

## 3. Evaluator panel and watches

- [ ] 3.1 Add the `AlloyEvaluatorState` workspace component, with watches per file URL and pruning of missing files on load. Add the panel's visibility and splitter proportion to `AlloyInstanceViewerState` (design §4, §6). Verify with a unit test that round-trips the state (watches of two files, visibility, proportion) through `loadState`/`getState`, and drops a URL with no file.
- [ ] 3.2 Add the Evaluator panel and its toolbar toggle, in a splitter under the tabs of `AlloyInstancePanel`. It has the caption ("Evaluating at state i", "(outdated result)"), the field, Add to Watches, and the tree with the single evaluation followed by one root per watch, rendered as in design §4. Verify with `AlloyEvaluatorPanelTest`:
  - the toggle shows and hides the panel and its visibility is remembered for the next session;
  - evaluating `succ` shows a root with 2 tuples whose children are the tuples;
  - an error row carries the error attributes;
  - the caption names the state and says "outdated" after editing the file.
- [ ] 3.3 Add the watches toolbar (Add, Remove, Edit, Move Up, Move Down) and change highlighting. Wire `rebuild()` and `stateChanged()` to `refresh(session)` (design §4, §7). Verify with `AlloyEvaluatorPanelTest`:
  - adding, editing, removing and reordering update both the tree and the file's stored watches;
  - on `LASSO`, selecting state 1 (`goTo` and `stateChanged()`, as a click in the trace path strip does) re-evaluates `Cur` and marks it changed, and an unchanged watch is not marked;
  - New Fork re-evaluates every watch;
  - opening a result of another file shows that file's watches;
  - a watch naming a removed field stays and shows its error.
- [ ] 3.4 Add the "Evaluator" manual checklist to `DEV.md`:
  - toggle and splitter;
  - Enter and Ctrl/Cmd+Shift+Enter;
  - Up/Down history;
  - changed-value highlight in light and dark themes;
  - watches restored after restarting the IDE;
  - the UI stays responsive during a slow evaluation.
  
  Verify by re-reading the checklist against the spec scenarios.

## 4. Expression field

- [ ] 4.1 Make the field an `EditorTextField` over an Alloy fragment marked with `ALLOY_EVALUATOR_FRAGMENT`. Make the existing completion contributors and `AlloyExternalAnnotator` skip such fragments (design §5). Verify with a test that a fragment with `Undefined` gets no diagnostics and no completion from the model-file contributors, and that the field's text is lexed with Alloy's highlighter.
- [ ] 4.2 Add `AlloyEvaluatorCompletionContributor`, offering the session module's names (also from opened modules) and the solution's atoms and skolems. Add the in-memory history (50 entries, Up/Down). Verify with `AlloyEvaluatorCompletionTest`: `su` offers `succ`; `Node$` offers the `Node` atoms; a skolem is offered; keywords are not offered as model names. Verify with a history test that `succ` then `#Node` are offered latest first.
- [ ] 4.3 Document the evaluator in the README's feature list (a new "Evaluator" item after the Alloy Instance tool window) and in `plugin.xml`'s description. Verify by re-reading both against the spec.

## 5. Integration

- [ ] 5.1 Run the full test suite (`./gradlew test`), including the Practical Alloy example tests, and verify that it passes. Check that `openspec validate add-trace-evaluator --strict` passes.
