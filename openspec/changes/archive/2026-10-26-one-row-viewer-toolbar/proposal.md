# Proposal

## Why

The instance preview has a toolbar line and then a separate row of tab titles (Visualization, Text, Table). Together they take two rows of vertical space, which is scarce now that the preview shares the editor with the model's text. The tab titles also look different from the editor's own layout buttons right next to them.

The two panes of a trace (the selected state and its successor) are placed side by side or stacked depending on the tab's shape, and they swap whenever the preview is resized. The user cannot choose the layout, and dragging the divider past the square shape rearranges the panes unexpectedly.

The New Config, New Trace, New Init and New Fork buttons borrow the debugger's icons (restart, step over, drop frame, run to cursor), which say little about what each asks for. For a result without a trace, New Trace, New Init and New Fork are shown disabled, although they can never apply.

## What Changes

- **One toolbar line.** When everything fits, the instance preview shows a single line:
  - on the left, the New solution buttons, the Evaluator toggle and the projection chooser;
  - in the center, the trace path strip;
  - on the right, the tab buttons.
- **Centering.** The strip is centered on the whole line when it overlaps neither side. Otherwise it is centered in the space between the left and right groups.
- **Overflow.** When the whole strip does not fit between the two groups, it moves to a line of its own below, as wide as the preview, where it scrolls. The tab buttons stay at the right end of the first line.
- **Static results.** They have no strip, so the line has just the left group and the tab buttons.
- **Tab buttons.** The row of tab titles is replaced by three icon-only buttons, one per tab. Each button's tooltip is the tab's name. The selected one is drawn pressed, like the editor's Editor / Editor and Preview / Preview layout buttons. The tab last chosen is still remembered, as today.
- **Chosen pane layout.** The two panes of a trace are no longer arranged by the tab's shape. The user chooses the layout with the tab buttons:
  - pressing the Visualization or Table button while another tab is selected shows that tab, with the panes in the current layout;
  - pressing the button of the selected Visualization or Table tab switches the panes between side by side and stacked;
  - the Visualization and Table buttons' icons show the current layout;
  - one layout is shared by both tabs, starts side by side, and is remembered for later results and after restarting the IDE, as the selected tab is;
  - resizing the preview no longer rearranges the panes.
- **New solution icons.** The four New buttons get their own icons, for light and dark themes:
  - New Config: the configuration, a small graph of the immutable sets and relations, with a refresh arrow;
  - New Trace, New Init and New Fork: the trace path strip's motif of circles joined by arrows. New Trace shows a whole lasso, New Init highlights the first state, and New Fork shows a state in the center that branches into several successors.
- **Buttons of a trace only.** For a result without a trace, which shows no trace path strip, New Trace, New Init and New Fork are hidden, leaving New Config.
- **Other lines unchanged.** The header link and the status line keep their own lines, above and below the toolbar line.

## Capabilities

### New Capabilities

### Modified Capabilities
- `alloy-instance-viewer`:
  - "Visualization, Text and Table tabs": the tabs are chosen with icon buttons on the toolbar line, rather than with titled tabs.
  - "Trace path strip": the strip no longer sits "above the tabs".
  - "Trace path strip placement": the left/center/right arrangement, the centering rule and the overflow line.
  - "Two trace states arranged by the tab's shape", renamed "Two trace states in a chosen layout": the layout is the user's choice, not the tab's shape.
  - "New solutions": the buttons' own icons, and New Trace, New Init and New Fork hidden without a trace.
  - New requirement "Tab buttons": icons, tooltips, pressed look, at the right end of the toolbar line.
  - New requirement "Pane layout switch": the Visualization and Table buttons switch the panes' layout, and their icons show it.

## Impact

- **Code.**
  - `viewer/AlloyInstancePanel.kt`: `JBTabbedPane` gives way to a `CardLayout` of the three views and an `ActionToolbar` of three toggle actions.
  - `ToolbarLineLayout` is rewritten for three regions plus the overflow line.
  - New bundle keys for the tooltips, if the tab names are not reused as they are.
  - `AlloyStatePanes` lays its panes out in a layout it is given, rather than by its shape.
  - `AlloyInstanceViewerState` keeps the chosen layout.
  - Four new SVG icons: the Visualization and Table buttons, each side by side and stacked.
  - Four more SVG icons for the New buttons, which `SolutionKind` uses instead of `AllIcons`.
  - `NewSolutionAction.update` hides the three trace kinds for a result without states.
- **Tests.** `AlloyInstanceTabsTest` reaches the tabs through `JBTabbedPane` and has to use the new switcher. The layout gets its own tests for each width. `AlloyStatePanesTest` tests the given layout instead of the shape rule. The New action tests in `AlloyTraceNavigationTest` check the hidden buttons and the icons.
- **Docs.** The `DEV.md` instance viewer checklist entries on tabs, on the strip's placement, on the panes' arrangement and on the New buttons are updated.
- **Coordination.** The in-progress change `add-event-timeline` adds an "Events" dropdown to the toolbar. It joins the left group, which makes that group wider. Whichever change lands second places the dropdown there.
