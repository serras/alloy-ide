# Spec Delta

## ADDED Requirements

### Requirement: Instance preview in the editor
Every Alloy file of the project SHALL open in an editor with an instance preview, which shows that file's result. Opening an instance or counterexample SHALL show it in the instance preview of the editor whose hint was clicked. No tool window and no separate visualizer window is opened.

#### Scenario: Opening an instance
- **WHEN** `run show` in `graph.als` found an instance and the user clicks its hint
- **THEN** the editor of `graph.als` shows the instance in its instance preview, next to the text, and no other window is opened

#### Scenario: No tool window
- **WHEN** the plugin is installed and a project with Alloy files is open
- **THEN** there is no Alloy Instance tool window

#### Scenario: Before any result
- **WHEN** the user switches the editor of a file without a result to show its preview
- **THEN** the preview shows an empty state explaining that instances appear when an instance or counterexample hint is clicked

### Requirement: Instance preview layout
An Alloy file's editor SHALL open showing the text only. Opening a result SHALL switch that editor to show the text and the instance preview side by side, unless it already shows the preview. The editor SHALL let the user show the text only, the text and the preview, or the preview only, and stack the text and the preview instead of placing them side by side.

#### Scenario: First result in a file
- **WHEN** `graph.als` is open showing its text only and the user clicks the instance hint of `run show`
- **THEN** the editor shows the text and the instance preview side by side

#### Scenario: Preview only
- **WHEN** the editor shows the preview only and the user opens another result of that file
- **THEN** the editor still shows the preview only, with the new result

#### Scenario: Stacking
- **WHEN** the user chooses to stack the text and the preview
- **THEN** the preview is below the text, and the next results of that file are shown stacked as well

### Requirement: Reopened editor without a result
The layout chosen in an Alloy file's editor SHALL be kept for that file, except that an editor that opens while its file has no result SHALL show the text only.

#### Scenario: Reopening after the result was dropped
- **WHEN** `graph.als` showed a result with its preview, and the user closes its editor and opens the file again
- **THEN** the editor shows the text only

#### Scenario: After restarting the IDE
- **WHEN** the IDE is restarted with `graph.als` open showing a result
- **THEN** the editor of `graph.als` shows the text only, and clicking the hint shows the result in the preview again

### Requirement: Result dropped with the file's last editor
A file's result SHALL be kept while at least one editor of that file is open, and SHALL be dropped when its last editor closes. The command's hint keeps its result as before, so clicking it shows that result again while the hint can still open it.

#### Scenario: Closing the last editor
- **WHEN** `graph.als` shows an instance and the user closes its only editor, opens the file again and clicks the instance hint of `run show`
- **THEN** the instance preview shows that instance again

#### Scenario: Closing one of two editors
- **WHEN** `graph.als` is open in two split editors that show its result, and the user closes one of them
- **THEN** the other editor still shows the result

### Requirement: Library modules without a preview
The Alloy modules bundled with the analyzer, such as `util/ordering`, SHALL open in a plain text editor, without an instance preview or the choice of layout.

#### Scenario: Opening a bundled module
- **WHEN** the user navigates to the declaration of `first` in `util/ordering`
- **THEN** `util/ordering.als` opens in a plain text editor, with no instance preview and no layout choice

## MODIFIED Requirements

### Requirement: Each result replaces the previous one
Each Alloy file SHALL show at most one result, of one of its own commands. Opening another result of a command SHALL replace the result shown for that command's file, and SHALL not change the result of any other file. Every editor of a file SHALL show that file's result, each with its own selected tab and projection.

#### Scenario: Opening a second result
- **WHEN** the instance preview of `graph.als` shows the instance of `run show` and the user clicks the counterexample hint of `check noCycles` in `graph.als`
- **THEN** the instance preview shows only the counterexample of `check noCycles`

