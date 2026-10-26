# Spec Delta

## MODIFIED Requirements

### Requirement: Two trace states arranged by the tab's shape
For a result with more than one state, the Visualization and Table tabs SHALL each show two panes, the selected state and its successor. Each pane is captioned with its state number, and a successor that is the loop target is marked as such. When the transition between them has events, the successor's caption SHALL also name them. The panes SHALL be stacked vertically when the tab is taller than it is wide, and placed side by side otherwise, and SHALL be rearranged when the tab is resized.

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

#### Scenario: Caption with the transition's events
- **WHEN** a leader election trace is shown at state 3, and the events of the transition from state 3 are `send[Node$1, Id$2]`
- **THEN** the second pane of the Visualization and Table tabs is captioned "State 4, after send[Node$1, Id$2]"

#### Scenario: Caption without events
- **WHEN** the model has no event predicates, or the transition has no events
- **THEN** the second pane is captioned with its state only, as before

## ADDED Requirements

### Requirement: Timeline of transitions
For a trace whose model has event predicates, the instance preview SHALL show a timeline under the toolbar with one entry per transition, including the one into the loop target. Each entry is labelled with its events. The entry of the selected state's transition is highlighted. The trace path strip, if shown, SHALL carry these labels on its transitions instead of a separate timeline.

#### Scenario: Timeline of a trace
- **WHEN** a leader election trace with 4 states that loops back to state 3 is shown
- **THEN** the timeline shows 4 transitions: 0→1, 1→2, 2→3 and 3→3, each labelled with its events, such as `initiate[Node$0]`

#### Scenario: Selecting a transition
- **WHEN** the user clicks the transition 2→3 in the timeline
- **THEN** the trace is shown at state 2, followed by state 3, and the first pane is captioned "State 2"

#### Scenario: Following the selection
- **WHEN** the user selects another state, by clicking its circle in the trace path strip
- **THEN** the highlighted entry of the timeline moves to the transition from the new state

#### Scenario: Navigating to an event
- **WHEN** the user clicks the name `send` in the label `send[Node$1, Id$2]`
- **THEN** the editor opens the file declaring `send`, with the caret on its declaration

#### Scenario: Many events
- **WHEN** a transition has more than 3 event instances
- **THEN** its label shows the first 3 and "+k more", and its tooltip lists all of them

#### Scenario: Transition without events
- **WHEN** no event holds at a transition
- **THEN** its label says "no event", in a warning color, because the trace fact is then not covered by the chosen events

#### Scenario: Events not yet known
- **WHEN** the events are still being computed
- **THEN** the timeline shows its transitions with a placeholder, and the rest of the instance preview can already be used

#### Scenario: No timeline
- **WHEN** the shown result has a single state, or its model has no event predicates
- **THEN** no timeline is shown

### Requirement: Events dropdown
For a trace, the toolbar SHALL offer an "Events" dropdown listing the model's predicates, with the event predicates checked, like the projection dropdown. Checking or unchecking one SHALL update the timeline and captions without running the analyzer. When collapsed, it SHALL say how many events are chosen and whether they are detected or chosen. It SHALL offer a reset to the detected events.

#### Scenario: Detected events
- **WHEN** a leader election trace is shown and the user opens the Events dropdown
- **THEN** `initiate`, `send`, `process` and `stutter` are checked, `node_acts` and the other predicates are unchecked, and the collapsed dropdown says "4 detected"

#### Scenario: Choosing an event
- **WHEN** the user checks `node_acts`
- **THEN** the timeline's labels include `node_acts[...]` instances too, no analysis is started, and the collapsed dropdown says "5 chosen"

#### Scenario: Static result
- **WHEN** the shown result has a single state
- **THEN** no Events dropdown is shown
