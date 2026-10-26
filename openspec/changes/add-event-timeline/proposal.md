# Proposal

## Why

A trace in the instance preview shows states, but not which event led from one to the next. To see events, Practical Alloy teaches the "event depiction" idiom. You add an `enum Event`, one `_happens` function per event and an `events` function to the model, purely so that the visualizer shows the event as a skolem. That boilerplate grows with every event and parameter. The protocol design chapter, for example, steps through 8-state leader election traces and reconstructs from the inbox and outbox changes whether a node did `send` or `process`. The plugin can compute this from the model itself.

## What Changes

- **Event predicates.** For a trace result, the plugin determines the model's event predicates.
  - By default they are found in the facts shaped like the book's trace fact, for example `always (stutter or some n : Node | node_acts[n])`.
  - A grouping predicate whose body is itself a disjunction of calls, such as `node_acts`, is expanded into the events it groups (`initiate`, `send`, `process`).
- **Choosing the events.** An "Events" dropdown in the instance preview's toolbar shows the detected predicates checked, and lets the user check or uncheck any predicate of the model.
  - The choice is remembered per file and survives IDE restarts.
  - "Reset to detected" goes back to the automatic choice.
- **Events of each transition.** For each transition from state *i* to its successor, the plugin computes the event instances that hold at state *i*: every checked predicate, with every tuple of arguments that makes it true, such as `initiate[Node$2]`, `process[Node$0, Id$1]` or `stutter`.
  - Several events may hold at once, and all of them are shown.
  - When no event holds, the transition says "no event".
  - Long lists are shortened with "+k more", and the full list is in the tooltip.
- **Timeline.** A timeline strip under the toolbar has one entry per transition, labelled with its events, including the loop-back transition.
  - Clicking an entry selects its state.
  - Clicking an event's name opens the editor at the predicate.
  - Coordination with the sibling change `add-trace-loop-indicator`, which adds an Analyzer-style state strip with the loop arc and replaces the stepper and its "State i of n" label: the two are independent but composable. Whichever lands first owns the strip, and the other attaches to it. If the state strip exists, event labels go on its transitions rather than in a second strip.
- **Pane caption.** In the Visualization and Table tabs, the successor pane's caption also names the events that lead to it, for example "State 3, after send[Node$1, Id$2]".
- Static (single-state) results are unchanged, and so are models without detectable events and with no events chosen (no timeline, no "after" in the caption).

## Capabilities

### New Capabilities
- `alloy-trace-events`: identifying a model's event predicates (detection from the trace fact, the user's per-file choice) and computing which event instances hold at each transition of a trace.

### Modified Capabilities
- `alloy-instance-viewer`:
  - "Two trace states arranged by the tab's shape": the successor pane's caption names the transition's events.
  - New requirements: the timeline of transitions, and the Events dropdown in the toolbar.

## Impact

- **Code.**
  - `analysis/AlloyAnalysisEngine` and `AlloyAnalysisEvent.Sat` hand back the compiled module (`CompModule`) together with the solution.
  - `run/AlloyCommandResults.CommandLink.Solution` keeps it, so the viewer can find the predicates and the facts.
  - New in `viewer/`:
    - event detection over the compiled facts;
    - an event evaluator: one comprehension per predicate per state, through `A4Solution.eval`;
    - the timeline component, and an Events chooser like `AlloyProjectionChooser`.
  - `AlloySolutionModel` gains the events per transition, and `AlloyInstancePanel` shows them.
  - `AlloyInstanceViewer`'s workspace state stores the per-file choice.
- **Dependencies.** None new. Public Alloy 6.2.0 AST classes are used: `Func`, `ExprCall`, `ExprQt`, `ExprUnary`, `ExprBinary`, `ExprList` and `Decl`.
- **Tests.**
  - New unit tests for detection and evaluation over the Practical Alloy models in `src/test/testData/practicalalloy` (leader election and file sharing).
  - Viewer tests for the timeline, the caption and the chooser.
  - New items in the DEV.md manual checklist.
