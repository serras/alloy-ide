# Design

## Context

`AlloyInstancePanel` stacks three lines in its `north` box: the header link, the toolbar line and the status line. Under them is a `JBTabbedPane` with the Visualization, Text and Table views, which is the first component of the Evaluator splitter.

The toolbar line uses `ToolbarLineLayout` with two children:
- `controls`: the `ActionToolbar` of New solution actions and the Evaluator toggle, then the "Projection:" label and link;
- `strip.scrollPane`.

The strip goes beside `controls` when it fits entirely, and otherwise below them at full width. When a resize moves it, the layout revalidates its parent.

The tab change listener writes `viewer.selectedTab`, and `rebuild()` restores it by index from `TAB_IDS`.

`AlloyStatePanes` holds the panes of the Visualization and Table tabs: one for a single state, two for a trace state and its successor. Its `isColumn` is `height > width`, so the panes are stacked in a tall tab and side by side otherwise, and they are rearranged at every resize.

`SolutionKind` gives each New button its label, description and icon: `AllIcons.Actions.Restart`, `TraceOver`, `PopFrame` and `RunToCursor`, by analogy with the debugger. `NewSolutionAction.update` shows the four buttons whenever there is a session, and `AlloyInstanceViewer.isEnabled` disables New Trace, New Init and New Fork for a model that is not temporal.

`TextEditorWithPreview` builds its Editor / Editor and Preview / Preview buttons from the registered toggle actions `TextEditorWithPreview.Layout.EditorOnly`, `EditorAndPreview` and `PreviewOnly`, placed in an `ActionManager.createActionToolbar(...)` toolbar. Their pressed look is simply how an `ActionToolbar` draws a selected `ToggleAction`.

## Goals / Non-Goals

**Goals:**
- A tab switcher that looks and behaves like the editor's layout buttons, built from the same platform parts.
- A toolbar-line layout whose placement rule is pure and tested at every width, as `TraceStripLayout` is for the strip.
- A pane layout that the user chooses with the tab buttons, and that a resize does not change.
- New button icons that say what each one asks for, and only the New buttons that can apply to a result.

**Non-Goals:**
- Moving the header link or the status line onto the toolbar line.
- Keyboard shortcuts for switching tabs.
- Changing the strip itself: its painting, scrolling and `reveal()`.
- A layout per tab, or one for the Text tab, whose diff is a single text.

## Decisions

### 1. The tab switcher is an `ActionToolbar` of three `ToggleAction`s

`TabAction(id, icon)` subclasses `ToggleAction` and is `DumbAware`, with `ActionUpdateThread.EDT`:
- its text is the existing `viewer.tab.*` message, so the toolbar shows it as the tooltip;
- `isSelected` is `selectedTab == id`;
- `setSelected(e, true)` selects the tab;
- `setSelected(e, false)` does nothing, so pressing the selected tab keeps it.

The three actions go in their own toolbar, `ActionManager.createActionToolbar("AlloyInstanceTabs", group, true)`, with `targetComponent = this`.

The toolbar is separate from the New solution toolbar so that it can sit at the right end.

- **Alternative: a Kotlin UI DSL segmented button.** It has a different look, and the request asks for the editor's layout buttons.
- **Alternative: icon-only titles on the `JBTabbedPane`.** That still leaves a row of tabs under the toolbar line, which is the space this change frees.

Icons, from `AllIcons`:
- Visualization: a diagram or graph icon;
- Text: `FileTypes.Text`;
- Table: a table or grid icon.

The exact constants are checked against the 2026.2 platform during implementation, where the build fails if one does not exist.

### 2. The views are a `CardLayout` keyed by tab id

`views = JPanel(CardLayout())` holds the visualization panel, the Text scroll pane and the tables, keyed by `AlloyInstanceViewer.VISUALIZATION`, `TEXT` and `TABLE`.

`selectTab(id)` does four things:
1. shows the card;
2. stores `viewer.selectedTab`;
3. records the id in a `selectedTab` property, which tests read;
4. calls `tabsToolbar.updateActionsAsync()`.

`rebuild()` calls `selectTab(viewer.selectedTab)`, falling back to Visualization for an unknown id. `views` replaces `tabs` as the splitter's first component.

