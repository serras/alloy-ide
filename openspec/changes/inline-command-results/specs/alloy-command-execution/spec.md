# Spec Delta

## ADDED Requirements

### Requirement: Inline command result
After a command is started, the editor SHALL show its state as an inlay hint at the end of the line where the command starts. The hint SHALL name the outcome: running, instance found, no instance found, counterexample found, no counterexample found, stopped, or a failure. Only the most recent result of each command is shown. Commands that were never executed have no hint.

#### Scenario: Instance found
- **WHEN** the user runs `run show for 3` and the analyzer finds an instance
- **THEN** the end of the `run show for 3` line shows a hint saying an instance was found

#### Scenario: Counterexample found
- **WHEN** the user runs `check noCycles for 3` and the analyzer finds a counterexample
- **THEN** the end of the `check noCycles for 3` line shows a hint saying a counterexample was found

#### Scenario: Nothing found
- **WHEN** a `check` finds no counterexample, or a `run` finds no instance
- **THEN** the hint says that no counterexample, or no instance, was found

#### Scenario: Running
- **WHEN** a command has been started and the analysis has not completed
- **THEN** its hint says that it is running

#### Scenario: Unexpected outcome
- **WHEN** a command declares `expect 1` and the analyzer finds no instance
- **THEN** the hint also says that the outcome is contrary to the expectation

#### Scenario: Command spanning several lines
- **WHEN** a command written over several lines is executed
- **THEN** its hint is at the end of the first line of the command

#### Scenario: Other commands untouched
- **WHEN** the file has three commands and only `run show` was executed
- **THEN** only the `run show` line has a hint

#### Scenario: Re-running replaces the result
- **WHEN** a command that already has a result is executed again
- **THEN** its hint shows the running state and then the new result, never the old one next to it

#### Scenario: Results last for the IDE session
- **WHEN** the user closes the file and opens it again in the same session
- **THEN** the hints of its executed commands are shown again

### Requirement: Result details tooltip
Hovering a result hint SHALL show a tooltip with everything the analyzer reported for that execution: the executed command, solver and scope settings, translation statistics, timings, expectation, unsat core size, warnings and failure details. Messages that the analyzer later replaced, such as progress lines, are not repeated.

#### Scenario: Details of a finished run
- **WHEN** the user hovers the hint of `run show for 3` after an instance was found
- **THEN** the tooltip shows the executed command `Run show for 3`, the solver and bitwidth line, the variable and clause counts, and the result with its time

#### Scenario: Warnings
- **WHEN** the analyzer reports warnings during an execution
- **THEN** the tooltip of that command's hint lists the warnings

#### Scenario: Details while running
- **WHEN** the user hovers the hint of a running command
- **THEN** the tooltip shows the analyzer output received so far

### Requirement: Outdated results
When an Alloy file is edited after one of its commands was executed, every result hint in that file SHALL be marked as outdated: it is shown dimmed, it is labelled as outdated, and its tooltip says that the file changed after the analysis. A result becomes current again only when its command is executed again.

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

#### Scenario: Outdated instance can still be opened
- **WHEN** the user clicks the outdated hint of a command that found an instance
- **THEN** the visualizer opens the instance that was found by that earlier analysis

### Requirement: Analysis progress
While a command is executing, the IDE SHALL show a cancellable background progress indicator naming the command and showing the analyzer's latest progress step. No tool window is opened.

#### Scenario: Progress in the status bar
- **WHEN** the user runs `run big for 30 but 7 int`
- **THEN** the status bar shows a background task for that command whose text follows the analyzer's steps (such as generating CNF and solving), and it disappears when the analysis completes

#### Scenario: No tool window
- **WHEN** a command is executed
- **THEN** no Run or other tool window is opened or brought to front

### Requirement: Failures reported inline
When an execution cannot produce a result, the hint SHALL show a short failure state, and its tooltip SHALL explain the cause. This covers a model with errors, a command the analyzer does not list, and an analyzer that does not answer in time.

#### Scenario: Model with errors
- **WHEN** the user runs a command in a file that has a syntax or type error
- **THEN** the hint says that the model has errors, the errors appear as editor diagnostics, and no analysis is started

