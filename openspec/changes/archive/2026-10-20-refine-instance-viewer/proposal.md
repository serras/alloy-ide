# Proposal

## Why

The Alloy Instance tool window always paints the graph with the Alloy visualizer's light colors. It shows a white canvas, black outlines and labels, and bright magic-layout fills, even under a dark IDE theme. Traces are also hard to follow, because every tab shows a single state. To see what a step changed, the user has to step back and forth and compare from memory.

## What Changes

- **Dark mode.** Under a dark IDE theme, the Visualization tab paints the graph with dark versions of its colors, and it repaints when the theme changes. The conversion follows the role-based dark-mode rules of the PaletaColor Pro converter: the hue is kept, while lightness and saturation are adjusted per role.
  - The canvas uses the IDE's background, and black lines use the IDE's foreground.
  - Node fills become muted dark colors, and node labels are always light.
  - Edges and their labels are lightened, so that they stand out on the dark canvas.
  - This applies to magic-layout colors and to any other color in the theme. Light themes keep today's colors.
- **Two trace states.** For a trace, the Visualization tab shows the current state and the next one. After the last state, the next one is the loop target.
  - The two panes are stacked vertically when the tab is taller than it is wide, and placed side by side otherwise. The arrangement follows resizes.
  - Each pane is captioned with its state, for example "State 2" or "State 1 (loop)".
  - Both panes share the theme, the projection and the atoms chosen for the projected signatures.
- **Table tab.** For a trace, the Table tab shows the tables of both states, with the same arrangement rule.
- **Text tab.** For a trace, the Text tab shows a tuple-level diff from the current state to the next one.
  - There is one line per signature, field and skolem, in the analyzer's order.
  - Each tuple is marked as kept, added or removed, using the editor color scheme's diff colors.
  - Relations that do not change are dimmed.
- **Stepping.** Previous and next still move one state at a time, so every transition is shown once as a pair. The stepper keeps saying "State i of n", where *i* is the first pane.
- Results with a single state are unchanged: one graph, plain text and one table.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `alloy-instance-viewer`:
  - The "Visualization, Text and Table tabs" requirement changes for traces: two panes in Visualization and Table, and a diff in Text.
  - "Trace state stepper" now defines what the second pane shows and what stepping does to the pair.
  - "Projection selection" applies to both panes.
  - A new requirement covers the visualization under dark themes.

## Impact

- **Code.** The changes are in `src/main/kotlin/com/serranofp/alloy/viewer/`:
  - `AlloyInstancePanel` stops using Alloy's `VizGraphPanel`. It builds its own graph panes from `VizState.getGraph`, its own atom selectors for projected signatures, and an orientation-aware layout.
  - `AlloySolutionModel` keeps one `VizState` per pane, sharing the theme.
  - New code: a dark color converter, a paint-time color-remapping layer around the graphs, and a tuple diff for the Text tab.
- **Dependencies.** No new ones. Only public classes of `org.alloytools.alloy.application` and `org.alloytools.alloy.core` 6.2.0 are used (`VizState`, `AlloyProjection`, `GraphViewer`, `A4Solution.eval`), plus IntelliJ platform UI (`JBColor`, `LafManagerListener`, editor color scheme diff attributes).
- **Tests.**
  - `AlloyStateStepperTest`, `AlloyInstanceTabsTest` and `AlloyProjectionTest` gain cases for the pair, the diff and the shared projection.
  - The color converter and the diff get unit tests.
  - New items go into the README's "instance viewer" manual checklist, for the visual checks in `runIde`.
