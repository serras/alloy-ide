# Spec Delta

## MODIFIED Requirements

### Requirement: Opening instances and counterexamples
When an analysis produces an instance or counterexample, its result hint SHALL be clickable, and clicking it SHALL show the instance or counterexample in the instance preview of the editor where the hint was clicked.

#### Scenario: Opening an instance
- **WHEN** `run show` found an instance and the user clicks its hint
- **THEN** the instance preview of that editor shows that instance, and its header names `run show`

#### Scenario: Opening a counterexample
- **WHEN** `check noCycles` found a counterexample and the user clicks its hint
- **THEN** the instance preview of that editor shows that counterexample, and its header names `check noCycles`

#### Scenario: Only recent instances are kept
- **WHEN** more than ten commands of the project found an instance or counterexample in this session
- **THEN** only the ten most recent ones can still be opened from their hints; the older hints keep their outcome and tooltip, and their command can be executed again to open its instance

### Requirement: Outdated results
When an Alloy file, or a project module that the analysis of one of its commands read, is edited after that command was executed, its result hint SHALL be marked as outdated: it is shown dimmed, it is labelled as outdated, and its tooltip says that the file or a module it opens changed after the analysis. A result becomes current again only when its command is executed again.

#### Scenario: Edit after a run
- **WHEN** `run show` has a result and the user types anywhere in the file
- **THEN** the hints of all commands in the file, including `run show`, are shown as outdated

#### Scenario: Edit during a run
- **WHEN** the user edits the file while `run show` is running
- **THEN** the result of `run show` is shown as outdated as soon as it arrives

#### Scenario: Re-running clears the mark
- **WHEN** the user executes an outdated command again
- **THEN** its new result is shown as current, while the other outdated hints in the file stay outdated

#### Scenario: Undoing the edit
- **WHEN** the user edits the file and then undoes the edit
- **THEN** the results stay outdated

#### Scenario: Edit of an opened module
- **WHEN** `run show` in `main.als`, which opens the project module `util/graph.als`, has a result and the user edits `util/graph.als`
- **THEN** the hint of `run show` is shown as outdated, while editing a module that `main.als` does not open leaves it current

#### Scenario: Outdated instance can still be opened
- **WHEN** the user clicks the outdated hint of a command that found an instance
- **THEN** the instance preview of that editor shows the instance that was found by that earlier analysis, with its header marked as outdated