#### Scenario: Results of two files
- **WHEN** `graph.als` shows the instance of `run show`, and the user clicks the instance hint of `run enqueue` in `queue.als`
- **THEN** the editor of `queue.als` shows the instance of `run enqueue`, and the editor of `graph.als` still shows the instance of `run show`

#### Scenario: Two editors of one file
- **WHEN** `graph.als` is open in two split editors and the user opens the instance of `run show` from one of them
- **THEN** both instance previews show that instance, and projecting over a signature in one does not project the other

### Requirement: Result origin header
The instance preview SHALL show a header naming the result's file, its command as written (for example `run show for 3`), its line, and whether it is an instance or a counterexample. The line SHALL be where the command is now, following edits of the file; when the command is not found in the file, the header names no line. Clicking the header SHALL move the caret of the editor next to the preview to the command.

#### Scenario: Header contents
- **WHEN** the instance preview shows the counterexample of `check noCycles for 3` from line 12 of `graph.als`
- **THEN** the header names `graph.als`, `check noCycles for 3`, line 12 and "Counterexample"

#### Scenario: Lines inserted above the command
- **WHEN** the user inserts a line above `check noCycles for 3`
- **THEN** the header names line 13

#### Scenario: Navigating to the origin
- **WHEN** the user clicks the header
- **THEN** the text next to the preview scrolls to the `check noCycles for 3` command, with the caret on it and the focus in the text

#### Scenario: Navigating from the preview only
- **WHEN** the editor shows the preview only and the user clicks the header
- **THEN** the editor shows the text, with the caret on the command

### Requirement: Outdated result in the preview
When the result shown in an instance preview becomes outdated, the header SHALL be dimmed and labelled as outdated. This happens when its file is edited or analyzer settings change. The solution shown SHALL stay visible.

#### Scenario: Editing the model
- **WHEN** the instance preview shows an instance of `run show` and the user edits the file
- **THEN** the header is shown as outdated, and the instance remains visible

### Requirement: Visualization, Text and Table tabs
The instance preview SHALL show the current solution in three tabs: "Visualization", a graph of the instance; "Text", its textual form as the Alloy analyzer prints it; and "Table", its tabular form as the Alloy analyzer formats it. All three show the same solution and trace states, and switching tabs keeps them. For a result with more than one state, each tab shows the selected state together with the state that follows it, as the trace state requirements below describe.

#### Scenario: Switching to Text
- **WHEN** the instance preview shows an instance and the user selects the Text tab
- **THEN** the tab shows every signature and field of that instance with their atoms and tuples

#### Scenario: Switching to Table
- **WHEN** the user selects the Table tab
- **THEN** the tab shows the same instance as tables, one per signature or field

#### Scenario: Default tab
- **WHEN** a result is opened for the first time in the session
- **THEN** the Visualization tab is selected; when later results are opened, the tab the user last selected stays selected

#### Scenario: Static result in every tab
- **WHEN** the shown result has a single state
- **THEN** the Visualization tab shows one graph, the Text tab shows the analyzer's text without any diff marks, and the Table tab shows one set of tables

### Requirement: New solutions
The instance preview toolbar SHALL offer New config, New trace, New init and New fork. Each asks the analyzer for another solution of the same command:
- **New config:** a different configuration of the immutable relations.
- **New trace:** a different trace with the same configuration.
- **New init:** a different initial state.
- **New fork:** a different successor of the current state.

A button SHALL be disabled when the kind of solution does not apply to the current result.

#### Scenario: Static model
- **WHEN** the shown result comes from a model without mutable signatures or fields
- **THEN** only New config is enabled

#### Scenario: Next configuration
- **WHEN** the user clicks New config
- **THEN** the instance preview shows the next solution of the same command, and the header still names that command

#### Scenario: Temporal model
- **WHEN** the shown result comes from a model with mutable relations
- **THEN** New config, New trace, New init and New fork are all enabled

#### Scenario: New fork from the current state
- **WHEN** state 2 of a trace is selected and the user clicks New fork
- **THEN** the instance preview shows a solution that agrees with the previous one up to state 2 and differs afterwards

