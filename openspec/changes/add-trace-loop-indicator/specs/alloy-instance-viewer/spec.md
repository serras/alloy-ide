# Spec Delta

## ADDED Requirements

### Requirement: Trace path strip
For a result with more than one state, the tool window SHALL show a strip between the toolbar and the tabs. It draws the trace as the Alloy visualizer does: one circle per state, numbered from 0 and in order, with an arrow from each state to the next. The strip SHALL be visible whichever tab is selected. For a result with a single state, no strip is shown.

#### Scenario: Circles and arrows
- **WHEN** a trace with 3 states that loops back to state 1 is shown
- **THEN** the strip shows circles numbered 0, 1 and 2 from left to right, an arrow from 0 to 1 and an arrow from 1 to 2

#### Scenario: Every tab
- **WHEN** a trace is shown and the user selects the Text tab, then the Table tab
- **THEN** the strip stays visible above both tabs

#### Scenario: Static result
- **WHEN** the shown result has a single state
- **THEN** no strip is shown

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
The strip SHALL highlight the circles of the two states the tabs show: the selected state and its successor. The other circles are drawn dimmed. When the selection changes, by stepping or otherwise, the highlighting SHALL follow it.

#### Scenario: Selected state and its successor
- **WHEN** a trace with 3 states that loops back to state 1 is shown at state 0
- **THEN** the circles of states 0 and 1 are highlighted and the circle of state 2 is dimmed

#### Scenario: Pair across the loop
- **WHEN** that trace is shown at state 2
- **THEN** the circles of states 2 and 1 are highlighted, joined by the loop arrow, and the circle of state 0 is dimmed

#### Scenario: Following the stepper
- **WHEN** that trace is at state 0 and the user clicks next state
- **THEN** the circles of states 1 and 2 are highlighted, and the stepper says "State 1 of 3"

### Requirement: Navigating from the strip
Clicking a state's circle SHALL select that state, with the same effect as stepping to it. Every tab, the stepper and the pane captions show that state and its successor. Clicking outside any circle SHALL not change the selection.

#### Scenario: Clicking a later state
- **WHEN** a trace with 3 states that loops back to state 1 is shown at state 0 and the user clicks the circle of state 2
- **THEN** the stepper says "State 2 of 3", and the tabs show state 2 followed by state 1, captioned "State 1 (loop)"

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

#### Scenario: Stepping to a state out of view
- **WHEN** a trace of 30 states is shown in a narrow tool window at state 0 and the user steps to state 25
- **THEN** the strip scrolls so that the circles of states 25 and 26 are visible

### Requirement: Trace path strip follows the IDE theme
Under a light IDE theme, the strip SHALL use the Alloy visualizer's colors: white highlighted circles, grey dimmed circles, and black outlines, numbers and arrows. Under a dark theme, it SHALL use the IDE's background and foreground for the strip and its arrows, with highlighted circles lighter than dimmed ones. The strip SHALL repaint when the theme changes.

#### Scenario: Light theme
- **WHEN** the IDE uses a light theme and a trace is shown
- **THEN** the highlighted circles are white, the dimmed ones grey, and the numbers and arrows black

#### Scenario: Dark theme
- **WHEN** the IDE uses a dark theme and a trace is shown
- **THEN** the strip has the IDE's background, the arrows and numbers are light, and the highlighted circles are lighter than the dimmed ones

#### Scenario: Theme switch
- **WHEN** a trace is shown and the user switches the IDE from a light to a dark theme
- **THEN** the strip is redrawn with the dark colors, without opening the result again
