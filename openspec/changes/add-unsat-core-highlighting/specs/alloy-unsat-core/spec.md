# Spec Delta

## Purpose

Explains why a `run` found no instance or a `check` found no counterexample, by finding a minimal set of the command's formulas that is unsatisfiable on its own and highlighting it in the editor.

## ADDED Requirements

### Requirement: Computing the core from the result hint
The "No instance found" and "No counterexample found" hints SHALL be clickable. Clicking a current one SHALL compute the unsat core of that command with the configured solver, inside the IDE, and then highlight it.

#### Scenario: Clicking a no-instance hint
- **WHEN** `run contradiction` found no instance and the user clicks its hint
- **THEN** the unsat core of `run contradiction` is computed and highlighted in the editor

#### Scenario: Clicking a no-counterexample hint
- **WHEN** `check noCycles` found no counterexample and the user clicks its hint
- **THEN** the unsat core of `check noCycles`, which includes the negated assertion when it takes part in the contradiction, is computed and highlighted

#### Scenario: Works with the pure-Java solver
- **WHEN** the configured solver is SAT4J and the user clicks a no-instance hint
- **THEN** a core is computed, without a native library or a separate process

### Requirement: Core is a minimal set of top-level formulas
The core SHALL be a set of the command's top-level formulas that is unsatisfiable within the command's scopes, such that removing any one of them makes the rest satisfiable. The top-level formulas are the conjuncts of the facts, the signature facts and the command's body; for a `check`, the negated assertion is one formula.

#### Scenario: Two contradicting facts
- **WHEN** a model has `fact f1 { some A }`, `fact f2 { no A }` and `fact f3 { all a: A | a in A }`, and `run {}` found no instance
- **THEN** the core is the bodies of `f1` and `f2`, and `f3` is not in it

#### Scenario: Contradiction inside one fact
- **WHEN** a fact is `fact { some A and no A and some B }`, and `run {}` found no instance
- **THEN** the core is `some A` and `no A`, and `some B` is not in it

#### Scenario: Valid assertion
- **WHEN** `check a { no A }` with `fact { no A }` found no counterexample
- **THEN** the core is the fact's body and the assertion

### Requirement: Core computation is an analysis
Computing a core SHALL behave like executing a command: it uses the project's single analysis slot, shows a cancellable background progress indicator naming the command and the formula being tried, and can be stopped from the hint, from the gutter spinner or from the progress indicator. While it runs, the hint keeps its outcome and says that the core is being computed.

#### Scenario: Progress while computing
- **WHEN** the core of `run contradiction` is being computed
- **THEN** its hint says "No instance found · computing core…", its gutter icon is a spinner, and the status bar shows a task naming `run contradiction`

#### Scenario: Another analysis is running
- **WHEN** a command is executing and the user clicks the no-instance hint of another command in the project
- **THEN** no core is computed, and the editor shows a message saying that an Alloy analysis is already running

#### Scenario: Stopping the computation
- **WHEN** the user clicks the hint of a command whose core is being computed
- **THEN** the computation stops, nothing is highlighted, and the hint says "No instance found" again

### Requirement: Highlighting the core
Every formula of the core SHALL be highlighted with the "Unsat core" color in every file it appears in, including opened project modules and bundled library modules, with a mark in the error stripe. Files opened after the core was computed SHALL show their highlights too. Only one core is shown in a project at a time.

#### Scenario: Core in the command's file
- **WHEN** the core of `run {}` is the bodies of `f1` and `f2`
- **THEN** the text of both bodies is highlighted and the error stripe has a mark for each

#### Scenario: Core in an opened module
- **WHEN** `b.als` opens `a.als`, and the core of a command in `b.als` includes a fact of `a.als`
- **THEN** that fact is highlighted in `a.als`, also if `a.als` is opened only after the core was computed

#### Scenario: Showing another core
- **WHEN** a core is shown and the user computes the core of another command
- **THEN** only the new core is highlighted

### Requirement: Core details on the hint
After a core was computed, the hint SHALL show its size, and the hint's tooltip SHALL list every core formula with its file and line, in source order.

#### Scenario: Core suffix
- **WHEN** the core of `run contradiction` has two formulas
- **THEN** the hint says "No instance found · core: 2 formulas", and the tooltip lists both formulas with their file and line

### Requirement: Clearing the core
The core highlights SHALL be removed from every editor when the user presses Esc in an editor showing them, edits any Alloy file of the project, applies changed analyzer settings, or executes any command. Clicking the hint of a current result whose core was computed SHALL show that core again without recomputing it.

#### Scenario: Esc
- **WHEN** a core is highlighted and the user presses Esc in the editor
- **THEN** the highlights disappear from every editor

#### Scenario: Editing
- **WHEN** a core is highlighted and the user types in any Alloy file of the project
- **THEN** the highlights disappear

#### Scenario: Running a command
- **WHEN** a core is highlighted and the user runs any command
- **THEN** the highlights disappear

#### Scenario: Showing it again
- **WHEN** the user cleared a core with Esc and clicks the same hint, which is still current
- **THEN** the same core is highlighted again, without a new computation

### Requirement: Contradiction outside the formulas
When the command has no instance even without any of its top-level formulas, the plugin SHALL NOT highlight anything. It SHALL tell the user that the contradiction comes from the declarations (such as field multiplicities and `one` signatures) or from the scopes, and SHALL say so in the hint's tooltip.

#### Scenario: Scope too small
- **WHEN** a model has `sig A { f: one B }` and `sig B {}`, `run {} for 3 but exactly 1 A, 0 B` found no instance, and the user clicks the hint
- **THEN** nothing is highlighted, a message says that no formula is needed for the contradiction and that it comes from the declarations or the scopes, and the tooltip says the same

### Requirement: Outdated results have no core
Clicking an outdated "No instance found" or "No counterexample found" hint SHALL NOT compute a core. It SHALL show a message asking the user to execute the command again.

#### Scenario: Clicking after an edit
- **WHEN** `run contradiction` found no instance, the user edits the file and clicks the outdated hint
- **THEN** no core is computed, and a message says to run `run contradiction` again

### Requirement: Customizable core color
The Alloy color settings page SHALL have an "Unsat core" entry. Its default SHALL be visible in both light and dark schemes, and its demo text SHALL show it.

#### Scenario: Changing the color
- **WHEN** the user changes "Unsat core" in Settings | Editor | Color Scheme | Alloy
- **THEN** the demo text and the highlighted cores in open editors use the new color
