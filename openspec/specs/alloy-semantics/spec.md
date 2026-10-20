# alloy-semantics Specification

## Purpose

Gives Alloy models native semantic editing features (diagnostics, documentation, navigation, usages, rename and symbols), computed inside the IDE by the bundled Alloy analyzer, with name-based behavior while the model has errors.

## Requirements

### Requirement: Diagnostics
The plugin SHALL show the syntax and type errors and the warnings reported by the bundled Alloy analyzer for the current editor text, at the reported ranges, through the IDE's standard error highlighting.

#### Scenario: Type error
- **WHEN** the user edits a file so that it references an undefined signature
- **THEN** the offending identifier is underlined as an error, and hovering it shows the analyzer's message

#### Scenario: Error fixed
- **WHEN** the user corrects the error
- **THEN** the error highlighting is removed without saving the file

#### Scenario: Warning
- **WHEN** the analyzer reports a warning for the model (for example, an expression that is always empty)
- **THEN** the location is highlighted as a warning with the analyzer's message

#### Scenario: Error in an opened module
- **WHEN** a file opens a project module that has a type error
- **THEN** the error is shown in the opened module's file, and the opening file reports that the opened module has errors at its `open` line
### Requirement: Diagnostics visible in the Problems view
Alloy diagnostics SHALL be reported to the IDE's standard problem infrastructure, so that they appear in the editor's error stripe and in the Problems tool window for the current file.

#### Scenario: Problems tool window
- **WHEN** the current Alloy file has a type error
- **THEN** the Problems tool window lists that error, and selecting it moves the caret to its location
### Requirement: Quick documentation
The plugin SHALL show documentation for an Alloy element on hover and on Quick Documentation. A reference shows its declaration as the analyzer explains it, preceded by the declaration's documentation comment if it has one.

#### Scenario: Hovering a field
- **WHEN** the user hovers the mouse over a use of a signature field
- **THEN** a popup shows the field's declaration with its type

#### Scenario: Documentation comment
- **WHEN** a predicate is preceded by a `/** ... */` comment and the user invokes Quick Documentation on one of its uses
- **THEN** the popup shows that comment's text together with the predicate's declaration

#### Scenario: Model with errors
- **WHEN** the model does not type-check and the user invokes Quick Documentation on a reference that resolves by name
- **THEN** the popup shows the declaration's source text and documentation comment
### Requirement: Go to declaration
Ctrl/Cmd-click and Go to Declaration on an Alloy reference SHALL navigate to its declaration. This includes declarations in other project files and in the Alloy library modules bundled with the analyzer.

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
### Requirement: Exact resolution for a valid model
When the model type-checks, every reference SHALL resolve to exactly the declaration the analyzer resolved it to, including overloaded field names and the implicit `this` in signature facts.

#### Scenario: Overloaded field
- **WHEN** signatures `A` and `B` both declare a field `f`, the model type-checks, and the user navigates from `a.f` where `a: A`
- **THEN** the caret moves to the field `f` of `A`, without offering `B`'s field
### Requirement: Name-based resolution for a model with errors
When the model does not type-check, references SHALL resolve by name, searching from the innermost scope outwards:
1. Enclosing binders and parameters.
2. Fields of the enclosing signature.
3. The module's top-level declarations.
4. Declarations of opened modules, by their alias or unqualified.

A name that still matches several declarations SHALL offer all of them.

#### Scenario: Navigating while editing
- **WHEN** the file has a type error in one predicate and the user Ctrl/Cmd-clicks a signature name in another predicate
- **THEN** the editor moves to that signature's declaration

#### Scenario: Inner binder shadows outer declaration
- **WHEN** the model has errors, a signature `x` exists, and the user navigates from the `x` in `some x: A | x in A`
- **THEN** the caret moves to the quantifier's binder `x`, not to signature `x`

