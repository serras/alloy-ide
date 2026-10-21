# Tasks

## 1. Transitions and strip geometry

- [ ] 1.1 Add `Transition(from, to, isLoop)` and `AlloySolutionModel.transitions`: the consecutive pairs in order, then the loop from the last state to `loopState` (design §3, §5). Verify with new cases in `AlloyStateStepperTest`:
  - the 3-state lasso looping to 1 gives `0→1`, `1→2`, `2→1 (loop)`;
  - a 2-state trace whose last state loops to itself ends with `1→1 (loop)`;
  - a static result has no transitions.
- [ ] 1.2 Add the pure `TraceStripLayout` (design §1–§3), with Alloy's constants named in one place and a comment citing `VizGUI.traceGraph()`. It computes circle bounds (radius 12, spacing 45, height 50, scaled), the highlighted set `{selected, successor}`, straight arrow segments, the loop curve (from the top of the last circle, rising 25 px, to the top of the target), `stateAt(point)` and the preferred width. Verify with a new `TraceStripLayoutTest`:
  - on the lasso at states 0 and 2, the circles are ordered left to right and the highlighted sets are `{0,1}` and `{2,1}`;
  - the loop curve's end points touch the tops of circles 2 and 1, and for a self-loop both end points are on the same circle;
  - `stateAt` gives each circle's state at its center and null between circles;
  - the preferred width grows linearly with the trace length.

## 2. Strip component

- [ ] 2.1 Add `AlloyTraceStrip`, which paints a `TraceStripLayout`, with colors by role (design §4): Alloy's colors under a light theme, `DarkPalette.ide()` roles under a dark one, and a repaint plus a palette reset on `LafManagerListener.TOPIC` with a disposable. Verify with a new `AlloyTraceStripTest` that paints the lasso into a `BufferedImage`:
  - under the light mapping, the center pixel of a highlighted circle is white and that of a dimmed circle is `(120,120,120)`;
  - under the dark mapping, the highlighted fill is lighter than the dimmed fill and the background is the palette's canvas.
- [ ] 2.2 Add click and tooltip handling: a click inside a circle calls the selection callback with its state, and a click elsewhere does nothing. `getToolTipText(event)` gives "State k", and for the loop target it adds that the last state loops back to it. Verify in `AlloyTraceStripTest` with synthetic `MouseEvent`s: a click at circle 2's center reports 2; a click between circles reports nothing; the tooltips of states 0 and 1 of the lasso are "State 0" and the loop-target text.
- [ ] 2.3 Wrap the strip in a horizontal-only `JBScrollPane` and add `reveal()`, which scrolls to the bounds of the highlighted pair, or to the selected state when the pair does not fit (design §2). Verify in `AlloyTraceStripTest` with a 30-state layout in a 200 px viewport: after selecting state 25, the viewport contains circles 25 and 26.

## 3. Panel integration

- [ ] 3.1 Place the strip in `AlloyInstancePanel`'s north block, under the controls row (design §5). It is visible only when `model.hasStates`, and `showState()` updates its selection and calls `reveal()`. Clicks go through the same `goTo` plus `showState()` path as the step actions. Verify with new cases in `AlloyInstanceViewerTest`/`AlloyStateStepperTest`:
  - a trace shows the strip and a static result does not;
  - clicking state 2 of the lasso makes the stepper say "State 2 of 3" and the second caption "State 1 (loop)";
  - with a projection over a signature, a click keeps the projection and the chosen atom (`AlloyProjectionTest`).
- [ ] 3.2 Add a "Trace path strip" section to the "instance viewer" manual checklist in `DEV.md`:
  - circles, arrows and the loop curve of a lasso;
  - the highlighted pair following previous and next;
  - clicking a circle;
  - tooltips;
  - horizontal scrolling in a narrow tool window;
  - light and dark themes, and switching between them.
  
  Verify by re-reading the checklist against the delta spec's scenarios.

## 4. Integration

- [ ] 4.1 Run the full test suite (`./gradlew test`), including the Practical Alloy example tests, and verify that it passes. Check that `openspec validate add-trace-loop-indicator --strict` passes.