#### Scenario: Command unknown to the analyzer
- **WHEN** a command shown with a gutter icon is not among the commands that the analyzer lists for the file
- **THEN** the hint shows a failure and its tooltip says that the command was not found

## MODIFIED Requirements

### Requirement: Run gutter markers
The editor SHALL show a run gutter icon next to every top-level `run` and `check` command in an Alloy file, and clicking it SHALL execute that command directly.

#### Scenario: Markers on commands
- **WHEN** a file contains `run show for 3` and `check noCycles for 5`
- **THEN** each of those lines has a run gutter icon, and no other line does

#### Scenario: Running from the gutter
- **WHEN** the user clicks the gutter icon of `check noCycles`
- **THEN** `check noCycles` is executed and its line shows the running hint, and no run configuration is created

### Requirement: Stopping an analysis
The user SHALL be able to stop a running analysis by clicking its running hint or by cancelling its background progress indicator. The server is asked to abort the analysis, and the hint SHALL then say that the analysis was stopped.

#### Scenario: Stopping a long analysis
- **WHEN** the user clicks the running hint of a command that is being solved
- **THEN** the analysis is aborted, the progress indicator disappears, and the hint says that the analysis was stopped

#### Scenario: Cancelling the progress indicator
- **WHEN** the user cancels the background progress indicator of a running command
- **THEN** the analysis is aborted and the hint says that the analysis was stopped

#### Scenario: Server does not confirm
- **WHEN** the user stops an analysis and the server does not confirm within a few seconds
- **THEN** the hint says that the analysis was stopped, and its tooltip says that the server did not confirm

### Requirement: One analysis at a time per project
While a command is executing in a project, the plugin SHALL refuse to start another Alloy command in the same project and SHALL tell the user why, rather than starting it silently or queueing it.

#### Scenario: Second run while busy
- **WHEN** a command is executing and the user clicks the gutter icon of another command in the same project
- **THEN** the second command does not start, its hint does not change, and the editor shows a message saying that an Alloy analysis is already running

### Requirement: Opening instances and counterexamples
When an analysis produces an instance or counterexample, its result hint SHALL be clickable and clicking it SHALL open the instance or counterexample in the Alloy visualizer.

#### Scenario: Opening an instance
- **WHEN** `run show` found an instance and the user clicks its hint
- **THEN** the Alloy visualizer window opens showing that instance

#### Scenario: Opening a counterexample
- **WHEN** `check noCycles` found a counterexample and the user clicks its hint
- **THEN** the Alloy visualizer window opens showing that counterexample

### Requirement: Opening unsat cores
When a check is valid or a run is unsatisfiable and an unsat core is available, its result hint SHALL be clickable and clicking it SHALL highlight the core in the editor. A result without a core is not clickable.

#### Scenario: Showing a core
- **WHEN** core minimization is enabled, a check finds no counterexample, and the user clicks its hint
- **THEN** the model locations in the core are highlighted in the editor as warnings marked as (possibly) part of the unsat core

#### Scenario: No core available
- **WHEN** a check finds no counterexample and the analyzer reports that no unsat core is available
- **THEN** the hint is not clickable, and its tooltip says that no core is available

### Requirement: Execution requires a running server
If the language server is not running when a command is started, the execution SHALL fail, and its hint SHALL say that the Alloy language server is unavailable.

#### Scenario: Server down
- **WHEN** the language server has failed and the user clicks a run gutter icon
- **THEN** the hint of that command says that the Alloy language server is unavailable, and no analysis runs

#### Scenario: Server stops during a run
- **WHEN** the language server process ends while a command is running
- **THEN** the hint of that command says that the Alloy language server is unavailable, and the progress indicator disappears

## REMOVED Requirements

### Requirement: Alloy command run configuration
**Reason**: Commands are executed and shown inline from the editor, so a run configuration (and the Run tool window it implies) is no longer the way to run them.
**Migration**: Click the gutter icon of the command in the editor. Saved "Alloy command" configurations show up as unknown configurations and can be deleted.

### Requirement: Streaming analyzer output
**Reason**: The Run console is removed. The analyzer's output is summarized in the result hint and given in full in its tooltip, and progress is shown in the status bar.
**Migration**: Read the result from the hint at the end of the command's line, and hover it for the full analyzer output. See "Inline command result", "Result details tooltip" and "Analysis progress".