#### Scenario: No more solutions
- **WHEN** the user clicks one of these buttons and the analyzer finds no further solution of that kind
- **THEN** the current solution stays visible, and the instance preview says that there are no more solutions of that kind

#### Scenario: Outdated result
- **WHEN** the shown result is outdated
- **THEN** all four buttons are disabled, and their tooltip says to run the command again

### Requirement: Solution enumeration runs as an analysis
Asking for a new solution SHALL run as an Alloy analysis of the project. It shows the same cancellable background progress indicator, can be stopped like any analysis, and counts toward the limit of one analysis at a time per project.

#### Scenario: Busy project
- **WHEN** a command is executing and the user clicks New config
- **THEN** no new solution is requested, and the instance preview says that an Alloy analysis is already running

#### Scenario: Stopping enumeration
- **WHEN** the user cancels the progress indicator of a New trace request
- **THEN** the request ends, and the previous solution stays visible

#### Scenario: Enumeration in another file
- **WHEN** a New trace request of `graph.als` is running and the user clicks New config in the instance preview of `queue.als`
- **THEN** no new solution of `queue.als` is requested, and its instance preview says that an Alloy analysis is already running; the new trace is shown in `graph.als` when it is found

### Requirement: Projection selection
The toolbar SHALL let the user project the visualization over any number of the signatures that the visualizer allows to be projected, by checking and unchecking each in a dropdown, and SHALL let the user remove every projection at once. When collapsed, the dropdown SHALL name the projected signatures, or say "None". For each projected signature the instance preview SHALL let the user pick the atom being shown. Projection SHALL only affect the Visualization tab. When the Visualization tab shows two trace states, the projection and the chosen atoms SHALL apply to both.

#### Scenario: Projecting over a signature
- **WHEN** the user checks `State` in the projection dropdown
- **THEN** the graph shows the instance for one `State` atom, an atom selector for `State` appears, and the collapsed dropdown says `State`

#### Scenario: Projecting over several signatures
- **WHEN** the user checks `State` and then `Time`
- **THEN** the graph is projected over both, with an atom selector for each, and the collapsed dropdown names both

#### Scenario: Removing a projection
- **WHEN** `State` and `Time` are checked and the user unchecks `Time`
- **THEN** the graph is projected over `State` only

#### Scenario: Removing every projection
- **WHEN** the user chooses to clear every projection
- **THEN** the graph shows the whole instance again, and the collapsed dropdown says "None"

#### Scenario: Default projection from magic layout
- **WHEN** the magic layout chooses a projection for a new result
- **THEN** the projection dropdown shows that signature checked

#### Scenario: New solution keeps the projection
- **WHEN** the graph is projected over `State` and the user asks for a new solution
- **THEN** the new solution is shown with the same theme and projected over `State`

#### Scenario: Projection shared by both trace states
- **WHEN** a trace is shown, the user checks a signature `Node` in the projection dropdown and picks the atom `Node1`
- **THEN** both graphs of the Visualization tab are projected over `Node` and show `Node1`, and a single atom selector for `Node` is shown

### Requirement: Visualizer independent of desktop preferences
The instance preview SHALL neither read nor write the Alloy desktop application's stored visualizer preferences, such as its last visualization mode or theme.

#### Scenario: Desktop mode not used
- **WHEN** the Alloy desktop application was last left in its Text mode
- **THEN** the instance preview still opens its first result in the Visualization tab

### Requirement: Two trace states arranged by the tab's shape
For a result with more than one state, the Visualization and Table tabs SHALL each show two panes, the selected state and its successor. Each pane is captioned with its state number, and a successor that is the loop target is marked as such. The panes SHALL be stacked vertically when the tab is taller than it is wide, and placed side by side otherwise, and SHALL be rearranged when the tab is resized.

#### Scenario: Wide tab
- **WHEN** a trace is shown at state 0 and the Visualization tab is wider than it is tall
- **THEN** the graph of state 0, captioned "State 0", is on the left and the graph of state 1, captioned "State 1", is on its right

