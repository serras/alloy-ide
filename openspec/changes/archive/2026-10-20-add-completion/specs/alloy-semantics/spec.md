# Spec Delta

## MODIFIED Requirements

### Requirement: Go to declaration
Ctrl/Cmd-click and Go to Declaration on an Alloy reference SHALL navigate to its declaration. This includes declarations in other project files and in the Alloy library modules bundled with the analyzer. On the path of an `open` declaration, they SHALL open the module that the analyzer loads for it. Bundled modules open read-only, in Reader mode when the IDE's Reader mode applies to library sources.

#### Scenario: Navigating to a signature
- **WHEN** the user Ctrl/Cmd-clicks a signature name used in a predicate
- **THEN** the editor moves to that signature's declaration

#### Scenario: Navigating across files
- **WHEN** `b.als` contains `open a` and the user Ctrl/Cmd-clicks a use of predicate `p` declared in `a.als`
- **THEN** `a.als` opens at the declaration of `p`

#### Scenario: Navigating into a library module
- **WHEN** a file contains `open util/ordering[S]` and the user Ctrl/Cmd-clicks a use of `first`
- **THEN** the bundled `util/ordering` module opens read-only at the declaration of `first`

#### Scenario: Navigating to a local variable
- **WHEN** the user Ctrl/Cmd-clicks the `x` in `all x: A | some x.f`
- **THEN** the caret moves to the binder `x` of that quantifier

#### Scenario: Navigating from an open path to a project module
- **WHEN** `b.als` contains `open a` and the user Ctrl/Cmd-clicks `a`
- **THEN** `a.als` opens

#### Scenario: Navigating from an open path to a library module
- **WHEN** a file contains `open util/ordering[S]` and the user Ctrl/Cmd-clicks `util/ordering`
- **THEN** the bundled `util/ordering` module opens read-only

#### Scenario: Library module in Reader mode
- **WHEN** Reader mode is enabled for library sources and the user navigates into a bundled module
- **THEN** the module opens in Reader mode

#### Scenario: Unresolved open path
- **WHEN** the path of an `open` declaration names a module that neither the project nor the bundled library has
- **THEN** Ctrl/Cmd-click on it navigates nowhere

### Requirement: Go to Symbol
Go to Symbol and Search Everywhere SHALL find Alloy signatures, fields, predicates, functions, assertions, named facts and named commands declared in the project's Alloy files. When the search includes non-project items, they SHALL also find the declarations of the bundled library modules.

#### Scenario: Searching for a symbol
- **WHEN** the user searches for a predicate name in Go to Symbol
- **THEN** the predicate declared in a project `.als` file appears, and selecting it opens that file at the declaration

#### Scenario: Searching for a library symbol
- **WHEN** the user searches for `first` in Go to Symbol with non-project items included
- **THEN** the `first` of the bundled `util/ordering` module appears, and selecting it opens that module at the declaration

#### Scenario: Project-only search
- **WHEN** the user searches for `first` in Go to Symbol restricted to project files, and no project file declares `first`
- **THEN** no result from the bundled modules appears

## ADDED Requirements

### Requirement: Bundled modules as a library
The Alloy library modules bundled with the analyzer SHALL be available to every project as a read-only library named after the bundled Alloy version. The library SHALL be listed under External Libraries in the Project view and SHALL contain the bundled `util` modules.

#### Scenario: External Libraries
- **WHEN** the user expands External Libraries in the Project view
- **THEN** an "Alloy 6.2.0" entry lists the bundled `util` modules, and opening one shows it read-only
