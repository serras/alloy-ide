# alloy-instance-viewer Specification

## Purpose

Shows the instances and counterexamples found by Alloy analyses inside the IDE, as a graph, text or table. It lets the user enumerate further solutions, step through Alloy 6 traces and project over signatures, and always says where the shown result came from.

## Requirements

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
The instance preview SHALL show a header naming the result's file and its command's line, as `graph.als:12`, then the command as written (for example `run show for 3`), then the text that the command's result hint showed when the result was opened, such as "Counterexample found · 9 ms" or "Instance found, as expected · 4 ms". The line SHALL be where the command is now, following edits of the file; when the command is not found in the file, the header names no line. Clicking the header SHALL move the caret of the editor next to the preview to the command.

#### Scenario: Header contents
- **WHEN** the instance preview shows the counterexample of `check noCycles for 3` from line 12 of `graph.als`
- **THEN** the header reads `graph.als:12: check noCycles for 3 · Counterexample found · 9 ms`, the last part as the hint read when it was clicked

#### Scenario: Lines inserted above the command
- **WHEN** the user inserts a line above `check noCycles for 3`
- **THEN** the header starts with `graph.als:13:`

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
The instance preview SHALL show the current solution in three tabs, chosen with the tab buttons on the toolbar line: "Visualization", a graph of the instance; "Text", its textual form as the Alloy analyzer prints it; and "Table", its tabular form as the Alloy analyzer formats it. Only the selected tab's content is shown, without a row of tab titles. All three show the same solution and trace states, and switching tabs keeps them. For a result with more than one state, each tab shows the selected state together with the state that follows it, as the trace state requirements below describe.

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

#### Scenario: No tab titles
- **WHEN** the instance preview shows an instance
- **THEN** there is no row of tab titles between the toolbar line and the selected tab's content

### Requirement: Magic layout by default
The Visualization tab SHALL lay out every newly opened result using the Alloy visualizer's automatic "magic layout" theme. That theme infers node visibility, colors, shapes, edge labels, attributes and a default projection.

#### Scenario: Ordering module
- **WHEN** an instance of a model that opens `util/ordering[State]` is shown
- **THEN** the graph applies the magic layout's choices for that model, such as hiding the ordering's helper relations

### Requirement: New solutions
The instance preview toolbar SHALL offer New config, New trace, New init and New fork. Each asks the analyzer for another solution of the same command:
- **New config:** a different configuration of the immutable relations.
- **New trace:** a different trace with the same configuration.
- **New init:** a different initial state.
- **New fork:** a different successor of the current state.

Each button SHALL have its own icon, readable under both a light and a dark theme. The icons of New trace, New init and New fork SHALL draw states as the trace path strip does. For a result without a trace path strip, New trace, New init and New fork SHALL NOT be shown. A button SHALL be disabled when the kind of solution does not apply to the current result.

#### Scenario: Static model
- **WHEN** the shown result comes from a model without mutable signatures or fields
- **THEN** only New config is shown, and it is enabled

#### Scenario: Next configuration
- **WHEN** the user clicks New config
- **THEN** the instance preview shows the next solution of the same command, and the header still names that command

#### Scenario: Temporal model
- **WHEN** the shown result comes from a model with mutable relations, and shows a trace path strip
- **THEN** New config, New trace, New init and New fork are all shown and enabled

#### Scenario: New fork from the current state
- **WHEN** state 2 of a trace is selected and the user clicks New fork
- **THEN** the instance preview shows a solution that agrees with the previous one up to state 2 and differs afterwards

#### Scenario: No more solutions
- **WHEN** the user clicks one of these buttons and the analyzer finds no further solution of that kind
- **THEN** the current solution stays visible, and the instance preview says that there are no more solutions of that kind

#### Scenario: Outdated result
- **WHEN** the shown result is outdated
- **THEN** every shown New button is disabled, and its tooltip says to run the command again

#### Scenario: Icons
- **WHEN** a trace is shown
- **THEN** New trace's icon is a row of states with a loop, New init's highlights the first state, and New fork's shows a state branching into several, under a light and a dark theme alike

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

### Requirement: Two trace states in a chosen layout
For a result with more than one state, the Visualization and Table tabs SHALL each show two panes, the selected state and its successor. Each pane is captioned with its state number, and a successor that is the loop target is marked as such. The panes SHALL be placed side by side or stacked vertically, as the user chose with the tab buttons; both tabs SHALL use the same layout. The layout SHALL start side by side, SHALL be kept for later results and after restarting the IDE, and SHALL NOT change when the instance preview is resized.

