# Proposal

## Why

A trace in Alloy 6 is a lasso: a finite prefix and a loop back to an earlier state. The loop decides what a liveness counterexample means. For example, in the Practical Alloy leader election, the system stutters forever without electing anyone. Today the Alloy Instance tool window only shows the loop as the label "Loops back to state k" and as the caption "State k (loop)" of the second pane. The user cannot see the shape of the trace, how long the prefix and the loop are, or where the two shown states sit in it.

The Alloy Analyzer's own visualizer answers this with a strip above the graph. The strip shows one numbered circle per state, with arrows between consecutive states and a curved arrow from the last state back to the loop target. The states shown are highlighted, and clicking a circle goes to that state. Readers of the book know this picture, so the plugin should show the same one.

## What Changes

- **Trace path strip.** For a result with more than one state, the tool window shows a strip between the toolbar and the tabs, in the Alloy visualizer's visual language:
  - one numbered circle per state, in order, joined by arrows;
  - a curved arrow above the strip from the last state back to the loop target (a self-loop when the last state loops to itself);
  - the strip is shared by the Visualization, Text and Table tabs, because all three show the same pair of states.
- **Shown states highlighted.** The circles of the selected state and of its successor (the two panes) are filled as Alloy fills the states it shows; the other circles are dimmed. When the successor is the loop target, the highlighted pair is the last state and the loop target, so the loop arrow joins the two highlighted circles.
- **Navigation.** Clicking a circle selects that state, exactly as stepping to it with previous and next would. The stepper ("State i of n"), the loop label and the pane captions are unchanged and stay in step with the strip.
- **Tooltips.** Hovering a circle names the state, and the loop target is marked as such.
- **Long traces.** The strip scrolls horizontally, and keeps the selected state and its successor in view when the selection changes.
- **Dark theme.** Under a dark IDE theme, the strip uses the same roles as the dark graph: the IDE's background and foreground, with the highlighted circles lighter than the others. It repaints when the theme changes.
- Results with a single state show no strip.

The strip is built so that each transition, including the loop, can later carry a label. The sibling change `add-event-timeline` will use this to name the event that fired between two states. This change adds no labels.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `alloy-instance-viewer`: new requirements for the trace path strip (its contents and loop arrow, the highlighted pair, navigation by clicking, tooltips, scrolling, and its dark-theme colors). The existing stepper, caption and pane requirements are unchanged.

## Impact

- **Code.** The changes are in `src/main/kotlin/com/serranofp/alloy/viewer/`:
  - a new trace strip component, with the strip's geometry kept separate from its painting so that it can be tested;
  - `AlloyInstancePanel` places the strip between the controls and the tabs, shows it only for traces, and refreshes it when the state changes;
  - `AlloySolutionModel` exposes the transitions of the lasso (pairs of states, the last one being the loop).
- **Dependencies.** None. Alloy's own strip is painted by a private method of `VizGUI` and cannot be embedded (see design.md), so the plugin draws the same picture itself. It uses the IntelliJ platform UI (`JBColor`, `JBUI`, `LafManagerListener`) and the existing `DarkPalette`.
- **Tests.** New unit tests for the strip's geometry, highlighting, hit testing and dark colors. `AlloyStateStepperTest` and `AlloyInstanceViewerTest` gain cases for the panel. New items go into the "instance viewer" manual checklist in `DEV.md`.
