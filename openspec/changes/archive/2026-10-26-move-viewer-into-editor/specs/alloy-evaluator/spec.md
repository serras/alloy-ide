# Spec Delta

## MODIFIED Requirements

### Requirement: Evaluator panel
The instance preview SHALL offer an Evaluator panel below its tabs, which a toolbar toggle shows and hides. The panel SHALL remember whether it was shown across results and IDE sessions. While no result is shown, there is no panel. The panel SHALL say which state it evaluates at. The panel of each file's instance preview SHALL evaluate in that file's result.

#### Scenario: Showing the panel
- **WHEN** an instance is shown and the user turns on the Evaluator toggle in the toolbar
- **THEN** the Evaluator panel appears below the Visualization, Text and Table tabs, with an expression field and the watches list

#### Scenario: Remembered visibility
- **WHEN** the panel is shown and the user opens another result, or restarts the IDE and opens a result
- **THEN** the Evaluator panel is shown

#### Scenario: State named
- **WHEN** a trace is shown at state 2
- **THEN** the panel says that it evaluates at state 2

#### Scenario: Two files with a result
- **WHEN** `graph.als` and `queue.als` each show a result, and the user evaluates `#univ` in the Evaluator panel of `queue.als`
- **THEN** the value is computed in the result of `queue.als`, and the panel of `graph.als` is unchanged