#### Scenario: Wide tab
- **WHEN** a trace is shown at state 0 and the layout is side by side
- **THEN** the graph of state 0, captioned "State 0", is on the left and the graph of state 1, captioned "State 1", is on its right

#### Scenario: Stacked
- **WHEN** a trace is shown at state 0 and the layout is stacked
- **THEN** the graph of state 0 is above the graph of state 1

#### Scenario: Tall tab
- **WHEN** the layout is side by side and the instance preview is resized so that the Visualization tab is taller than it is wide
- **THEN** the graphs stay side by side

#### Scenario: Loop target caption
- **WHEN** a trace with 3 states that loops back to state 1 is shown at state 2
- **THEN** the second pane is captioned "State 1 (loop)"

#### Scenario: Table tab
- **WHEN** a trace is shown at state 0 and the user selects the Table tab
- **THEN** the tab shows the tables of state 0 and of state 1 in two captioned panes, in the same layout as the Visualization tab

#### Scenario: Layout remembered
- **WHEN** the user chose the stacked layout and opens another trace, or restarts the IDE and opens one
- **THEN** its panes are stacked

### Requirement: Text tab shows the difference between trace states
For a result with more than one state, the Text tab SHALL show one line per signature, field and skolem, in the order the analyzer prints them, with the tuples of the selected state and of its successor. Each tuple SHALL be marked as kept, added (only in the successor) or removed (only in the selected state), using the editor color scheme's colors for inserted and deleted text. Lines whose tuples do not change SHALL be dimmed.

#### Scenario: Added and removed tuples
- **WHEN** a trace is shown where `this/X` is `{A$0}` in the selected state and `{A$1}` in its successor
- **THEN** the line of `this/X` shows `A$0` marked as removed and `A$1` marked as added

#### Scenario: Unchanged relation
- **WHEN** the field `this/A<:f` has the same tuples in both states
- **THEN** its line shows those tuples without marks, dimmed

#### Scenario: Order of the lines
- **WHEN** the Text tab shows the difference between two states
- **THEN** its lines follow the order of the analyzer's text for a single state: each signature followed by its fields, then the skolems

#### Scenario: Diff into the loop target
- **WHEN** a trace with 3 states that loops back to state 1 is shown at state 2
- **THEN** the Text tab shows the difference from state 2 to state 1

### Requirement: Visualization follows a dark IDE theme
When the IDE uses a dark theme, the Visualization tab SHALL draw the graph with dark versions of its colors, keeping each color's hue. The canvas uses the IDE's background; black lines and outlines use the IDE's foreground. Node fills become dark, muted colors, and node labels are always light. Edges and their labels are made light enough to stand out. Light themes keep the visualizer's colors.

#### Scenario: Dark theme
- **WHEN** the IDE uses a dark theme and an instance is shown
- **THEN** the canvas has the IDE's background, node labels are light, and no part of the graph is drawn on a white background

#### Scenario: Node colors keep their identity
- **WHEN** the magic layout colors one signature gold and another blue, and the IDE uses a dark theme
- **THEN** the nodes of the first signature are a dark gold and those of the second a dark blue, and both are distinguishable from the canvas

#### Scenario: Edges stay visible
- **WHEN** an edge is black or dark in the visualizer's colors and the IDE uses a dark theme
- **THEN** the edge and its label are drawn in a light color on the dark canvas

#### Scenario: Theme switch
- **WHEN** an instance is shown and the user switches the IDE from a light to a dark theme
- **THEN** the graph is redrawn with the dark colors, without opening the result again

#### Scenario: Light theme
- **WHEN** the IDE uses a light theme
- **THEN** the graph is drawn with the visualizer's own colors on a white canvas, as before

### Requirement: Trace path strip
For a result with more than one state, the instance preview SHALL show a strip on or under the toolbar line, above the selected tab's content. It draws the trace as the Alloy visualizer does: one circle per state, numbered from 0 and in order, with an arrow from each state to the next. Each number SHALL be centered in its circle. The strip SHALL be visible whichever tab is selected. For a result with a single state, no strip is shown.

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
The toolbar line SHALL have the New solution buttons, the Evaluator toggle and the projection chooser at its left end, and the tab buttons at its right end. The strip SHALL be on this line, between them, when it fits there entirely: centered on the line if it then overlaps neither end, and otherwise centered between them. When it does not fit, it SHALL be on a line of its own below, as wide as the instance preview. The placement SHALL follow every resize.

