# Tasks

## 1. Compiled module with the solution

- [ ] 1.1 Pass the analysis's `CompModule` through `AlloyAnalysisEvent.Sat` and `CommandLink.Solution`; make enumerated solutions in `AlloyInstanceViewer` reuse their session's module (design §1). Verify that the existing `AlloyAnalysisEngineTest`, `AlloyRunServiceTest` and `AlloyInstanceViewerTest` pass, plus a new `AlloyAnalysisEngineTest` case asserting that the `Sat` event of `run example` in leader election carries a module whose `allFunc` contains `initiate`.

## 2. Event detection

- [ ] 2.1 Add a pure `TraceEventDetector` in `viewer/` over `CompModule` (design §2). It handles `always`, `or`, `some`, `let`, grouping expansion with a visited set, and declaration order. Verify with a new `TraceEventDetectorTest`:
  - `protocol-design/specifying-the-protocol-dynamics/leaderelection.als` gives `initiate, send, process, stutter`, without `node_acts`;
  - `event-depiction/depicting-events-with-parameters/filesharing.als` gives its 7 events;
  - a model without a trace fact gives none;
  - a self-recursive grouping predicate terminates and is kept.
- [ ] 2.2 Mark predicates with non-unary or `set` parameters as unsupported, with a reason. Verify with a `TraceEventDetectorTest` case on `pred move[ns : set Node]`.

## 3. Event evaluation

- [ ] 3.1 Add `TraceEvents` (design §3): for each transition *i* (including the last, into the loop target) and each supported predicate, evaluate the comprehension `{ params | p[params] }` (or the formula, for a parameterless predicate) with `solution.eval(expr, i)`, in model order. Record an `Err` per predicate. Verify with a new `TraceEventsTest`:
  - solve `run eventually_elected` of `protocol-design/verifying-the-expected-properties/leaderelection.als`, and check that every transition has at least one event, and that every `process[n, i]` instance has `i` in `n.inbox` at state *i* (cross-checked with `eval`);
  - on file sharing, `upload[File$k]` holds where `uploaded` grows by `File$k`;
  - an injected failing predicate leaves the others intact and records its error.
- [ ] 3.2 Add the label rule: at most 3 instances, then "+k more"; "no event"; full list for the tooltip. Verify with unit tests in `TraceEventsTest`.
- [ ] 3.3 Compute `TraceEvents` in the background in `AlloySolutionModel`, cached by the chosen predicate set and cancelled on a new session (design §4). Verify with an `AlloyInstanceViewerTest` case: the session is shown before the events are known, and selecting another state after they are known performs no new evaluation (counted through a test hook).

## 4. Timeline and captions

- [ ] 4.1 Check whether `add-trace-loop-indicator` has landed. If it has, attach `TransitionLabel`s to its strip's transitions. Otherwise, add `TraceTimeline` under the toolbar line: one cell per transition, the selected transition highlighted, clicking a cell calls `goTo`, and event names navigate through `Func.pos` and `AlloyPositions` (design §6). Verify with `AlloyInstanceTabsTest` cases:
  - a 4-state lasso shows 4 cells with their labels;
  - clicking cell 2 selects state 2;
  - selecting another state (`goTo` and `stateChanged()`, as a click in the trace path strip does) moves the highlight;
  - a static result and a model without events show no strip;
  - the "…" placeholder is shown before the events arrive.
- [ ] 4.2 Append ", after <events>" to the successor caption of the Visualization and Table panes when the transition has events. Verify with `AlloyInstanceTabsTest`: at state 3 of a leader election trace, the second caption ends with the label of transition 3; with no events it equals "State 4" exactly, and the existing caption tests still pass.
- [ ] 4.3 Add the timeline items to the DEV.md "instance viewer" manual checklist:
  - labels readable in light and dark themes;
  - "no event" in a warning color;
  - tooltip with the full list;
  - a plain click on `send` opens its declaration, also inside `util/*`.
  
  Verify by re-reading the checklist against the delta spec scenarios.

## 5. Events dropdown and stored choice

- [ ] 5.1 Store the per-file choice in `AlloyInstanceViewerState`, filtered against the module's predicates when read (design §5). Verify with an `AlloyInstanceViewerTest` case: saving and reloading the state keeps the choice, and a label of a removed predicate is ignored.
- [ ] 5.2 Add the "Events" dropdown, following `AlloyProjectionChooser`: checkboxes in model order, unsupported predicates disabled with their reason, a "Reset to detected" item, and the collapsed text "n detected" or "n chosen". Changes recompute `TraceEvents` without an analysis. Verify with tests:
  - the detected events of leader election are checked, with "4 detected";
  - checking `node_acts` gives "5 chosen" and its instances in the labels, with `AlloyRunService` reporting no analysis started;
  - reset restores "4 detected";
  - static results have no dropdown.
- [ ] 5.3 Add the dropdown items to the DEV.md manual checklist (popup behavior, disabled item tooltip, choice kept after restarting `runIde`). Verify by re-reading the checklist against the delta spec scenarios.

## 6. Integration

- [ ] 6.1 Document the timeline and the Events dropdown in the README's "Instance preview" bullet. Run `./gradlew test`, including `PracticalAlloyModelsTest`, and verify that it passes. Verify that `openspec validate add-event-timeline --strict` passes.