#### Scenario: Tall tab
- **WHEN** the instance preview is resized so that the Visualization tab is taller than it is wide
- **THEN** the graph of state 0 is above the graph of state 1

#### Scenario: Loop target caption
- **WHEN** a trace with 3 states that loops back to state 1 is shown at state 2
- **THEN** the second pane is captioned "State 1 (loop)"

#### Scenario: Table tab
- **WHEN** a trace is shown at state 0 and the user selects the Table tab
- **THEN** the tab shows the tables of state 0 and of state 1 in two captioned panes, arranged by the same rule

### Requirement: Trace path strip
For a result with more than one state, the instance preview SHALL show a strip above the tabs. It draws the trace as the Alloy visualizer does: one circle per state, numbered from 0 and in order, with an arrow from each state to the next. Each number SHALL be centered in its circle. The strip SHALL be visible whichever tab is selected. For a result with a single state, no strip is shown.

#### Scenario: Circles and arrows
- **WHEN** a trace with 3 states that loops back to state 1 is shown
- **THEN** the strip shows circles numbered 0, 1 and 2 from left to right, an arrow from 0 to 1 and an arrow from 1 to 2

#### Scenario: Every tab
- **WHEN** a trace is shown and the user selects the Text tab, then the Table tab
- **THEN** the strip stays visible above both tabs

#### Scenario: Static result
- **WHEN** the shown result has a single state
- **THEN** no strip is shown

### Requirement: Trace path strip placement
The strip SHALL be on the toolbar's line, after the New solution buttons and the projection chooser, when it fits there entirely. Otherwise it SHALL be on a line of its own below them, as wide as the instance preview. The placement SHALL follow every resize of the instance preview.

#### Scenario: Beside the toolbar
- **WHEN** a trace with 3 states is shown in an instance preview wide enough for the toolbar and the whole strip
- **THEN** the strip follows the projection chooser on the toolbar's line

#### Scenario: Below the toolbar
- **WHEN** the instance preview is too narrow for the toolbar and the whole strip
- **THEN** the strip is on a line of its own below the toolbar, and moves back beside it when the instance preview is widened again

### Requirement: Long traces in the strip
When the circles do not fit in the strip's width, the strip SHALL scroll horizontally. Whenever the selection changes, the strip SHALL scroll so that the circles of the selected state and its successor are visible, when both fit.

#### Scenario: Selecting a state near the end
- **WHEN** a trace of 30 states is shown in a narrow instance preview at state 0, and the user scrolls the strip and clicks the circle of state 25
- **THEN** the strip scrolls so that the circles of states 25 and 26 are visible

### Requirement: Trace path strip background
The strip SHALL have no background of its own: around its circles and arrows, it shows the background of the instance preview around it, under every theme.

#### Scenario: Same background as the toolbar
- **WHEN** a trace is shown, under a light or a dark theme
- **THEN** there is no visible edge between the strip and the toolbar around it

### Requirement: Showing a solution keeps the IDE responsive
The analyzer's work of showing a solution SHALL not run on the IDE's user interface thread: reading the solution, inferring its theme, and the text, difference and tables of each state. Opening a result from its hint MAY show a progress dialog while the solution is read. When several states are selected in quick succession, the instance preview SHALL end up showing the last one selected.

#### Scenario: Stepping quickly
- **WHEN** the user selects state 2 and then state 1 of a trace before state 2 is shown
- **THEN** the tabs show state 1

## REMOVED Requirements

### Requirement: Instance tool window
**Reason**: The viewer moves into the editor of the file whose command produced the result, and the Alloy Instance tool window is removed.
**Migration**: Click a result hint as before. The result appears in the instance preview next to the file's text (see "Instance preview in the editor" and "Instance preview layout").

## RENAMED Requirements

- FROM: `### Requirement: Outdated result in the tool window`
- TO: `### Requirement: Outdated result in the preview`