#### Scenario: Ambiguous name
- **WHEN** the model has errors, signatures `A` and `B` both declare a field `f`, and the user navigates from a use of `f` whose receiver type cannot be determined by name
- **THEN** the IDE offers a choice between both fields

#### Scenario: Qualified name
- **WHEN** the model has errors, the file contains `open util/ordering[S] as ord`, and the user navigates from `ord/first`
- **THEN** the caret moves to `first` in the bundled `util/ordering` module
### Requirement: Highlight usages in file
Placing the caret on an Alloy declaration or reference SHALL highlight every usage of the same declaration in the current file. The resolution rules are the same as for Go to declaration.

#### Scenario: Caret on a field
- **WHEN** the caret is on a field name
- **THEN** that field's declaration and every use of it in the file are highlighted
### Requirement: Find usages
Find Usages on an Alloy declaration or reference SHALL list every reference to that declaration across the project's Alloy files. When some files do not type-check, the matches found by name in them SHALL be listed in a separate group of unconfirmed usages.

#### Scenario: Finding usages of a predicate
- **WHEN** the user invokes Find Usages on a predicate name
- **THEN** every reference to it in the project is listed, and each entry navigates to its location

#### Scenario: Usages in a file with errors
- **WHEN** a predicate is used in a file that does not type-check
- **THEN** those uses are listed in a separate unconfirmed usages group
### Requirement: Rename
Rename on an Alloy declaration or reference SHALL update the declaration and every reference to it in the project in a single undoable action. Rename SHALL be refused, with a message saying why, while any affected file does not type-check.

#### Scenario: Renaming a signature
- **WHEN** the user renames signature `A` to `B`
- **THEN** the declaration and every reference in the project are updated to `B`, and a single Undo reverts the whole rename

#### Scenario: Renaming a local variable
- **WHEN** the user renames the binder `x` of `all x: A | some x.f`
- **THEN** the binder and its uses inside that quantifier are renamed in place, and no other `x` is changed

#### Scenario: Rename refused with errors
- **WHEN** the model has a type error and the user invokes Rename on a signature
- **THEN** no text changes, and the IDE says that rename needs a model without errors

#### Scenario: Library declarations are read-only
- **WHEN** the user invokes Rename on a declaration from a bundled library module
- **THEN** the rename is refused because the module is read-only
### Requirement: Structure view and breadcrumbs
The Structure tool window and the editor breadcrumbs of an Alloy file SHALL list its module, signatures (with their fields nested), facts, predicates, functions, assertions, macros and commands. Selecting an entry SHALL move the caret to it, whether or not the model type-checks.

#### Scenario: Structure view
- **WHEN** the user opens the Structure tool window on a file with signatures, fields, predicates and commands
- **THEN** those elements are listed with fields under their signatures, and selecting one moves the caret to it

#### Scenario: Breadcrumbs
- **WHEN** the caret is inside the body of predicate `p`
- **THEN** the breadcrumbs show `p`

#### Scenario: File with errors
- **WHEN** the file has a type error
- **THEN** the Structure tool window still lists all of its elements
### Requirement: Go to Symbol
Go to Symbol and Search Everywhere SHALL find Alloy signatures, fields, predicates, functions, assertions, named facts and named commands declared in the project's Alloy files.

#### Scenario: Searching for a symbol
- **WHEN** the user searches for a predicate name in Go to Symbol
- **THEN** the predicate declared in a project `.als` file appears, and selecting it opens that file at the declaration
### Requirement: Semantic features never block the editor
The plugin SHALL compute semantic information off the UI thread, with any work for outdated text cancelled. Failures of the analyzer on any input SHALL degrade to name-based behavior and SHALL NOT raise IDE errors.

#### Scenario: Typing in a large model
- **WHEN** the user types continuously in a large model
- **THEN** typing is not delayed by the analyzer, and diagnostics appear once typing pauses

#### Scenario: Analyzer failure
- **WHEN** the analyzer throws an unexpected exception for some input
- **THEN** no IDE error notification is shown, and navigation still works by name
