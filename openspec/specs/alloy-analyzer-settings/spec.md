# alloy-analyzer-settings Specification

## Purpose

Lets users configure the Alloy analyzer options from the IDE settings, keeps those options separate from the Alloy desktop application's preferences, lists only the solvers that can actually run, and applies changes to the running language server.

## Requirements

### Requirement: Analyzer settings page
The plugin SHALL provide an IDE-level settings page (Settings | Languages & Frameworks | Alloy) with these options: solver, skolem depth, prevent overflows, unsat core minimization, unsat core granularity, non-fatal warnings, record Kodkod input/output, and infer partial instance.

#### Scenario: Opening the page
- **WHEN** the user opens Settings | Languages & Frameworks | Alloy
- **THEN** all eight options are shown with their current values

#### Scenario: Settings are application-wide
- **WHEN** the user changes the skolem depth while one project is open and then opens another project
- **THEN** the other project uses the same skolem depth

### Requirement: Option values and defaults
Each option SHALL accept exactly the values the Alloy 6.2.0 analyzer accepts and SHALL default to the analyzer's defaults: solver SAT4J, skolem depth 1 (choices 0–4), prevent overflows on, core minimization "Fast" (choices Slow/Medium/Fast), core granularity "Top-level" (choices Top-level/Flatten once/Flatten twice/Expand quantifiers), non-fatal warnings off, record Kodkod off, infer partial instance off.

#### Scenario: Fresh installation
- **WHEN** the plugin is used for the first time
- **THEN** the settings page shows the default values listed above and analyses use them

#### Scenario: Restricted choices
- **WHEN** the user opens the skolem depth selector
- **THEN** only the values 0, 1, 2, 3 and 4 can be chosen

### Requirement: Settings drive analysis
Every command execution SHALL use the values currently saved on the settings page.

#### Scenario: Changing the solver
- **WHEN** the user selects a different available solver, applies the settings, and runs a command
- **THEN** the analyzer output reports that the selected solver was used

### Requirement: Isolation from the Alloy desktop application
The plugin SHALL neither read nor write the Alloy desktop application's stored preferences.

#### Scenario: Desktop preferences are not imported
- **WHEN** the user has set skolem depth 3 in the Alloy desktop application and the IDE settings still have default values
- **THEN** analyses in the IDE use skolem depth 1

#### Scenario: Desktop preferences are not changed
- **WHEN** the user changes any option in the IDE settings and runs commands
- **THEN** the Alloy desktop application's stored preferences are unchanged

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

### Requirement: Settings changes outdate results
Applying changed analyzer settings SHALL mark every existing command result in every open project as outdated, in the same way as an edit of the file does.

#### Scenario: Results after a settings change
- **WHEN** `run show` has a current result and the user changes the skolem depth and applies
- **THEN** the hint of `run show` is shown as outdated

#### Scenario: Apply without changes
- **WHEN** the user clicks OK in the settings page without changing any option
- **THEN** current results stay current
