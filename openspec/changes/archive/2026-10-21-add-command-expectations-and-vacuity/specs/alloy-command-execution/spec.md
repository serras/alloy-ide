# Spec Delta

## MODIFIED Requirements

### Requirement: Run gutter markers
The editor SHALL show a run gutter icon next to every top-level `run` and `check` command in an Alloy file, and clicking it SHALL execute that command directly. While the command is executing, its icon SHALL be a spinner, and clicking the spinner SHALL open a menu with a Stop item that stops that analysis. Once a command has a result, its icon SHALL show the verdict described in "Result verdict icons"; clicking any of these icons executes the command again.

#### Scenario: Markers on commands
- **WHEN** a file contains `run show for 3` and `check noCycles for 5`
- **THEN** each of those lines has a run gutter icon, and no other line does

#### Scenario: Running from the gutter
- **WHEN** the user clicks the gutter icon of `check noCycles`
- **THEN** `check noCycles` is executed and its line shows the running hint, and no run configuration is created

#### Scenario: Spinner while running
- **WHEN** `check noCycles` is executing
- **THEN** its gutter icon is a spinner, and it turns back into the run icon or the icon of its verdict when the analysis ends

#### Scenario: Stop menu on the spinner
- **WHEN** the user clicks the spinner gutter icon of the running `check noCycles`
- **THEN** a menu with a Stop item opens, and choosing Stop stops that analysis

#### Scenario: Running again from a verdict icon
- **WHEN** `check noCycles expect 0` shows the passed icon and the user clicks it
- **THEN** `check noCycles` is executed again, exactly as from the run icon

### Requirement: Inline command result
After a command is started, the editor SHALL show its state as an inlay hint at the end of the line where the command starts. The hint SHALL name the outcome: running, instance found, no instance found, counterexample found, no counterexample found, stopped before completion, or a failure. Only the most recent result of each command is shown. Commands that were never executed have no hint. A command declaring `expect` SHALL also say whether its outcome is as expected, and a vacuous pass SHALL be marked "(vacuous)".

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

#### Scenario: Expected outcome
- **WHEN** a command declares `expect 0` and the analyzer finds no counterexample
- **THEN** the hint also says that the outcome is as expected

#### Scenario: Vacuous pass
- **WHEN** `check safety` finds no counterexample and the vacuity check finds that the facts have no instance within its scope
- **THEN** the hint says "No counterexample found (vacuous)" with the time of the check

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
Hovering a result hint SHALL show a tooltip with everything the analyzer reported for that execution: the executed command, solver and scope settings, translation statistics, timings, expectation, unsat core size, warnings and failure details. Messages that the analyzer later replaced, such as progress lines, are not repeated. After a vacuity check, the tooltip SHALL also show its conclusion, what was solved (the facts, or the facts and the premise), its time and, for a vacuous pass, its unsat core size.

#### Scenario: Details of a finished run
- **WHEN** the user hovers the hint of `run show for 3` after an instance was found
- **THEN** the tooltip shows the executed command `Run show for 3`, the solver and bitwidth line, the variable and clause counts, and the result with its time

#### Scenario: Warnings
- **WHEN** the analyzer reports warnings during an execution
- **THEN** the tooltip of that command's hint lists the warnings

#### Scenario: Details while running
- **WHEN** the user hovers the hint of a running command
- **THEN** the tooltip shows the analyzer output received so far

#### Scenario: Details of a vacuous pass
- **WHEN** the user hovers the hint of a check that passed vacuously because no instance satisfies its premise
- **THEN** the tooltip says that the assertion's premise has no instance within the scope, so the check passed vacuously, and shows the premise check's time and core size

#### Scenario: Details of a non-vacuous pass
- **WHEN** the user hovers the hint of a check whose vacuity check found an instance
- **THEN** the tooltip says that the facts and the premise have an instance, and shows that check's time

### Requirement: Analysis progress
While a command executed from its gutter icon is executing, including its vacuity check, the IDE SHALL show a cancellable background progress indicator naming the command and showing the analyzer's latest progress step. No tool window is opened. Commands executed as a test suite report their progress in the test runner instead (see `alloy-test-runner`).

#### Scenario: Progress in the status bar
- **WHEN** the user runs `run big for 30 but 7 int`
- **THEN** the status bar shows a background task for that command whose text follows the analyzer's steps (such as generating CNF and solving), and it disappears when the analysis completes

#### Scenario: No tool window
- **WHEN** a command is executed from its gutter icon
- **THEN** no Run or other tool window is opened or brought to front

#### Scenario: Progress of the vacuity check
- **WHEN** a check found no counterexample and its vacuity check is running
- **THEN** the background task stays and its text says that vacuity is being checked