`TAB_IDS` stays as the order of the buttons.

### 3. `ToolbarLineLayout` gets three regions and a pure placement function

The layout takes `left` (today's `controls`), `strip` and `right` (the tab toolbar's component).

Placement is decided by a pure function, so that it can be tested without Swing:

```
placement(width, left, strip, right, gap): Placement
  centered = (width - strip) / 2
  if strip hidden                                   -> NONE
  if centered >= left + gap
     and centered + strip <= width - right - gap    -> CENTERED(x = centered)
  if left + gap + strip + gap <= width - right      -> BETWEEN(x = left + gap + (free - strip) / 2)
  otherwise                                         -> BELOW
```

`free` is the space between the groups: `width - right - left - 2 * gap`.

Before the first layout the width is 0, and the placement is `CENTERED` (treated as fitting), as today.

How each part is laid out:
- `left` is always at the left inset and `right` at the right edge, both vertically centered on the first line.
- `BELOW` puts the strip on a second line at full width, with room for its scroll bar as `heightBelow` computes today.
- The preferred width is `left + gap + strip + gap + right` when the strip is shown, so that a wide parent can fit everything. The minimum width stays 0.
- The revalidate-on-move logic now tracks the `BELOW` case rather than the old `beside` flag.

When the preview is narrower than `left + gap + right`, `right` keeps its place at the right edge and is painted over the end of `left`. That is what a `BorderLayout` with `right` in `EAST` would do too. Wrapping the tab buttons onto a line of their own was ruled out, because the tab buttons always stay on the first line. The case only occurs in a preview narrower than both button groups together.

### 4. The pane layout is chosen with the tab buttons

The layout is an enum `PaneLayout { ROW, COLUMN }` (side by side and stacked):
- `AlloyInstanceViewerState.paneLayout` keeps it, starting at `ROW`, so it is shared by every viewer and kept after a restart, as `selectedTab` is;
- `AlloyInstanceViewer.paneLayout` reads it, falling back to `ROW` for an unknown value.

`AlloyStatePanes` gets a `layout: PaneLayout` property, which its setter lays out again. `isColumn` becomes `layout == COLUMN`, and the shape no longer matters. The panel sets it on `graphs` and `tables` when it is built and whenever it changes.

`TabAction` changes as follows:
- `setSelected(e, true)` selects the tab, as before; the panes keep the current layout.
- `setSelected(e, false)` is what an `ActionToolbar` calls when the selected toggle is pressed. For Visualization and Table it calls `togglePaneLayout()`, which flips the stored layout, applies it to both panes and updates the tab toolbar. For Text it still does nothing.
- `update(e)` sets `e.presentation.icon` to the icon of the current layout, for Visualization and Table.

The toggle works for a static result too, which has a single pane: the layout flips, the icon follows, and the next trace uses it. That keeps the button's behavior the same for every result, and the icon is then the only visible change.

Icons: `AllIcons` has layout icons such as `Actions.SplitVertically` and `SplitHorizontally`, but none that also names the tab. Four plugin SVGs in `icons/` combine both, in the platform's 16×16 style with `_dark` variants:
- `visualizationRow.svg`, `visualizationColumn.svg`: two small graphs side by side, or one above the other;
- `tableRow.svg`, `tableColumn.svg`: two small grids side by side, or one above the other.

`AlloyIcons` loads them. The Text button keeps `AllIcons.FileTypes.Text`.

- **Alternative: a separate layout button.** It would add a fourth button to the line, while the request is to use the tab buttons.
- **Alternative: one layout per tab.** "The same layout as previously" means the panes keep their arrangement when switching from Visualization to Table, which one shared layout gives directly.

The tooltips stay the tab names.

### 5. New solution icons, and the trace buttons only with a trace

