# alloy-command-execution Specification

## Purpose

Lets users execute Alloy `run` and `check` commands from the IDE through standard run gutter icons and run configurations, follow analyzer progress in the Run console, stop analyses, and open the resulting instances, counterexamples and unsat cores.

## Requirements

### Requirement: Run gutter markers
The editor SHALL show a run gutter icon next to every top-level `run` and `check` command in an Alloy file, offering to run that command and to create or edit a run configuration for it.

#### Scenario: Markers on commands
- **WHEN** a file contains `run show for 3` and `check noCycles for 5`
- **THEN** each of those lines has a run gutter icon, and no other line does

#### Scenario: Running from the gutter
- **WHEN** the user clicks the gutter icon of `check noCycles` and chooses Run
- **THEN** a temporary "Alloy command" run configuration for that command is created and started

### Requirement: Alloy command run configuration
The plugin SHALL provide an "Alloy command" run configuration type that stores the model file and the command to execute, and that can be created, edited, saved and re-run like other run configurations.

#### Scenario: Re-running a saved configuration
- **WHEN** the user saves a configuration for `run show` in `model.als` and later starts it from the run toolbar
- **THEN** the `run show` command in `model.als` is executed

#### Scenario: Command no longer exists
- **WHEN** the user starts a configuration whose command was removed or renamed in the file
- **THEN** the run fails before analysis begins with an error saying the command was not found in the file

#### Scenario: File no longer exists
- **WHEN** the user starts a configuration whose model file was deleted
- **THEN** the configuration reports an error naming the missing file and does not start

### Requirement: Execution uses current editor contents
Executing a command SHALL analyze the model as it currently appears in the editor, including unsaved changes.

#### Scenario: Unsaved edit
- **WHEN** the user changes a scope from `for 3` to `for 4` without saving and then runs the command
- **THEN** the analysis uses scope 4

### Requirement: Streaming analyzer output
The Run console SHALL show the analyzer's messages as they arrive, preserving bold emphasis, line breaks, and in-place updates of progress lines, and SHALL end the run when the analyzer reports completion.

#### Scenario: Progress and result
- **WHEN** a command is executed
- **THEN** the console shows the start message, progress lines that update in place rather than accumulating, the result, and the run is shown as finished when the analysis completes

#### Scenario: Warnings
- **WHEN** the analyzer reports warnings during a run
- **THEN** the warnings are printed in the console in warning style

#### Scenario: Model with errors
- **WHEN** the user runs a command in a file that has a syntax or type error
- **THEN** the console reports that there were errors, the run finishes, and the errors appear as editor diagnostics

### Requirement: Stopping an analysis
The Stop action of a running Alloy command SHALL ask the server to abort the analysis, and the run SHALL then finish with a "Stopped" message.

#### Scenario: Stopping a long analysis
- **WHEN** the user presses Stop while a command is being solved
- **THEN** the analysis is aborted, the console shows "Stopped", and the run is shown as terminated

### Requirement: One analysis at a time per project
While a command is executing in a project, the plugin SHALL refuse to start another Alloy command in the same project and SHALL tell the user why, rather than starting it silently or queueing it.

#### Scenario: Second run while busy
- **WHEN** a command is executing and the user starts another Alloy command in the same project
- **THEN** the second run does not start and the user is told that an Alloy analysis is already running

### Requirement: Opening instances and counterexamples
When an analysis produces an instance or counterexample, the console SHALL show it as a clickable link that opens it in the Alloy visualizer.

#### Scenario: Opening an instance
- **WHEN** `run show` finds an instance and the user clicks the "Instance" link in the console
- **THEN** the Alloy visualizer window opens showing that instance

#### Scenario: Opening a counterexample
- **WHEN** `check noCycles` finds a counterexample and the user clicks the "Counterexample" link
- **THEN** the Alloy visualizer window opens showing that counterexample

### Requirement: Opening unsat cores
When a check is valid or a run is unsatisfiable and an unsat core is available, the console SHALL show a clickable link that highlights the core in the editor.

#### Scenario: Showing a core
- **WHEN** core minimization is enabled, a check succeeds, and the user clicks the core link
- **THEN** the model locations in the core are highlighted in the editor as warnings marked as (possibly) part of the unsat core

### Requirement: Execution requires a running server
If the language server is not running when a command is started, the run SHALL fail with a message explaining that the Alloy language server is unavailable.

#### Scenario: Server down
- **WHEN** the language server has failed and the user clicks a run gutter icon
- **THEN** the Run console reports that the Alloy language server is unavailable and the run finishes without analysis
