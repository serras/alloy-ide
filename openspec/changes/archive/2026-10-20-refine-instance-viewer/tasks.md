# Tasks

## 1. Dark color conversion

- [x] 1.1 Add the pure `DarkColors` converter in `viewer/` (design §2): `fill(Color)` with the muted dark band and `edge(Color)` with the "primary" rule, both in HSL and keeping the hue. Verify with a new `DarkColorsTest` over every `DotColor` of every `DotPalette`: the hue is preserved; fill lightness is ≤ 0.35; fills are ordered by their original lightness; a fill contrasts with a light label by at least 4.5:1; an edge contrasts with a dark canvas by at least 3:1.
- [x] 1.2 Add the color-mapping `Graphics2D` delegate (design §1). It maps white to the canvas, black to the foreground or label, `LIGHT_GRAY` to the selection surface, other colors to `fill` or `edge` depending on the drawing call, and `create()` returns another delegate. Verify with a test that paints a `GraphViewer` from `VizState.getGraph` into a `BufferedImage` through the delegate: no pure-white pixels remain and label text pixels are light. Painting without the mapping reproduces Alloy's colors.
- [x] 1.3 Wrap the graph in a `JLayer` that uses the delegate only while `!JBColor.isBright()`, and repaint on `LafManagerListener.TOPIC` with the panel's disposable (design §1, §3). Verify with a test that toggles the mapping and checks that the painted canvas color switches between white and the panel background. Add the dark-theme and theme-switch checks to the README "instance viewer" manual checklist.

## 2. Two states in the model

- [x] 2.1 In `AlloySolutionModel`, add `successor` (the next state, or the loop target after the last state) and a second theme-sharing `VizState` for traces. `goTo` loads both states, and `setProjected`/`clearProjections` apply to both (design §5). Verify with new cases in `AlloyStateStepperTest`: for the 3-state lasso looping to 1, the successors of 0, 1 and 2 are 1, 2 and 1; previous and next move one state at a time; both `VizState`s keep one theme. Also verify in `AlloyProjectionTest` that projecting and clearing affect both states. Existing tests still pass.
- [x] 2.2 Add captions to the model ("State i", or "State k (loop)" when the successor is the loop target), keeping `stepperText` as "State i of n". Verify with `AlloyStateStepperTest` assertions on the captions at states 0 and 2 of the lasso.

## 3. Visualization and Table panes

- [x] 3.1 Add the `AlloyStatePanes` container: one or two captioned panes, stacked in a column when `height > width` and in a row otherwise, rearranged in `doLayout` (design §4). Verify with a unit test that lays it out at 800×400 and 400×800 and checks the panes' bounds (side by side, then stacked) and the captions.
- [x] 3.2 Replace `VizGraphPanel` in `AlloyInstancePanel` with `AlloyStatePanes` of `JLayer`-wrapped `vizState.getGraph(frame, projection)` graphs, plus one atom selector per projected signature that builds the single `AlloyProjection` shared by both panes. Selectors default to the first atom and keep a choice that still exists. Verify that `AlloyProjectionTest.testPanelShowsTheChooser`, `AlloyMagicLayoutTest` and `AlloyInstanceViewerTest` pass, adapted to the new panel. Add a test that a trace shows two graphs and a static result shows one, and that picking an atom applies to both graphs.
- [x] 3.3 Show the Table tab in `AlloyStatePanes` too: one `format(state)` text area per state for traces, and one for static results. Verify with `AlloyInstanceTabsTest` cases: for the lasso at state 2, the panes hold the tables of states 2 and 1; a static result still shows the analyzer's single table.
- [x] 3.4 Update the README "instance viewer" manual checklist for the visualization and table panes: two captioned panes, arrangement following resizes, zoom popup in both panes, and one shared atom selector. Remove the item about the atom selector "at the bottom of the graph" if it changes. Verify by re-reading the checklist against the delta spec scenarios.

## 4. Text tab diff

- [x] 4.1 Add the pure `TraceDiff` (design §6): for states *a* and *b*, one line per signature (in `getAllReachableSigs()` order) followed by its fields, then one per skolem (`getAllSkolems()`). Each line has kept, added and removed tuples from `A4Solution.eval`. Verify with a `TraceDiffTest` on the lasso and on a model with a mutable field: the labels and order match `toString(state)`'s lines; added and removed tuples are exact; unchanged relations have no marks; the diff from the last state goes to the loop target.
- [x] 4.2 Render the diff in the Text tab as a read-only styled `JTextPane`: added tuples use the scheme's `DiffColors.DIFF_INSERTED` attributes, removed tuples use `DIFF_DELETED` struck through, and unchanged lines are dimmed. Static results keep the plain `toString(state)` text. Verify with `AlloyInstanceTabsTest`: the document text of a trace contains each relation line; the styled ranges of added and removed tuples carry those attributes; a static result shows the analyzer's text unchanged (the existing `testTextAndTableAreTheAnalyzersOutput` still passes).
- [x] 4.3 Add the Text tab diff checks to the README "instance viewer" manual checklist (colors in light and dark themes, dimmed unchanged lines). Verify by re-reading the checklist against the delta spec scenarios.

## 5. Integration

- [x] 5.1 Run the full test suite (`./gradlew test`), including the Practical Alloy example tests. Verify that it passes. Check that `openspec validate refine-instance-viewer --strict` passes.