#### Scenario: Beside the toolbar
- **WHEN** a trace with 3 states is shown in an instance preview wide enough that the strip, centered on the toolbar line, overlaps neither the projection chooser nor the tab buttons
- **THEN** the strip is in the middle of the toolbar line, with the New solution buttons at the left and the tab buttons at the right

#### Scenario: Centered between the ends
- **WHEN** the instance preview is narrowed so that the centered strip would overlap the projection chooser, but the whole strip still fits between the projection chooser and the tab buttons
- **THEN** the strip is on the toolbar line, centered in the space between the projection chooser and the tab buttons

#### Scenario: Below the toolbar
- **WHEN** the instance preview is too narrow for the whole strip between the projection chooser and the tab buttons
- **THEN** the strip is on a line of its own below the toolbar line, the tab buttons stay at the right end of the toolbar line, and the strip moves back onto the toolbar line when the instance preview is widened again

#### Scenario: Static result
- **WHEN** the shown result has a single state
- **THEN** the toolbar line has the New solution buttons at its left end and the tab buttons at its right end, and nothing between them

### Requirement: Loop arrow
The strip SHALL draw the loop as a curved arrow above the circles, from the last state's circle to the loop target's circle. When the last state loops back to itself, the arrow leaves and returns to the same circle.

#### Scenario: Loop to an earlier state
- **WHEN** a trace with 3 states that loops back to state 1 is shown
- **THEN** a curved arrow goes from the circle of state 2 back to the circle of state 1

#### Scenario: Loop to the first state
- **WHEN** a trace with 4 states that loops back to state 0 is shown
- **THEN** the curved arrow goes from the circle of state 3 to the circle of state 0, over states 1 and 2

#### Scenario: Last state loops to itself
- **WHEN** a trace with 2 states whose last state loops back to itself is shown
- **THEN** a curved arrow leaves the circle of state 1 and returns to it

### Requirement: Shown states highlighted in the strip
The strip SHALL highlight the circles of the two states the tabs show: the selected state and its successor. The other circles are drawn dimmed. When the selection changes, the highlighting SHALL follow it.

#### Scenario: Selected state and its successor
- **WHEN** a trace with 3 states that loops back to state 1 is shown at state 0
- **THEN** the circles of states 0 and 1 are highlighted and the circle of state 2 is dimmed

#### Scenario: Pair across the loop
- **WHEN** that trace is shown at state 2
- **THEN** the circles of states 2 and 1 are highlighted, joined by the loop arrow, and the circle of state 0 is dimmed

#### Scenario: Following the selection
- **WHEN** that trace is at state 0 and the user clicks the circle of state 1
- **THEN** the circles of states 1 and 2 are highlighted, and the circle of state 0 is dimmed

### Requirement: Navigating from the strip
Clicking a state's circle SHALL select that state: every tab and the pane captions show that state and its successor. Clicking outside any circle SHALL not change the selection. Clicking a circle SHALL also focus the strip, where the keyboard steps through the trace: Right selects the successor (after the last state, the loop target), Left the state before (none before state 0), and Home and End the first and last states.

#### Scenario: Clicking a later state
- **WHEN** a trace with 3 states that loops back to state 1 is shown at state 0 and the user clicks the circle of state 2
- **THEN** the tabs show state 2 followed by state 1, captioned "State 2" and "State 1 (loop)"

#### Scenario: Clicking between circles
- **WHEN** the user clicks the strip between two circles
- **THEN** the selected state does not change

#### Scenario: Stepping with the keyboard
- **WHEN** a trace with 3 states that loops back to state 1 is shown at state 2, and the user presses Right in the strip
- **THEN** the tabs show state 1 followed by state 2

#### Scenario: Projection kept
- **WHEN** the graph is projected over `Node` showing `Node1` and the user clicks another state's circle
- **THEN** the graphs of the newly selected states are still projected over `Node` and show `Node1`

### Requirement: State tooltips in the strip
Hovering a state's circle SHALL show a tooltip naming that state, for example "State 2". The loop target's tooltip SHALL also say that the last state loops back to it.

