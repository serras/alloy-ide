# alloy-evaluator Specification

## Purpose

Lets the user evaluate Alloy expressions and formulas against the instance or counterexample shown in the Alloy Instance tool window, once or as a list of watches that follow the selected state of a trace, as IntelliJ's debugger evaluates expressions in the current frame.

## Requirements

### Requirement: Evaluator panel
The Alloy Instance tool window SHALL offer an Evaluator panel below its tabs, which a toolbar toggle shows and hides. The panel SHALL remember whether it was shown across results and IDE sessions. While no result is shown, there is no panel. The panel SHALL say which state it evaluates at.

#### Scenario: Showing the panel
- **WHEN** an instance is shown and the user turns on the Evaluator toggle in the toolbar
- **THEN** the Evaluator panel appears below the Visualization, Text and Table tabs, with an expression field and the watches list

#### Scenario: Remembered visibility
- **WHEN** the panel is shown and the user opens another result, or restarts the IDE and opens a result
- **THEN** the Evaluator panel is shown

#### Scenario: State named
- **WHEN** a trace is shown at state 2
- **THEN** the panel says that it evaluates at state 2

### Requirement: Evaluating one expression
When the user types an Alloy expression or formula in the evaluator field and presses Enter, the panel SHALL show its value in the shown solution, under the field. A later evaluation replaces it. An empty field evaluates nothing.

#### Scenario: A relation
- **WHEN** the shown instance has `succ = {Node$0->Node$1, Node$1->Node$0}` and the user evaluates `succ`
- **THEN** the panel shows `succ` with a set of 2 tuples, `Node$0->Node$1` and `Node$1->Node$0`

#### Scenario: A formula
- **WHEN** the user evaluates `some succ` in that instance
- **THEN** the panel shows `true`

#### Scenario: An integer
- **WHEN** the user evaluates `#Node` in an instance with two nodes
- **THEN** the panel shows `2`

### Requirement: Evaluation errors
When an expression cannot be evaluated, the panel SHALL show the analyzer's message in place of the value, marked as an error. This covers syntax and type errors, unknown names, and expressions that the analyzer refuses, such as higher-order quantification. An error SHALL never close the panel or change the shown solution.

#### Scenario: Unknown name
- **WHEN** the user evaluates `Undefined`
- **THEN** the panel shows an error saying that the name `Undefined` cannot be found, and the shown solution is unchanged

#### Scenario: Syntax error
- **WHEN** the user evaluates `some (succ`
- **THEN** the panel shows the analyzer's syntax error

#### Scenario: Higher-order quantification
- **WHEN** the user evaluates `some r: Node -> Node | r = succ`
- **THEN** the panel shows that higher-order quantification is not allowed in the evaluator

### Requirement: Values as trees
A set of tuples SHALL be shown with its number of tuples, and SHALL expand to one row per tuple, in the analyzer's order, with the atoms named as the analyzer prints them. An empty set is shown as `{}` with no rows. Formulas SHALL show `true` or `false`, and integers their value, including the analyzer's overflow mark.

#### Scenario: Expanding a set
- **WHEN** the value of `succ` is shown and the user expands it
- **THEN** one row per tuple of `succ` is shown, such as `Node$0->Node$1`

#### Scenario: Empty set
- **WHEN** the user evaluates `none`
- **THEN** the value is shown as `{}`, with nothing to expand

### Requirement: Evaluation at the selected state
For a trace, expressions SHALL be evaluated at the selected state, the state of the first pane of the tabs. Temporal operators and primes SHALL be interpreted from that state along the whole trace, including its loop. For a single state, expressions are evaluated at that state.

#### Scenario: A mutable signature
- **WHEN** a trace where `Cur` is `{V1$0}`, then `{V2$0}`, then `{V3$0}`, looping back to state 1, is shown at state 0, and the user evaluates `Cur`
- **THEN** the panel shows `{V1$0}`

#### Scenario: Prime and after
- **WHEN** that trace is shown at state 0 and the user evaluates `Cur'` and then `after Cur = V2`
- **THEN** the panel shows `{V2$0}` and then `true`

#### Scenario: Along the loop
- **WHEN** that trace is shown at state 1 and the user evaluates `always Cur in V2 + V3`
- **THEN** the panel shows `true`, and at state 0 the same formula is `false`

### Requirement: Names available to expressions
Expressions SHALL be able to use every name of the model as it was analyzed for the shown result, as the desktop evaluator allows. This includes signatures, fields, predicates and functions, those of opened modules, the solution's atoms (such as `Node$0`) and its skolems (such as `$show_n`).

#### Scenario: Calling a predicate
- **WHEN** the model has `pred linked[a, b: Node] { b in a.succ }` and the user evaluates `linked[Node$0, Node$1]`
- **THEN** the panel shows whether that holds in the shown state

#### Scenario: A skolem
- **WHEN** the shown result has a skolem `$show_n` and the user evaluates `$show_n.succ`
- **THEN** the panel shows the successors of that atom

