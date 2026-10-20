# Spec Delta

## Purpose

Shows the instances and counterexamples found by Alloy analyses inside the IDE, as a graph, text or table. It lets the user enumerate further solutions, step through Alloy 6 traces and project over signatures, and always says where the shown result came from.

## ADDED Requirements

### Requirement: Instance tool window
The plugin SHALL provide an "Alloy Instance" tool window per project that shows one analysis result at a time. Opening an instance or counterexample SHALL show it in this tool window and bring the window to front. No separate visualizer window is opened.

#### Scenario: Opening an instance
- **WHEN** `run show` found an instance and the user clicks its hint
- **THEN** the Alloy Instance tool window opens and shows that instance, and no other window is opened

#### Scenario: Before any result
- **WHEN** the user opens the Alloy Instance tool window before opening any result
- **THEN** it shows an empty state explaining that instances appear when an instance or counterexample hint is clicked

### Requirement: Each result replaces the previous one
Opening another result SHALL replace the tool window's content. The tool window SHALL never show two results at once.

#### Scenario: Opening a second result
- **WHEN** the tool window shows the instance of `run show` and the user clicks the counterexample hint of `check noCycles`
- **THEN** the tool window shows only the counterexample of `check noCycles`

### Requirement: Result origin header
The tool window SHALL show a header naming the result's file, its command as written (for example `run show for 3`), its line, and whether it is an instance or a counterexample. Clicking the header SHALL navigate to the command in the editor.

#### Scenario: Header contents
- **WHEN** the tool window shows the counterexample of `check noCycles for 3` from line 12 of `graph.als`
- **THEN** the header names `graph.als`, `check noCycles for 3`, line 12 and "Counterexample"

#### Scenario: Navigating to the origin
- **WHEN** the user clicks the header
- **THEN** `graph.als` opens with the caret on the `check noCycles for 3` command

### Requirement: Outdated result in the tool window
When the result shown in the tool window becomes outdated, the header SHALL be dimmed and labelled as outdated. This happens when its file is edited or analyzer settings change. The solution shown SHALL stay visible.

#### Scenario: Editing the model
- **WHEN** the tool window shows an instance of `run show` and the user edits the file
- **THEN** the header is shown as outdated, and the instance remains visible

### Requirement: Visualization, Text and Table tabs
The tool window SHALL show the current solution in three tabs: "Visualization", a graph of the instance; "Text", its textual form as the Alloy analyzer prints it; and "Table", its tabular form as the Alloy analyzer formats it. All three show the same solution and trace state, and switching tabs keeps them.

#### Scenario: Switching to Text
- **WHEN** the tool window shows an instance and the user selects the Text tab
- **THEN** the tab shows every signature and field of that instance with their atoms and tuples

#### Scenario: Switching to Table
- **WHEN** the user selects the Table tab
- **THEN** the tab shows the same instance as tables, one per signature or field

#### Scenario: Default tab
- **WHEN** a result is opened for the first time in the session
- **THEN** the Visualization tab is selected; when later results are opened, the tab the user last selected stays selected

### Requirement: Magic layout by default
The Visualization tab SHALL lay out every newly opened result using the Alloy visualizer's automatic "magic layout" theme. That theme infers node visibility, colors, shapes, edge labels, attributes and a default projection.

#### Scenario: Ordering module
- **WHEN** an instance of a model that opens `util/ordering[State]` is shown
- **THEN** the graph applies the magic layout's choices for that model, such as hiding the ordering's helper relations

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
- **WHEN** the state stepper shows state 2 of a trace and the user clicks New fork
- **THEN** the tool window shows a solution that agrees with the previous one up to state 2 and differs afterwards

#### Scenario: No more solutions
- **WHEN** the user clicks one of these buttons and the analyzer finds no further solution of that kind
- **THEN** the current solution stays visible, and the tool window says that there are no more solutions of that kind

#### Scenario: Outdated result
- **WHEN** the shown result is outdated
- **THEN** all four buttons are disabled, and their tooltip says to run the command again

### Requirement: Solution enumeration runs as an analysis
Asking for a new solution SHALL run as an Alloy analysis of the project. It shows the same cancellable background progress indicator, can be stopped like any analysis, and counts toward the limit of one analysis at a time per project.

#### Scenario: Busy project
- **WHEN** a command is executing and the user clicks New config
- **THEN** no new solution is requested, and the tool window says that an Alloy analysis is already running

#### Scenario: Stopping enumeration
- **WHEN** the user cancels the progress indicator of a New trace request
- **THEN** the request ends, and the previous solution stays visible

### Requirement: Trace state stepper
For results with more than one state, the toolbar SHALL show the current state as "State i of n" and the loop-back state. It SHALL also offer previous and next state buttons. Every tab SHALL show the selected state. Stepping past the last state SHALL follow the loop.

#### Scenario: Stepping forward
- **WHEN** a trace with 3 states and a loop back to state 1 is shown at state 0, and the user clicks next state
- **THEN** every tab shows state 1, and the stepper says "State 1 of 3"

#### Scenario: Following the loop
- **WHEN** that trace is at its last state and the user clicks next state
- **THEN** the stepper shows state 1, the loop target

#### Scenario: Static result
- **WHEN** the shown result has a single state
- **THEN** no state stepper is shown

### Requirement: Projection selection
The toolbar SHALL let the user project the visualization over any signature that the visualizer allows to be projected, and remove projections. For each projected signature the tool window SHALL let the user pick the atom being shown. Projection SHALL only affect the Visualization tab.

#### Scenario: Projecting over a signature
- **WHEN** the user selects projection over `State`
- **THEN** the graph shows the instance for one `State` atom, and an atom selector for `State` appears

#### Scenario: Removing the projection
- **WHEN** the user selects no projection
- **THEN** the graph shows the whole instance again

#### Scenario: Default projection from magic layout
- **WHEN** the magic layout chooses a projection for a new result
- **THEN** the projection selector reflects that choice

### Requirement: Visualizer independent of desktop preferences
The tool window SHALL neither read nor write the Alloy desktop application's stored visualizer preferences, such as its last visualization mode or theme.

#### Scenario: Desktop mode not used
- **WHEN** the Alloy desktop application was last left in its Text mode
- **THEN** the tool window still opens its first result in the Visualization tab
