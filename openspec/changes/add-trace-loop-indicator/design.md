# Design

## Context

- **Navigation today.** The Alloy Instance tool window steps through a trace with previous and next actions, a "State i of n" label and a "Loops back to state k" label, all in the controls row of `AlloyInstancePanel`.
  - `AlloySolutionModel` knows `traceLength`, `loopState` and `successor(state)`.
  - Every tab shows the selected state with its successor (`AlloyStatePanes`).
  - Under a dark theme, graphs are painted through `DarkGraphLayerUI`/`DarkGraphics` with a `DarkPalette` taken from the IDE theme.
- **Alloy's strip.** In Alloy 6.2.0 (`org.alloytools.alloy.application-6.2.0.jar`), the visualizer's trace strip is built by the private method `VizGUI.traceGraph()`.
  - That method returns the anonymous `JPanel` `VizGUI$7` (`paintComponent`, `getPreferredSize`), with the mouse listener `VizGUI$8`.
  - The bytecode shows the visual language this change copies:
    - a panel 50 px high;
    - circles of radius 12, 45 px apart, outlined with a 2 px stroke, each numbered at its center;
    - the states being shown (`current` up to `current + statepanes - 1`, normalized into the lasso) filled white, the others filled grey `(120, 120, 120)`, with black outlines and numbers;
    - 1 px black straight arrows between consecutive circles, each with a small triangular head (points `(0,4)`, `(-4,-4)`, `(4,-4)`) rotated to the arrow's direction;
    - the loop as a cubic curve that leaves the top of the last circle, rises through control points 25 px above it, and lands on the top of the loop target's circle, with the same head;
    - a click inside a circle sets `current` and calls `updateDisplay()`.

## Goals / Non-Goals

**Goals:**
- The same picture as Alloy's strip: geometry, highlighting and loop curve. Readers of the book then recognize it.
- Geometry and hit testing that can be tested without painting.
- Room for a label on each transition, for `add-event-timeline`.

**Non-Goals:**
- Event labels on transitions (`add-event-timeline`).
- Changing the stepper, the loop label, the captions or the panes.
- Keyboard focus and navigation inside the strip. The previous and next actions already cover stepping.
- Alloy's unrolled, sliding window (see Decision 2).

## Decisions

### 1. Replicate Alloy's painting in our own component, not embed it

The strip is a new `AlloyTraceStrip` component that draws Alloy's picture with its constants. Embedding Alloy's strip is not possible:
- `traceGraph()` is private, and its panel is an anonymous inner class of `VizGUI`.
- That panel reads `VizGUI`'s private `current` and `statepanes` and its `getVizState()`.
- Getting one would mean constructing a `VizGUI`, a window-level object that also reads the desktop application's preferences. That is against the "Visualizer independent of desktop preferences" requirement, and the change `refine-instance-viewer` already moved away from Alloy's panels for the same reasons.

Reflection into an anonymous class was rejected as fragile across Alloy versions. The constants (radius 12, spacing 45, height 50, 2 px circle stroke, 1 px arrows, the 25 px loop rise, the arrow-head polygon, white and grey fills) are named in one place. They are scaled with `JBUI.scale`, and a comment cites `VizGUI.traceGraph()`.

### 2. Draw the lasso once and scroll, instead of Alloy's unrolled window

Alloy fills the panel's width with an unrolled window centred on `current`. It repeats states past the loop, numbered modulo the lasso. Our strip draws each state exactly once (`0 until traceLength`), with one loop curve. This is Alloy's picture when the trace fits, and it makes the shape of the lasso readable at a glance, which is the point of this change.

When the circles do not fit, the strip is placed in a horizontal `JBScrollPane` without a vertical bar. After each selection change it calls `scrollRectToVisible` on the bounds covering the two highlighted circles. If the pair is the last state and the loop target, those bounds also cover everything between them; when they do not fit, the selected state is preferred.

Alternative rejected: reproducing the sliding window. It hides the prefix and loop lengths and needs the modulo numbering, which readers find confusing in long traces.

### 3. A pure geometry model under the component

`TraceStripLayout(traceLength, loopState, selected, successor)` computes everything except painting:
- the circle bounds;
- the highlighted set;
- the transitions, as a list of `Transition(from, to, isLoop)` in trace order, the loop last;
- the arrow paths and the loop curve;
- `stateAt(point)` for hit testing.

The component paints the layout and maps clicks and tooltips through `stateAt`. Unit tests check the layout directly: bounds, highlighting, the curve's end points and hit testing.

The `Transition` list is the extension point for `add-event-timeline`. A later change can attach an optional label to each transition, which the component draws at the arrow's midpoint, or above the curve for the loop. The geometry stays as it is. This change only defines the list and draws no labels.

### 4. Colors by role, light as Alloy and dark from `DarkPalette`

The strip is not painted through `DarkGraphics`. Its mapping turns white into the canvas and Alloy's grey into a muted fill, which would make the highlighted circles darker than the dimmed ones, the opposite of Alloy's cue. Instead the strip picks colors by role:

| Role | Light theme (Alloy) | Dark theme (`DarkPalette.ide()`) |
|---|---|---|
| Strip background | panel background | `canvas` |
| Highlighted fill | white | `selection` (a little lighter than the canvas) |
| Dimmed fill | `(120, 120, 120)` | `canvas` |
| Outlines and arrows | black | `foreground` |
| Number, highlighted circle | black | `label` |
| Number, dimmed circle | black | `UIUtil.getContextHelpForeground()` |

The dark theme is detected with `!JBColor.isBright()`, as `DarkGraphLayerUI` does. Highlighted circles are always the lighter ones, so the cue keeps its meaning. A `LafManagerListener`, tied to the panel's disposable, drops the cached palette and repaints.

### 5. Placement and wiring

The strip sits in the panel's north block, under the controls row and above the status line. It is shared by every tab, because all three show the same pair. `showState()`, which already runs after every stepper change, updates the strip's selection and visibility (`model.hasStates`). Clicks call a callback that does `model.goTo(state)` followed by `showState()`, the same path the step actions take. Navigation by clicking therefore keeps the projection, the captions and the diff for free.

`AlloySolutionModel` gains `transitions: List<Transition>`, derived from `traceLength` and `loopState`, so that the panel and a later timeline share one definition.

## Risks / Trade-offs

- [The strip takes 50 px of vertical space in a small tool window] → It is shown only for traces. In return, the loop label could later be removed, but it is kept for now (screen readers, existing tests).
- [The loop curve over many circles in a long trace reaches across the scrolled strip] → The curve keeps Alloy's fixed 25 px rise, so it stays within the strip's height. Only its horizontal extent grows, and that is scrolled.
- [Alloy's constants are copied, not shared, and could drift in a future Alloy version] → They are named in one place, with a comment pointing to `VizGUI.traceGraph()`. Any drift is cosmetic.
- [Very long traces (hundreds of states) make a wide strip] → Painting is proportional to the visible clip. The layout is computed once per selection change.
