# Spec Delta

## MODIFIED Requirements

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

## ADDED Requirements

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

## RENAMED Requirements

- FROM: `### Requirement: Two trace states arranged by the tab's shape`
- TO: `### Requirement: Two trace states in a chosen layout`