#### Scenario: Loop target tooltip
- **WHEN** a trace with 3 states that loops back to state 1 is shown and the user hovers the circle of state 1
- **THEN** the tooltip names state 1 and says that state 2 loops back to it

#### Scenario: Ordinary state tooltip
- **WHEN** the user hovers the circle of state 0
- **THEN** the tooltip says "State 0"

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

### Requirement: Trace path strip follows the IDE theme
Under a light IDE theme, the strip SHALL use the Alloy visualizer's colors: white highlighted circles, grey dimmed circles, and black outlines, numbers and arrows. Under a dark theme, it SHALL use the IDE's foreground for its outlines, numbers and arrows, and dimmed circles of the background around it, with highlighted circles lighter than dimmed ones. The strip SHALL repaint when the theme changes.

#### Scenario: Light theme
- **WHEN** the IDE uses a light theme and a trace is shown
- **THEN** the strip shows the toolbar's background, the highlighted circles are white, the dimmed ones grey, and the numbers and arrows black

#### Scenario: Dark theme
- **WHEN** the IDE uses a dark theme and a trace is shown
- **THEN** the strip shows the toolbar's background, the arrows and numbers are light, and the highlighted circles are lighter than the dimmed ones

#### Scenario: Theme switch
- **WHEN** a trace is shown and the user switches the IDE from a light to a dark theme
- **THEN** the strip is redrawn with the dark colors, without opening the result again

### Requirement: Selected trace state and its successor
For results with more than one state, a result SHALL open at state 0, and every tab SHALL show the selected state together with its successor, which after the last state is the loop target. The trace path strip is the way to select another state.

#### Scenario: First state
- **WHEN** a trace with 3 states and a loop back to state 1 is opened
- **THEN** every tab shows state 0 followed by state 1

#### Scenario: Successor of the last state
- **WHEN** that trace is at state 2, its last state
- **THEN** every tab shows state 2 followed by state 1, the loop target

#### Scenario: Static result
- **WHEN** the shown result has a single state
- **THEN** every tab shows that state alone

### Requirement: Showing a solution keeps the IDE responsive
The analyzer's work of showing a solution SHALL not run on the IDE's user interface thread: reading the solution, inferring its theme, and the text, difference and tables of each state. Opening a result from its hint MAY show a progress dialog while the solution is read. When several states are selected in quick succession, the instance preview SHALL end up showing the last one selected.

#### Scenario: Stepping quickly
- **WHEN** the user selects state 2 and then state 1 of a trace before state 2 is shown
- **THEN** the tabs show state 1

### Requirement: Tab buttons
The toolbar line SHALL end with one button per tab, in the order Visualization, Text, Table. Each button SHALL show an icon and no text, and its tooltip SHALL be the tab's name. The button of the selected tab SHALL be drawn pressed, as the editor's layout buttons are. Pressing a button SHALL select its tab. Pressing the selected tab's button SHALL keep that tab selected.

#### Scenario: Selecting a tab
- **WHEN** the Visualization tab is selected and the user presses the second tab button
- **THEN** the Text tab is shown, its button is drawn pressed, and the Visualization button is not

#### Scenario: Tooltips
- **WHEN** the user hovers each tab button
- **THEN** the tooltips are "Visualization", "Text" and "Table"

#### Scenario: Pressing the selected tab
- **WHEN** the Table tab is selected and the user presses its button again
- **THEN** the Table tab stays selected and its button stays pressed

### Requirement: Pane layout switch
The tab buttons SHALL switch the layout of the trace state panes. The icons of the Visualization and Table buttons SHALL show the current layout, side by side or stacked. Pressing the button of another tab SHALL keep the layout. Pressing the button of the selected Visualization or Table tab SHALL switch the layout between side by side and stacked; pressing that of the selected Text tab SHALL NOT.

#### Scenario: Switching tabs keeps the layout
- **WHEN** a trace is shown with the panes stacked in the Visualization tab, and the user presses the Table button
- **THEN** the Table tab is shown, with the tables of the two states stacked

#### Scenario: Pressing the selected tab switches the layout
- **WHEN** the Table tab is selected with the panes side by side, and the user presses its button again
- **THEN** the Table tab stays selected and its button stays pressed, the panes are stacked, and the Visualization and Table buttons' icons show the stacked layout

#### Scenario: Pressing the selected Text tab
- **WHEN** the Text tab is selected and the user presses its button again
- **THEN** the Text tab stays selected, and the layout and the icons do not change
