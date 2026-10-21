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
    - circles of radius 12, 45 px apart, outlined with a 2 px stroke, each numbered at its center (half the text's width to the left and half the font's ascent down from the center, which leaves the digits a little low, because the ascent includes the font's leading);
    - the states being shown (`current` up to `current + statepanes - 1`, normalized into the lasso) filled white, the others filled grey `(120, 120, 120)`, with black outlines and numbers;
    - 1 px black straight arrows between consecutive circles, each with a small triangular head (points `(0,4)`, `(-4,-4)`, `(4,-4)`) rotated to the arrow's direction;
    - the loop as a cubic curve that leaves the top of the last circle and lands on the top of the loop target's circle, through control points at the top of the strip, each 25 px sideways from its end towards the other, with the same head;
    - a click inside a circle sets `current` and calls `updateDisplay()`.

## Goals / Non-Goals

**Goals:**
- The same picture as Alloy's strip: geometry, highlighting and loop curve. Readers of the book then recognize it.
- Geometry and hit testing that can be tested without painting.
- Room for a label on each transition, for `add-event-timeline`.

**Non-Goals:**
- Event labels on transitions (`add-event-timeline`).
- Changing the captions or the panes.
- Keyboard focus and navigation inside the strip. With the stepper gone (see Decision 6), this is left to a later change.
- Alloy's unrolled, sliding window (see Decision 2).

## Decisions

### 1. Replicate Alloy's painting in our own component, not embed it

The strip is a new `AlloyTraceStrip` component that draws Alloy's picture with its constants. Embedding Alloy's strip is not possible:
- `traceGraph()` is private, and its panel is an anonymous inner class of `VizGUI`.
- That panel reads `VizGUI`'s private `current` and `statepanes` and its `getVizState()`.
- Getting one would mean constructing a `VizGUI`, a window-level object that also reads the desktop application's preferences. That is against the "Visualizer independent of desktop preferences" requirement, and the change `refine-instance-viewer` already moved away from Alloy's panels for the same reasons.

Reflection into an anonymous class was rejected as fragile across Alloy versions. The constants (radius 12, spacing 45, height 50, 2 px circle stroke, 1 px arrows, the loop's 25 px sideways reach, the arrow-head polygon, white and grey fills) are named in one place. They are scaled with `JBUI.scale`, and a comment cites `VizGUI.traceGraph()`.

Two details differ from Alloy on purpose. Each number is centered on the visual bounds of its glyphs, so that it sits in the middle of the circle, not a little below it. And the strip has no background of its own (Decision 4).

### 2. Draw the lasso once and scroll, instead of Alloy's unrolled window

Alloy fills the panel's width with an unrolled window centred on `current`. It repeats states past the loop, numbered modulo the lasso. Our strip draws each state exactly once (`0 until traceLength`), with one loop curve. This is Alloy's picture when the trace fits, and it makes the shape of the lasso readable at a glance, which is the point of this change.

When the circles do not fit, the strip scrolls in a horizontal `JBScrollPane` without a vertical bar. The scroll pane and its viewport are transparent, like the strip. After each selection change it calls `scrollRectToVisible` on the bounds covering the two highlighted circles. If the pair is the last state and the loop target, those bounds also cover everything between them; when they do not fit, the selected state is preferred.

Alternative rejected: reproducing the sliding window. It hides the prefix and loop lengths and needs the modulo numbering, which readers find confusing in long traces.

### 3. A pure geometry model under the component

`TraceStripLayout(traceLength, loopState, selected, successor)` computes everything except painting:
- the circle bounds;
- the highlighted set;
- the transitions, as a list of `Transition(from, to, isLoop)` in trace order, the loop last;
- the arrow paths and the loop curve;
- `stateAt(point)` for hit testing.

The component paints the layout and maps clicks and tooltips through `stateAt`. Unit tests check the layout directly: bounds, highlighting, the curve's end points and hit testing.

When painting, the component skips what lies outside the clip. A straight arrow is tested with the bounds of its line and head together: a horizontal line's own bounds have no height, so they never intersect the clip, and such an arrow would never be drawn.

The `Transition` list is the extension point for `add-event-timeline`. A later change can attach an optional label to each transition, which the component draws at the arrow's midpoint, or above the curve for the loop. The geometry stays as it is. This change only defines the list and draws no labels.

### 4. Colors by role, light as Alloy and dark from `DarkPalette`

The strip is not painted through `DarkGraphics`. Its mapping turns white into the canvas and Alloy's grey into a muted fill, which would make the highlighted circles darker than the dimmed ones, the opposite of Alloy's cue. Instead the strip picks colors by role:

| Role | Light theme (Alloy) | Dark theme (`DarkPalette.ide(background)`) |
|---|---|---|
| Strip background | none (the toolbar's shows) | none (the toolbar's shows) |
| Highlighted fill | white | `selection` (a little lighter than the background) |
| Dimmed fill | `(120, 120, 120)` | `canvas` (the background around the strip) |
| Outlines and arrows | black | `foreground` |
| Number, highlighted circle | black | `label` |
| Number, dimmed circle | black | `UIUtil.getContextHelpForeground()` |

The strip is transparent, so that it matches the toolbar around it under every theme; a fill of its own (the panel background, or the dark canvas) showed as a band of a different color. Under a dark theme, the palette is built on the background of the nearest opaque component around the strip: `DarkPalette.ide()` takes that color as its canvas, instead of always the panel background. The dark theme is detected with `!JBColor.isBright()`, as `DarkGraphLayerUI` does. Highlighted circles are always the lighter ones, so the cue keeps its meaning. A `LafManagerListener`, tied to the panel's disposable, drops the cached palette and repaints; a palette is also taken again when the background around the strip changes.

### 5. Placement and wiring

The strip sits on the toolbar's line, after the New solution actions and the projection chooser, when it fits there entirely; otherwise it goes on a line of its own below them, as wide as the panel, where it scrolls. A small layout manager for that line, `ToolbarLineLayout`, makes the choice from the line's width. Its preferred height is computed for the current width; when a new width moves the strip from one place to the other, the line asks to be laid out again, so that the panel takes the new height. The strip is shared by every tab, because all three show the same pair.

`stateChanged()`, which runs after every change of the selected state, updates the strip's selection and visibility (`model.hasStates`) and calls `reveal()`. Clicks call a callback that does `model.goTo(state)` followed by `stateChanged()`. Navigation by clicking therefore keeps the projection, the captions and the diff for free.

`AlloySolutionModel` gains `transitions: List<Transition>`, derived from `traceLength` and `loopState`, so that the panel and a later timeline share one definition.

### 6. The strip replaces the stepper

The previous and next actions, the "State i of n" label and the "Loops back to state k" label are removed, with the "Trace state stepper" requirement. The strip shows all of that at once: the selected pair, the length of the trace and the loop. It reaches any state in one click instead of several steps. The toolbar keeps only the New solution actions. The model keeps `next()` and `previous()`, which tests and later changes use, but drops the texts of the removed labels.

What is lost is stepping from the keyboard, through the toolbar's actions. The pane captions still name both shown states and mark the loop target, and the strip's tooltips say which state the last one loops back to.

## Risks / Trade-offs

- [The strip takes 50 px of vertical space in a small tool window] → It is shown only for traces, and on the toolbar's line when it fits there, so that it often takes no extra line at all.
- [Without the stepper, a trace cannot be stepped through from the keyboard, and screen readers lose the "State i of n" label] → The pane captions name both shown states. Keyboard navigation in the strip is a possible follow-up.
- [The strip on its own line may be covered at the bottom by a horizontal scroll bar] → The line adds the bar's height when the bar takes space (an opaque bar) and the strip is wider than the line. An overlay bar covers only the strip's bottom margin, below the circles.
- [The loop curve over many circles in a long trace reaches across the scrolled strip] → The curve's control points stay at the top of the strip, as in Alloy, so it stays within the strip's height. Only its horizontal extent grows, and that is scrolled.
- [Alloy's constants are copied, not shared, and could drift in a future Alloy version] → They are named in one place, with a comment pointing to `VizGUI.traceGraph()`. Any drift is cosmetic.
- [Very long traces (hundreds of states) make a wide strip] → Painting is proportional to the visible clip. The layout is computed once per selection change.
