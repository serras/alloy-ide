# Spec Delta

## MODIFIED Requirements

### Requirement: Analyzer settings page
The plugin SHALL provide an IDE-level settings page (Settings | Languages & Frameworks | Alloy) with these options: solver, skolem depth, prevent overflows, unsat core minimization, unsat core granularity, non-fatal warnings, record Kodkod input/output, infer partial instance, and check vacuity of passing checks.

#### Scenario: Opening the page
- **WHEN** the user opens Settings | Languages & Frameworks | Alloy
- **THEN** all nine options are shown with their current values

#### Scenario: Settings are application-wide
- **WHEN** the user changes the skolem depth while one project is open and then opens another project
- **THEN** the other project uses the same skolem depth

### Requirement: Option values and defaults
Each analyzer option SHALL accept exactly the values the Alloy 6.2.0 analyzer accepts and SHALL default to the analyzer's defaults: solver SAT4J, skolem depth 1 (choices 0–4), prevent overflows on, core minimization "Fast" (choices Slow/Medium/Fast), core granularity "Top-level" (choices Top-level/Flatten once/Flatten twice/Expand quantifiers), non-fatal warnings off, record Kodkod off, infer partial instance off. The plugin's own option, check vacuity of passing checks, SHALL be on/off and default to on.

#### Scenario: Fresh installation
- **WHEN** the plugin is used for the first time
- **THEN** the settings page shows the default values listed above, including check vacuity of passing checks turned on, and analyses use them

#### Scenario: Restricted choices
- **WHEN** the user opens the skolem depth selector
- **THEN** only the values 0, 1, 2, 3 and 4 can be chosen

#### Scenario: Turning vacuity checks off
- **WHEN** the user turns off check vacuity of passing checks, applies, and runs a check that finds no counterexample
- **THEN** no vacuity check runs for it
