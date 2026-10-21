# Tasks

## 1. Transitions and strip geometry

- [x] 1.1 Add `Transition(from, to, isLoop)` and `AlloySolutionModel.transitions`: the consecutive pairs in order, then the loop from the last state to `loopState` (design §3, §5). Verify with new cases in `AlloyStateStepperTest`:
  - the 3-state lasso looping to 1 gives `0→1`, `1→2`, `2→1 (loop)`;
  - a 2-state trace whose last state loops to itself ends with `1→1 (loop)`;
  - a static result has no transitions.
- [x] 1.2 Add the pure `TraceStripLayout` (design §1–§3), with Alloy's constants named in one place and a comment citing `VizGUI.traceGraph()`. It computes circle bounds (radius 12, spacing 45, height 50, scaled), the highlighted set `{selected, successor}`, straight arrow segments, the loop curve (from the top of the last circle to the top of the target, through control points at the top of the strip, 25 px sideways from each end), `stateAt(point)` and the preferred width. Verify with a new `TraceStripLayoutTest`:
  - on the lasso at states 0 and 2, the circles are ordered left to right and the highlighted sets are `{0,1}` and `{2,1}`;
  - the loop curve's end points touch the tops of circles 2 and 1, and for a self-loop both end points are on the same circle;
  - `stateAt` gives each circle's state at its center and null between circles;
  - the preferred width grows linearly with the trace length.

## 2. Strip component

- [x] 2.1 Add `AlloyTraceStrip`, which paints a `TraceStripLayout`, with colors by role (design §4): Alloy's colors under a light theme, `DarkPalette.ide()` roles under a dark one, and a repaint plus a palette reset on `LafManagerListener.TOPIC` with a disposable. Verify with a new `AlloyTraceStripTest` that paints the lasso into a `BufferedImage`:
  - under the light mapping, the center pixel of a highlighted circle is white and that of a dimmed circle is `(120,120,120)`;
  - under the dark mapping, the highlighted fill is lighter than the dimmed fill, and the dimmed fill is the background around the strip.
- [x] 2.2 Add click and tooltip handling: a click inside a circle calls the selection callback with its state, and a click elsewhere does nothing. `getToolTipText(event)` gives "State k", and for the loop target it adds that the last state loops back to it. Verify in `AlloyTraceStripTest` with synthetic `MouseEvent`s: a click at circle 2's center reports 2; a click between circles reports nothing; the tooltips of states 0 and 1 of the lasso are "State 0" and the loop-target text.
- [x] 2.3 Wrap the strip in a horizontal-only `JBScrollPane` and add `reveal()`, which scrolls to the bounds of the highlighted pair, or to the selected state when the pair does not fit (design §2). Verify in `AlloyTraceStripTest` with a 30-state layout in a 200 px viewport: after selecting state 25, the viewport contains circles 25 and 26.

## 3. Panel integration

- [x] 3.1 Place the strip in `AlloyInstancePanel`'s north block (design §5). It is visible only when `model.hasStates`, and `stateChanged()` updates its selection and calls `reveal()`. Clicks go through `goTo` plus `stateChanged()`. Verify with new cases in `AlloyInstanceViewerTest`/`AlloyStateStepperTest`:
  - a trace shows the strip and a static result does not;
  - clicking state 2 of the lasso selects state 2, with the captions "State 2" and "State 1 (loop)";
  - with a projection over a signature, a click keeps the projection and the chosen atom (`AlloyProjectionTest`).
- [x] 3.2 Add a "Trace path strip" section to the "instance viewer" manual checklist in `DEV.md`:
  - circles, arrows and the loop curve of a lasso;
  - the highlighted pair following the selection;
  - clicking a circle;
  - tooltips;
  - horizontal scrolling in a narrow tool window;
  - light and dark themes, and switching between them.
  
  Verify by re-reading the checklist against the delta spec's scenarios.

## 4. Integration

- [x] 4.1 Run the full test suite (`./gradlew test`), including the Practical Alloy example tests, and verify that it passes. Check that `openspec validate add-trace-loop-indicator --strict` passes.

## 5. Review fixes

- [x] 5.1 Draw every straight arrow: cull an arrow by the bounds of its line and head together, since a horizontal line's bounds have no height (design §3). Verify in `AlloyTraceStripTest` by painting with a clip, as Swing does: every gap between consecutive circles of a 5-state trace has a dark arrow pixel, and the loop curve is drawn.
- [x] 5.2 Make the strip, its scroll pane and its viewport transparent, and build the dark palette on the background around the strip, with `DarkPalette.ide(canvas)` (design §4). Verify in `AlloyTraceStripTest`: under both themes, a pixel off the circles keeps what was under the strip, and under the dark theme the dimmed fill is the surrounding background; a theme change still takes a new palette.
- [x] 5.3 Center each number on the visual bounds of its glyphs (design §1). Verify in `AlloyTraceStripTest`: the dark pixels of the numbers of states 0 and 1 are vertically centered on their circles, within 1 px.
- [x] 5.4 Remove the stepper: the previous and next actions, the "State i of n" and loop labels, and the model's `stepperText` and `loopText` (design §6). Verify in `AlloyStateStepperTest`: the panel has no such label, the toolbar's actions are the four New solution actions, and clicking circles 1, then 0 moves the highlighting and the captions.
- [x] 5.5 Put the strip on the toolbar's line, after the New solution actions and the projection chooser, when it fits, and below them otherwise, with `ToolbarLineLayout` (design §5). Verify in `AlloyStateStepperTest`: in a 2000 px line the strip is to the right of the controls, on the same line, at its preferred width; in a line narrower than both, it is below the controls, as wide as the line.
- [x] 5.6 Update the "Trace path strip" and "Instance viewer" checklists in `DEV.md`: no stepper, placement beside or below the toolbar, the same background as the toolbar, every arrow, centered numbers.
- [x] 5.7 Run the full test suite (`./gradlew test`) and `openspec validate add-trace-loop-indicator --strict`, and verify that both pass.