## ADDED Requirements

### Requirement: Result verdict icons
The gutter icon of a command that is not running SHALL reflect its latest result, current or outdated: *vacuous* for a check that passed vacuously; otherwise, for a command with `expect`, *passed* when the outcome matches it and *failed* when it does not or the execution failed; otherwise the run icon. Unknown vacuity counts as not vacuous.

#### Scenario: Expectation met
- **WHEN** `run show expect 1` finds an instance
- **THEN** its gutter icon is the passed icon

#### Scenario: Expectation not met
- **WHEN** `check safety expect 0` finds a counterexample
- **THEN** its gutter icon is the failed icon

#### Scenario: Failure with an expectation
- **WHEN** `run show expect 1` is executed in a model with errors
- **THEN** its gutter icon is the failed icon

#### Scenario: Vacuous check without expect
- **WHEN** `check safety` passes vacuously
- **THEN** its gutter icon is the vacuous icon, distinct from the run, passed and failed icons

#### Scenario: Vacuous check with expect
- **WHEN** `check safety expect 0` passes vacuously
- **THEN** its gutter icon is the vacuous icon, not the passed icon

#### Scenario: No expectation
- **WHEN** `run show` without `expect` finds an instance, or `check noCycles` without `expect` passes and is not vacuous
- **THEN** its gutter icon is the run icon, as before

#### Scenario: Stopped with an expectation
- **WHEN** `run show expect 1` is stopped before completion
- **THEN** its gutter icon is the run icon

#### Scenario: Outdated verdict
- **WHEN** `run show expect 1` shows the passed icon and the user edits the file
- **THEN** the icon stays the passed icon, and its tooltip says that the result is outdated

### Requirement: Vacuity check of passing checks
When the vacuity setting is on and a `check` ends with no counterexample, the same execution SHALL solve the model's facts with the check's scope. When the assertion is `A implies B` (possibly under `all` or `always`), it SHALL solve the facts with the premise instead (`A`, `some … | A` or `eventually A`). With no instance, the check passed vacuously; with an instance, it did not.

#### Scenario: Facts without instances
- **WHEN** the facts of a model contradict each other within the scope of `check safety`, so that it finds no counterexample
- **THEN** the vacuity check finds no instance of the facts, and the check passed vacuously because the facts have no instance

#### Scenario: Premise without instances
- **WHEN** `check liveness { fairness implies eventually some Elected } for exactly 1 Node` finds no counterexample, the facts have traces, but no trace satisfies `fairness`
- **THEN** the check passed vacuously because its premise has no instance

#### Scenario: Not vacuous
- **WHEN** `check noCycles` finds no counterexample and the facts have an instance within its scope
- **THEN** the check did not pass vacuously, and its gutter icon and hint are those of a normal pass

#### Scenario: Same scope
- **WHEN** `check safety for 3 but 20 steps` passed
- **THEN** the vacuity check uses scope 3 and up to 20 steps, with the same solver and settings as the check

#### Scenario: Setting off
- **WHEN** the vacuity setting is off and a check finds no counterexample
- **THEN** no vacuity check runs, and the result is shown as before

#### Scenario: Other outcomes
- **WHEN** a `check` finds a counterexample, or a `run` finds no instance
- **THEN** no vacuity check runs

### Requirement: Vacuity check is part of the execution
The vacuity check SHALL belong to its check's execution. The check's result is shown as soon as it is known, with "checking vacuity…", and the project's single analysis stays taken until the vacuity check ends. Stopping it, a failure, or an edit keep the check's own result, with vacuity unknown, and the result follows "Outdated results".

#### Scenario: Result shown before vacuity
- **WHEN** `check safety` finds no counterexample and its vacuity check is running
- **THEN** its hint says that no counterexample was found and that vacuity is being checked, and its gutter icon is the spinner

#### Scenario: Busy during the vacuity check
- **WHEN** the vacuity check of `check safety` is running and the user clicks the gutter icon of another command
- **THEN** that command does not start and the editor says that an Alloy analysis is already running

#### Scenario: Stopping the vacuity check
- **WHEN** the user stops the execution while its vacuity check is running
- **THEN** the vacuity check is aborted, the hint keeps "No counterexample found" with its time, and the tooltip says that vacuity was not determined because the check was stopped

#### Scenario: Vacuity check fails
- **WHEN** the vacuity check runs out of memory
- **THEN** the hint keeps the check's result, the gutter icon is that of a non-vacuous result, and the tooltip says that vacuity could not be determined and why

#### Scenario: Edit during the vacuity check
- **WHEN** the user edits the file while the vacuity check of `check safety` is running
- **THEN** the result, including the vacuity conclusion when it arrives, is shown as outdated
