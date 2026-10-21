# Spec Delta

## ADDED Requirements

### Requirement: Trace path strip
For a result with more than one state, the tool window SHALL show a strip above the tabs. It draws the trace as the Alloy visualizer does: one circle per state, numbered from 0 and in order, with an arrow from each state to the next. Each number SHALL be centered in its circle. The strip SHALL be visible whichever tab is selected. For a result with a single state, no strip is shown.

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
The strip SHALL be on the toolbar's line, after the New solution buttons and the projection chooser, when it fits there entirely. Otherwise it SHALL be on a line of its own below them, as wide as the tool window. The placement SHALL follow every resize of the tool window.

#### Scenario: Beside the toolbar
- **WHEN** a trace with 3 states is shown in a tool window wide enough for the toolbar and the whole strip
- **THEN** the strip follows the projection chooser on the toolbar's line

#### Scenario: Below the toolbar
- **WHEN** the tool window is too narrow for the toolbar and the whole strip
- **THEN** the strip is on a line of its own below the toolbar, and moves back beside it when the tool window is widened again

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
Clicking a state's circle SHALL select that state: every tab and the pane captions show that state and its successor. Clicking outside any circle SHALL not change the selection.

#### Scenario: Clicking a later state
- **WHEN** a trace with 3 states that loops back to state 1 is shown at state 0 and the user clicks the circle of state 2
- **THEN** the tabs show state 2 followed by state 1, captioned "State 2" and "State 1 (loop)"

#### Scenario: Clicking between circles
- **WHEN** the user clicks the strip between two circles
- **THEN** the selected state does not change

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
- **WHEN** a trace of 30 states is shown in a narrow tool window at state 0, and the user scrolls the strip and clicks the circle of state 25
- **THEN** the strip scrolls so that the circles of states 25 and 26 are visible

### Requirement: Trace path strip background
The strip SHALL have no background of its own: around its circles and arrows, it shows the background of the tool window around it, under every theme.

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

## MODIFIED Requirements

### Requirement: New solutions
The tool window toolbar SHALL offer New config, New trace, New init and New fork. Each asks the analyzer for another solution of the same command:
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
- **THEN** the tool window shows the next solution of the same command, and the header still names that command

#### Scenario: Temporal model
- **WHEN** the shown result comes from a model with mutable relations
- **THEN** New config, New trace, New init and New fork are all enabled

#### Scenario: New fork from the current state
- **WHEN** state 2 of a trace is selected and the user clicks New fork
- **THEN** the tool window shows a solution that agrees with the previous one up to state 2 and differs afterwards

#### Scenario: No more solutions
- **WHEN** the user clicks one of these buttons and the analyzer finds no further solution of that kind
- **THEN** the current solution stays visible, and the tool window says that there are no more solutions of that kind

#### Scenario: Outdated result
- **WHEN** the shown result is outdated
- **THEN** all four buttons are disabled, and their tooltip says to run the command again

## REMOVED Requirements

### Requirement: Trace state stepper
**Reason**: The trace path strip supersedes it: it shows every state, the selected pair and the loop, and selects a state with one click. The previous and next buttons, the "State i of n" label and the "Loops back to state k" label are removed.
**Migration**: Click a state's circle in the trace path strip. The second pane's caption still names the loop target as "State k (loop)", and the loop target's tooltip in the strip says that the last state loops back to it.
