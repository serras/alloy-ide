# Spec Delta

## ADDED Requirements

### Requirement: Structural parsing of declarations and references
The parser SHALL recognize the top-level Alloy paragraphs (`module`, `open`, `sig`/`enum`, `fact`, `pred`, `fun`, `assert`, `let` macros, and `run`/`check` commands, each optionally named). Within them it SHALL recognize the declarations that introduce names and every identifier use as a reference. The names come from:
- signature field declarations;
- enum members;
- predicate and function parameters;
- quantifier, `let` and comprehension binders.

It SHALL not parse full expression structure.

#### Scenario: Recognizing paragraphs
- **WHEN** a file contains `sig A {}`, `pred p[x: A] { some x }` and `run p for 3`
- **THEN** the PSI tree contains a signature, a predicate and a command element, each spanning its full text

#### Scenario: Recognizing declarations
- **WHEN** a file contains `sig A { f: set A }` and `pred p[x: A] { all y: x.f | let z = y | some z }`
- **THEN** the PSI tree contains a field declaration `f`, a parameter `x`, a quantifier binder `y` and a `let` binder `z`

#### Scenario: Recognizing references
- **WHEN** a predicate body contains `some x.f`
- **THEN** `x` and `f` are reference elements

#### Scenario: Expressions are not checked
- **WHEN** a predicate body contains a type error or an unknown identifier but its brackets are balanced
- **THEN** the native parser reports no error for it (semantic errors come only from the bundled analyzer)

#### Scenario: Error recovery
- **WHEN** a paragraph is syntactically broken (for example `sig {` with no name and no closing brace)
- **THEN** the parser marks an error at that location and still recognizes the following top-level paragraphs

#### Scenario: Broken binder
- **WHEN** a quantifier inside a body is incomplete, such as `all x: | some x`
- **THEN** the parser still recognizes the paragraph and the remaining declarations and references in that body

### Requirement: Native features work on models with errors
Highlighting, parsing, brace matching, commenting, folding and keyword completion SHALL work on any file content, including models that do not parse or type-check for the Alloy analyzer.

#### Scenario: Model with a type error
- **WHEN** an opened Alloy file has a type error
- **THEN** it is still highlighted and foldable, and keyword completion still works

### Requirement: Supported IDEs
The plugin SHALL install in any IntelliJ-based IDE from build 2026.2, without requiring the IntelliJ Platform LSP module.

#### Scenario: IDE without LSP support
- **WHEN** a user installs the plugin in a 2026.2 IntelliJ-based IDE that does not provide the LSP module
- **THEN** the plugin installs and all of its features work

## REMOVED Requirements

### Requirement: Shallow structural parsing
**Reason**: Native semantic features need declarations and references inside paragraph bodies, so parsing is no longer limited to top-level paragraphs.
**Migration**: Replaced by "Structural parsing of declarations and references", which keeps every previous scenario.

### Requirement: Native features work without the language server
**Reason**: There is no language server any more.
**Migration**: Replaced by "Native features work on models with errors".