**Icons.** Four plugin SVGs in `icons/`, 16×16 with `_dark` variants, which `SolutionKind` takes from `AlloyIcons`. They use the platform's icon palette: the outline grey (`#6C707E`, dark `#CED0D6`) and the blue accent (`#3574F0`, dark `#548AF7`) for what is new.
- `newConfig.svg`: three nodes joined in a triangle, the immutable sets and relations, with a small circular refresh arrow in the blue accent at the bottom right.
- `newTrace.svg`: the trace path strip's motif, three circles in a row joined by arrows with a loop curve above, all in the blue accent: a whole new execution.
- `newInit.svg`: the same three circles, with only the first one filled in the blue accent: a new initial state.
- `newFork.svg`: a grey circle on the left joined to one in the center, which branches into two circles on the right, up and down, in the blue accent: the same behavior up to the current state, then new successors.

The comment on `SolutionKind` that explains the debugger icons is replaced by one on these.

- **Alternative: keep the debugger icons.** They are familiar, but "step over" and "drop frame" say nothing about traces, and the request asks for icons in the style of the strip.

**Visibility.** `NewSolutionAction.update` hides New Trace, New Init and New Fork when the session's model has no states (`!model.hasStates`), the same condition that hides the trace path strip. New Config stays visible for every result.

`isEnabled` keeps its own rule (`isTemporal`) for the enabled state. A temporal model whose trace has a single state therefore hides the three buttons, though the analyzer could answer them. That follows the request, which ties the buttons to the strip, and such a trace is rare.

### 6. Tests

- **`ToolbarLinePlacementTest`** covers the pure function:
  - `CENTERED` when everything fits around the center;
  - `BETWEEN` when the left group is wide and the center would overlap it;
  - `BELOW` when the strip is wider than the free space;
  - `NONE` without a strip;
  - each threshold at the exact pixel.
- **`AlloyInstanceTabsTest`** reaches the switcher through the panel's `selectedTab`/`selectTab` and the tab toolbar's actions, instead of `JBTabbedPane`.
- **Tab-button cases** check the tooltips and that pressing the selected button keeps the tab.
- **Laid-out panel case:** a test lays the panel out at a wide and a narrow width with a trace, and checks the bounds:
  - wide: the strip is on the first line, centered;
  - narrow: the strip is below, and the tab toolbar's right edge is at the line's right edge.
- **`AlloyStatePanesTest`** lays the panes out in each layout, at both a wide and a tall size, and checks that only the layout decides.
- **Pane layout cases** in `AlloyInstanceTabsTest`:
  - switching from a stacked Visualization to Table keeps the panes stacked;
  - pressing the selected Visualization button flips the layout, and its icon;
  - the layout is kept for the next result and in a new panel;
  - pressing the selected Text button changes nothing.
- **New action cases** in `AlloyTraceNavigationTest`:
  - a static result shows only New Config, enabled;
  - a trace shows all four, enabled;
  - each kind's icon is its `AlloyIcons` icon.

## Risks / Trade-offs

- **[Trade-off] Pressing the selected button does two things.** The Visualization and Table buttons select their tab or switch the layout, depending on whether they are selected. This is less discoverable than a separate button.
  → The icon shows the layout, so it changes visibly when pressed. The `DEV.md` checklist covers it.
- **[Risk] Tall previews start side by side.** A preview stacked below the text, which used to get stacked panes, now starts side by side until the user switches.
  → The choice is remembered, so it is made once.

- **[Risk] Placement uses stale widths.** `ActionToolbar` fills itself asynchronously, so the right group's preferred width is 0 at the first layout, and the strip may be placed against a wrong `right`.
  → The layout already revalidates when a placement changes, and the toolbar revalidates its parent when its actions arrive. The panel test waits for the toolbars to update (as the existing tests do with `waitUntil`) before checking bounds.
- **[Risk] Weaker keyboard access.** Toolbar buttons are not focus-traversable by default, while `JBTabbedPane` tabs were.
  → This is accepted, as for the editor's own layout buttons. The tooltips name the tabs.
- **[Trade-off] Less room for the strip.** It now has the space between the groups, rather than everything after `controls`, so it moves below at a slightly wider preview than today.
- **[Risk] Conflict with `add-event-timeline`.** That change adds an Events dropdown to `controls`.
  → It goes in the left group without any change to this layout. The left group gets wider, which only moves the thresholds. Whichever change lands second resolves the conflict in `AlloyInstancePanel`.
