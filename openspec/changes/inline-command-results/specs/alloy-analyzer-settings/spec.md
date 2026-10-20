# Spec Delta

## ADDED Requirements

### Requirement: Settings changes outdate results
Applying changed analyzer settings SHALL mark every existing command result in every open project as outdated, in the same way as an edit of the file does.

#### Scenario: Results after a settings change
- **WHEN** `run show` has a current result and the user changes the skolem depth and applies
- **THEN** the hint of `run show` is shown as outdated

#### Scenario: Apply without changes
- **WHEN** the user clicks OK in the settings page without changing any option
- **THEN** current results stay current

## MODIFIED Requirements

### Requirement: Available solvers only
The solver selector SHALL list exactly the solvers that the bundled analyzer reports as present on this machine: the pure-Java SAT4J variants always, plus any solver whose required executable is found on the `PATH`.

#### Scenario: Only pure-Java solvers available
- **WHEN** no external solver executable is on the `PATH`
- **THEN** the solver selector lists only the SAT4J variants

#### Scenario: Solver needing a native library
- **WHEN** a solver known to the analyzer needs a native library that the bundled distribution does not ship
- **THEN** that solver is not listed

#### Scenario: External solver on PATH
- **WHEN** a solver known to the analyzer is available because its executable is on the `PATH`
- **THEN** that solver is also listed in the selector

#### Scenario: Previously chosen solver disappears
- **WHEN** the saved solver is no longer available
- **THEN** the settings page shows that the saved solver is unavailable, and analyses fall back to SAT4J and include a warning about it in the tooltip of their result hint

### Requirement: Applying changes restarts the server
When the user applies changed analyzer settings, the plugin SHALL restart the language server of every open project that has one running, so that subsequent analyses use the new values.

#### Scenario: Apply with changes
- **WHEN** the user changes an option and clicks Apply or OK
- **THEN** each running Alloy language server is restarted and open Alloy files get their diagnostics again

#### Scenario: Apply without changes
- **WHEN** the user clicks OK without changing any option
- **THEN** no language server is restarted

#### Scenario: Apply during an analysis
- **WHEN** settings are applied while a command is executing
- **THEN** that execution ends, its hint says that it was interrupted by a settings change, and the server restarts
