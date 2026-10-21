# alloy-test-runner Specification

## Purpose
Lets users run every command of an Alloy file as a test suite in the IDE's test runner, so that a model's validation scenarios and checks can be re-run together after each change, with failures, timings and analyzer output listed per command.

## Requirements

### Requirement: Run all commands of a file
The plugin SHALL provide an *Alloy Commands* run configuration that executes every top-level `run` and `check` command of one Alloy file, one after another in source order, on the current editor contents of the file and the modules it opens. It SHALL be offered as "Run 'All commands in <file>'" in the context menu of an Alloy file in the editor and in the *Project* view.

#### Scenario: Running from the context menu
- **WHEN** the user right-clicks `a.als` in the *Project* view and chooses "Run 'All commands in a.als'"
- **THEN** a temporary *Alloy Commands* run configuration for `a.als` is created and run, and the Run tool window shows its test tree

#### Scenario: Source order
- **WHEN** `a.als` contains `run show`, `check noCycles` and `run other`, in this order
- **THEN** the commands are executed in that order, one at a time

#### Scenario: Saved configuration
- **WHEN** the user saves the temporary configuration and runs it later from the toolbar
- **THEN** it executes the commands of `a.als` as found at that time

### Requirement: One test per command
The test tree SHALL have one node per command, named like the command (such as `run show` or `check noCycles`), with the analyzer output of that execution as its output and the analysis time as its duration. A command SHALL fail when its expectation is not met, its execution fails, or it passes vacuously; otherwise it passes.

#### Scenario: Expectation met
- **WHEN** `run show expect 1` finds an instance
- **THEN** its node passed, and its output is the analyzer output shown in the hint's tooltip

#### Scenario: Expectation not met
- **WHEN** `check safety expect 0` finds a counterexample
- **THEN** its node failed with a message saying that a counterexample was found, contrary to the expectation

#### Scenario: Command without expectation
- **WHEN** `run show` without `expect` finds no instance
- **THEN** its node passed, and its output says that no instance was found

#### Scenario: Vacuous pass
- **WHEN** `check liveness` passes vacuously
- **THEN** its node failed with a message saying that it passed vacuously and whether the facts or the premise have no instance

#### Scenario: Failure
- **WHEN** the model has errors
- **THEN** every command's node failed with the message that the model has errors

### Requirement: Navigation from the test tree
Every node SHALL navigate to its command in the file, located by the command's label, or by its position among the commands when it has none.

#### Scenario: Jump to source
- **WHEN** the user double-clicks the node `check noCycles`
- **THEN** the editor opens `a.als` with the caret at `check noCycles`

### Requirement: Rerun failed commands
*Rerun Failed Tests* SHALL execute only the commands whose nodes failed in the previous run, in source order.

#### Scenario: Rerunning failures
- **WHEN** a run of `a.als` had `check safety` failed and the other commands passed, and the user clicks *Rerun Failed Tests*
- **THEN** only `check safety` is executed, and the tree shows only its node

### Requirement: Results are shared with the editor
Each command executed by the suite SHALL record its result exactly as a gutter execution does: its hint, tooltip, verdict icon and instance link are updated. Suite runs SHALL NOT show status-bar progress.

#### Scenario: Hints after a suite
- **WHEN** a suite run of `a.als` finishes while `a.als` is open in the editor
- **THEN** every command line shows the hint and the verdict icon of its result, and clicking the hint of `run show` opens its instance

### Requirement: Suite takes the project's analysis
A suite run SHALL hold the project's single analysis from its start until it ends. It SHALL NOT start while another analysis runs in the project, and gutter executions and instance enumerations SHALL be refused while it runs.

#### Scenario: Suite while busy
- **WHEN** a command is executing from the gutter and the user runs *All commands in a.als*
- **THEN** the suite does not execute any command, and the Run tool window says that an Alloy analysis is already running

#### Scenario: Gutter while the suite runs
- **WHEN** a suite run is executing and the user clicks the gutter icon of a command
- **THEN** the command does not start and the editor says that an Alloy analysis is already running

### Requirement: Stopping a suite
Stopping the run from the Run tool window SHALL stop the command being executed, as "Stopping a running analysis" does, and SHALL not execute the remaining commands. The stopped command and the remaining ones SHALL be shown as not completed.

#### Scenario: Stop in the middle
- **WHEN** the user clicks Stop while the second of three commands is being solved
- **THEN** its analysis is aborted, its hint says "Stopped before completion", the third command is not executed, and neither node is shown as passed
