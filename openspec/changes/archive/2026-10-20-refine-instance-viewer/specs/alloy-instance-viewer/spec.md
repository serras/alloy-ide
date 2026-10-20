# Spec Delta

## MODIFIED Requirements

### Requirement: Visualization, Text and Table tabs
The tool window SHALL show the current solution in three tabs: "Visualization", a graph of the instance; "Text", its textual form as the Alloy analyzer prints it; and "Table", its tabular form as the Alloy analyzer formats it. All three show the same solution and trace states, and switching tabs keeps them. For a result with more than one state, each tab shows the selected state together with the state that follows it, as the trace state requirements below describe.

#### Scenario: Switching to Text
- **WHEN** the tool window shows an instance and the user selects the Text tab
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

### Requirement: Trace state stepper
For results with more than one state, the toolbar SHALL show the current state as "State i of n" and the loop-back state. It SHALL also offer previous and next state buttons. Every tab SHALL show the selected state together with its successor, which after the last state is the loop target. Stepping past the last state SHALL follow the loop.

#### Scenario: Stepping forward
- **WHEN** a trace with 3 states and a loop back to state 1 is shown at state 0, and the user clicks next state
- **THEN** every tab shows state 1 followed by state 2, and the stepper says "State 1 of 3"

#### Scenario: Successor of the last state
- **WHEN** that trace is at state 2, its last state
- **THEN** every tab shows state 2 followed by state 1, the loop target

#### Scenario: Following the loop
- **WHEN** that trace is at its last state and the user clicks next state
- **THEN** the stepper shows state 1, the loop target, and every tab shows state 1 followed by state 2

#### Scenario: Stepping one state at a time
- **WHEN** the tabs show state 1 followed by state 2 and the user clicks previous state
- **THEN** the tabs show state 0 followed by state 1

#### Scenario: Static result
- **WHEN** the shown result has a single state
- **THEN** no state stepper is shown

### Requirement: Projection selection
The toolbar SHALL let the user project the visualization over any number of the signatures that the visualizer allows to be projected, by checking and unchecking each in a dropdown, and SHALL let the user remove every projection at once. When collapsed, the dropdown SHALL name the projected signatures, or say "None". For each projected signature the tool window SHALL let the user pick the atom being shown. Projection SHALL only affect the Visualization tab. When the Visualization tab shows two trace states, the projection and the chosen atoms SHALL apply to both.

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

## ADDED Requirements

### Requirement: Two trace states arranged by the tab's shape
For a result with more than one state, the Visualization and Table tabs SHALL each show two panes, the selected state and its successor. Each pane is captioned with its state number, and a successor that is the loop target is marked as such. The panes SHALL be stacked vertically when the tab is taller than it is wide, and placed side by side otherwise, and SHALL be rearranged when the tab is resized.

#### Scenario: Wide tab
- **WHEN** a trace is shown at state 0 and the Visualization tab is wider than it is tall
- **THEN** the graph of state 0, captioned "State 0", is on the left and the graph of state 1, captioned "State 1", is on its right

#### Scenario: Tall tab
- **WHEN** the tool window is resized so that the Visualization tab is taller than it is wide
- **THEN** the graph of state 0 is above the graph of state 1

#### Scenario: Loop target caption
- **WHEN** a trace with 3 states that loops back to state 1 is shown at state 2
- **THEN** the second pane is captioned "State 1 (loop)"

#### Scenario: Table tab
- **WHEN** a trace is shown at state 0 and the user selects the Table tab
- **THEN** the tab shows the tables of state 0 and of state 1 in two captioned panes, arranged by the same rule

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
