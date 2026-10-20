# Spec Delta

## MODIFIED Requirements

### Requirement: Run gutter markers
The editor SHALL show a run gutter icon next to every top-level `run` and `check` command in an Alloy file, and clicking it SHALL execute that command directly. While the command is executing, its icon SHALL be a spinner, and clicking the spinner SHALL open a menu with a Stop item that stops that analysis.

#### Scenario: Markers on commands
- **WHEN** a file contains `run show for 3` and `check noCycles for 5`
- **THEN** each of those lines has a run gutter icon, and no other line does

#### Scenario: Running from the gutter
- **WHEN** the user clicks the gutter icon of `check noCycles`
- **THEN** `check noCycles` is executed and its line shows the running hint, and no run configuration is created

#### Scenario: Spinner while running
- **WHEN** `check noCycles` is executing
- **THEN** its gutter icon is a spinner, and it turns back into the run icon when the analysis ends

#### Scenario: Stop menu on the spinner
- **WHEN** the user clicks the spinner gutter icon of the running `check noCycles`
- **THEN** a menu with a Stop item opens, and choosing Stop stops that analysis

### Requirement: Opening instances and counterexamples
When an analysis produces an instance or counterexample, its result hint SHALL be clickable, and clicking it SHALL show the instance or counterexample in the Alloy Instance tool window.

#### Scenario: Opening an instance
- **WHEN** `run show` found an instance and the user clicks its hint
- **THEN** the Alloy Instance tool window shows that instance, and its header names `run show`

#### Scenario: Opening a counterexample
- **WHEN** `check noCycles` found a counterexample and the user clicks its hint
- **THEN** the Alloy Instance tool window shows that counterexample, and its header names `check noCycles`

### Requirement: Inline command result
After a command is started, the editor SHALL show its state as an inlay hint at the end of the line where the command starts. The hint SHALL name the outcome: running, instance found, no instance found, counterexample found, no counterexample found, stopped before completion, or a failure. Only the most recent result of each command is shown. Commands that were never executed have no hint.

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

#### Scenario: Stopped
- **WHEN** a command was stopped before the analysis completed
- **THEN** its hint says "Stopped before completion"

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
- **THEN** the Alloy Instance tool window shows the instance that was found by that earlier analysis, with its header marked as outdated

### Requirement: Failures reported inline
When an execution cannot produce a result, the hint SHALL show a short failure state, and its tooltip SHALL explain the cause. Causes include:
- a model with errors;
- a command that the analyzer does not find in the model;
- an analysis that runs out of memory or stack;
- an unexpected analyzer error.

#### Scenario: Model with errors
- **WHEN** the user runs a command in a file that has a syntax or type error
- **THEN** the hint says that the model has errors, the errors appear as editor diagnostics, and no analysis is started

#### Scenario: Command unknown to the analyzer
- **WHEN** a command shown with a gutter icon is not among the commands that the analyzer finds in the file
- **THEN** the hint shows a failure and its tooltip says that the command was not found

#### Scenario: Analysis exhausts resources
- **WHEN** an analysis runs out of memory or stack
- **THEN** the analysis ends, the hint shows a failure, its tooltip says which resource was exhausted, and the IDE keeps running

#### Scenario: Unexpected analyzer error
- **WHEN** the analyzer throws an unexpected exception during an analysis
- **THEN** the hint shows a failure and its tooltip contains the exception's message

## ADDED Requirements

### Requirement: Stopping a running analysis
The user SHALL be able to stop a running analysis from the Stop item of its spinner gutter icon, by clicking its running hint, or by cancelling its background progress indicator. The analysis is aborted inside the IDE, and the hint SHALL then say "Stopped before completion".

#### Scenario: Stopping from the gutter
- **WHEN** the user chooses Stop in the spinner menu of a command that is being solved
- **THEN** the analysis is aborted, the progress indicator disappears, the gutter icon turns back into the run icon, and the hint says "Stopped before completion"

#### Scenario: Stopping from the hint
- **WHEN** the user clicks the running hint of a command that is being solved
- **THEN** the analysis is aborted and the hint says "Stopped before completion"

#### Scenario: Cancelling the progress indicator
- **WHEN** the user cancels the background progress indicator of a running command
- **THEN** the analysis is aborted and the hint says "Stopped before completion"

#### Scenario: Stop during translation
- **WHEN** the user stops an analysis while the analyzer is still translating the model and cannot be interrupted immediately
- **THEN** the hint says that the analysis is stopping until the analyzer reaches its next interruption point, and then says "Stopped before completion"

#### Scenario: Analysis output before stopping
- **WHEN** the user hovers the hint of a stopped analysis
- **THEN** the tooltip shows the analyzer output received before it was stopped

### Requirement: Analysis runs inside the IDE
Commands SHALL be analyzed by the Alloy analyzer bundled with the plugin, inside the IDE process, on a background thread. They SHALL NOT use a separate process or any Java installation other than the IDE's own runtime. External solver executables chosen in the settings are the exception.

#### Scenario: No system Java needed
- **WHEN** the user has no Java installation other than the IDE's own runtime and runs a command with SAT4J
- **THEN** the analysis completes

#### Scenario: Editor stays responsive
- **WHEN** a long analysis is running
- **THEN** the user can keep editing and navigating Alloy files without delays

#### Scenario: Project closed during an analysis
- **WHEN** the project is closed while one of its commands is executing
- **THEN** the analysis is stopped, and any external solver process it started is terminated

## REMOVED Requirements

### Requirement: Stopping an analysis
**Reason**: Stopping no longer asks a server to abort and wait for its confirmation. The analysis is aborted inside the IDE, and the outcome is now worded "Stopped before completion".
**Migration**: Replaced by "Stopping a running analysis", which keeps stopping from the hint and from the progress indicator and adds the gutter Stop menu.

### Requirement: Execution requires a running server
**Reason**: There is no language server any more. The analyzer runs inside the IDE, so it is always available.
**Migration**: None needed. Failures that used to show "Server unavailable" now show the specific cause through "Failures reported inline".
