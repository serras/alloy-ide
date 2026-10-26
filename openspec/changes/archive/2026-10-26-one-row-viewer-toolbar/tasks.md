# Tasks

## 1. Tab buttons and views

- [x] 1.1 Replace the `JBTabbedPane` in `AlloyInstancePanel` with a `CardLayout` panel of the three views, keyed by `AlloyInstanceViewer.VISUALIZATION`, `TEXT` and `TABLE` (design §2).
  - Add `selectTab(id)` and a read-only `selectedTab`.
  - `rebuild()` restores `viewer.selectedTab`, falling back to Visualization.
  - The views panel becomes the Evaluator splitter's first component.

  Verify by updating `AlloyInstanceTabsTest` to use `selectTab`/`selectedTab` instead of `JBTabbedPane`, and by running it. The default-tab and remembered-tab cases must still pass, including the restart case.
- [x] 1.2 Add the tab `ActionToolbar` ("AlloyInstanceTabs") of three `TabAction` `ToggleAction`s, with icons from `AllIcons` and the `viewer.tab.*` names as text (design §1).
  - `setSelected(false)` does nothing.
  - `selectTab` updates the toolbar.

  Verify with new cases in `AlloyInstanceTabsTest`:
  - the actions' order is Visualization, Text, Table, and their texts are the tab names;
  - performing the Text action shows the Text view, after which only the Text action is selected;
  - performing the selected Table action again keeps Table selected;
  - each action's icon is not null.

## 2. Toolbar line layout

- [x] 2.1 Extract the pure placement function `NONE`/`CENTERED`/`BETWEEN`/`BELOW` (design §3).

  Verify with a new `ToolbarLinePlacementTest`:
  - centered when everything fits around the center;
  - between the groups when a wide left group would be overlapped;
  - below when the strip is wider than the free space;
  - `NONE` without a strip;
  - each threshold, one pixel either side.
- [x] 2.2 Rewrite `ToolbarLineLayout` with three regions (left controls, strip, tab toolbar), using the placement function. Keep the scroll-bar height of the line below, and the revalidation when the placement changes. Put the tab toolbar into the toolbar line in `AlloyInstancePanel`.

  Verify with a new case that lays out a panel showing the lasso, after its toolbars have updated:
  - at a wide width, the strip is on the first line and centered, and the tab toolbar's right edge is at the line's right edge;
  - at a narrow width, the strip is below at full width, and the tab toolbar stays on the first line at the right;
  - for a static result, there is no strip, and the tab toolbar is at the right.
- [x] 2.3 Update the "instance viewer" and "Trace path strip" sections of `DEV.md`'s manual checklist. Replace the tab-title and "after the Projection link" wording with:
  - the three icon buttons, their tooltips and the pressed look matching the editor's layout buttons;
  - the strip centered on the line, then centered between the groups, then below while narrowing the divider, and back when widening.

  Verify by re-reading the checklist against the delta spec's scenarios.

## 3. Chosen pane layout

- [x] 3.1 Add `PaneLayout { ROW, COLUMN }`, kept in `AlloyInstanceViewerState.paneLayout` (starting at `ROW`) and read through `AlloyInstanceViewer.paneLayout`, falling back to `ROW` (design §4).

  Verify with a case in `AlloyInstanceTabsTest` that the layout survives serializing the state, as `testTheChosenTabIsSaved` does for the tab.
- [x] 3.2 Give `AlloyStatePanes` a `layout` property, which lays its panes out again when set, and make `isColumn` follow it instead of the shape. The panel sets it on the graphs and the tables when built and whenever it changes.

  Verify by rewriting `AlloyStatePanesTest`: in each layout, at both a wide and a tall size, the panes are side by side for `ROW` and stacked for `COLUMN`.
- [x] 3.3 Add the four SVG icons (`visualizationRow`, `visualizationColumn`, `tableRow`, `tableColumn`, each with a `_dark` variant) and load them in `AlloyIcons`.
- [x] 3.4 Make `TabAction` switch the layout (design §4):
  - `setSelected(false)` toggles the layout for Visualization and Table, and does nothing for Text;
  - `update` shows the icon of the current layout for Visualization and Table.

  Verify with new cases in `AlloyInstanceTabsTest`, on the lasso:
  - with a stacked Visualization, performing the Table action shows Table with its panes stacked;
  - performing the selected Table action again keeps Table selected, puts the panes side by side, and changes both icons;
  - the layout stays for the next result and in a new panel;
  - performing the selected Text action changes neither the layout nor the icons.

  Update the existing "selected Table action again keeps Table selected" case for the toggle.
- [x] 3.5 Update the `DEV.md` instance viewer checklist: replace the panes "side by side when the preview is wider than tall, and stacked when it is taller than wide" wording with the layout switch, its icons, its being kept for later results and after a restart, and resizing not changing it.

  Verify by re-reading the checklist against the delta spec's scenarios.

## 4. New solution buttons

- [x] 4.1 Draw the four New icons (`newConfig`, `newTrace`, `newInit`, `newFork`, each with a `_dark` variant) as design §5 describes, load them in `AlloyIcons`, and use them in `SolutionKind` instead of the debugger's icons.

  Verify by rendering each icon and its dark variant, on a light and a dark background, and checking that they are distinct from one another and readable at 16×16. Add a case in `AlloyTraceNavigationTest` that each kind's icon is its `AlloyIcons` icon.
- [x] 4.2 Hide New Trace, New Init and New Fork in `NewSolutionAction.update` when the session's model has no states (design §5).

  Verify by updating the New action cases in `AlloyTraceNavigationTest`:
  - a static result shows only New Config, enabled;
  - a trace shows all four, enabled;
  - an outdated trace disables every shown button, with the "run again" tooltip.
- [x] 4.3 Update the New buttons item of `DEV.md`'s instance viewer checklist: the new icons under a light and a dark theme, and only New Config for a static result.

  Verify by re-reading the item against the delta spec's "New solutions" scenarios.

## 5. Integration

- [x] 5.1 Run the whole test suite (`./gradlew test`) and verify it passes, in particular the viewer, trace navigation, projection and evaluator tests that go through the panel.
- [x] 5.2 Run `openspec validate one-row-viewer-toolbar --strict` and verify it passes.