### Requirement: Evaluator field editing
The evaluator field SHALL highlight Alloy syntax. Completion SHALL offer the names available to expressions, and nothing else, without reporting the model's diagnostics in the field. Pressing Enter evaluates, and the field keeps a history of evaluated expressions that the user can go back through; coming back from the history shows again what was being typed. Adding or editing a watch SHALL offer the same completion.

#### Scenario: Completing a field name
- **WHEN** the shown model has a field `succ` and the user types `su` in the evaluator field and invokes completion
- **THEN** `succ` is offered

#### Scenario: Completing an atom
- **WHEN** the user types `Node$` and invokes completion
- **THEN** the solution's `Node` atoms are offered

#### Scenario: History
- **WHEN** the user evaluated `succ` and then `#Node`, and opens the field's history
- **THEN** both expressions are offered, the latest first

#### Scenario: Leaving the history
- **WHEN** the user has typed `#No`, goes up to `succ` in the history and then down again
- **THEN** the field shows `#No`

### Requirement: Watches list
The user SHALL be able to add the current expression of the field to a list of watches. Watches can also be added from the list itself, and each can be edited, removed and moved up or down. Each watch shows its expression and its value, or its error, as a single evaluation does.

#### Scenario: Adding a watch from the field
- **WHEN** the user types `Elected` in the field and chooses to add it to the watches
- **THEN** a watch `Elected` appears at the end of the list with its value in the selected state

#### Scenario: Editing a watch
- **WHEN** the user edits the watch `Elected` into `#Elected`
- **THEN** the watch shows `#Elected` and its value

#### Scenario: Removing and reordering
- **WHEN** the list has watches `a`, `b` and `c`, the user moves `c` up and removes `a`
- **THEN** the list shows `c` and then `b`

### Requirement: Watches follow the shown state
All watches SHALL be evaluated again whenever the selected state changes, by clicking a state in the trace path strip, and whenever a new solution is shown. A new solution can be shown from a command hint or from New Config, New Trace, New Init or New Fork. A watch whose value differs from its previous evaluation SHALL be highlighted as changed.

#### Scenario: Selecting another state
- **WHEN** a watch `Cur` shows `{V1$0}` at state 0 and the user clicks the circle of state 1 in the trace path strip
- **THEN** the watch shows `{V2$0}`, highlighted as changed

#### Scenario: New solution
- **WHEN** watches are shown and the user clicks New Fork and a new solution is shown
- **THEN** every watch shows its value in the new solution at its selected state

#### Scenario: Unchanged value
- **WHEN** a watch has the same value after another state is selected
- **THEN** it is not highlighted as changed

### Requirement: Watches kept per model file
Watches SHALL be kept per model file, in the project's workspace, across results and IDE sessions. Showing a result of a file SHALL show that file's watches. A watch that cannot be evaluated in a result, for example because it names something that no longer exists, SHALL stay in the list and show its error. The watches SHALL follow their file when it, or a directory above it, is renamed or moved, and SHALL be forgotten when it is deleted; a file that is missing when the IDE starts keeps its watches.

#### Scenario: Renaming the model file
- **WHEN** `ring.als` has watches and the user renames it to `election.als`
- **THEN** opening a result of `election.als` shows those watches

#### Scenario: Results of two files
- **WHEN** the user added watches while a result of `ring.als` was shown, and then opens a result of `graph.als`
- **THEN** the list shows the watches of `graph.als`, and opening a result of `ring.als` again shows its watches

#### Scenario: After restarting the IDE
- **WHEN** the user restarts the IDE and opens a result of `ring.als`
- **THEN** the watches of `ring.als` are shown and evaluated

#### Scenario: A watch that no longer applies
- **WHEN** a watch names a field that was removed from the model, and a new result of the model is shown
- **THEN** the watch stays in the list and shows that the name cannot be found

### Requirement: Evaluation of outdated results
An outdated result SHALL still be evaluated against the model as it was analyzed for that result, not the edited text. While the result is outdated, the panel SHALL say so.

#### Scenario: Editing the model
- **WHEN** an instance is shown, the user edits the file, and then evaluates `succ`
- **THEN** the panel shows the value of `succ` in the shown instance, and says that the result is outdated

### Requirement: Evaluation keeps the IDE responsive
Evaluating SHALL not block the IDE's user interface. While an evaluation runs, the panel SHALL show that it is evaluating. A newer state, solution or evaluation SHALL supersede the results of evaluations still running, so that the panel only shows values of the current state, and the evaluations still waiting SHALL not run. An evaluation that fails unexpectedly, for example by running out of stack on a huge expression, SHALL show an error instead of a value.

#### Scenario: Selecting during an evaluation
- **WHEN** watches are being evaluated at state 0 and the user clicks the circle of state 1 in the trace path strip
- **THEN** the watches end up showing their values at state 1, never those of state 0
