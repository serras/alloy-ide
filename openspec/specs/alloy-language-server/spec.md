# alloy-language-server Specification

## Purpose

Runs the upstream Alloy language server alongside the IDE and surfaces its semantic features (diagnostics, hover, navigation, references, rename, symbols) in IntelliJ's standard editor UI, without loading the server's dependencies into the IDE.

## Requirements

### Requirement: Server starts for Alloy files
The plugin SHALL start one Alloy language server per project when an Alloy file in that project is opened, and SHALL reuse that server for every Alloy file in the project.

#### Scenario: First Alloy file opened
- **WHEN** the user opens the first `.als` file in a project
- **THEN** a language server process starts for that project and the file is sent to it

#### Scenario: Further files reuse the server
- **WHEN** the user opens a second `.als` file in the same project
- **THEN** no additional server process is started

#### Scenario: Project closed
- **WHEN** the project is closed
- **THEN** its language server process and any analyzer subprocesses it started are terminated

### Requirement: Out-of-process isolation
The server SHALL run in a separate Java process using the IDE's bundled Java runtime, and none of the server's libraries SHALL be loaded into the IDE process.

#### Scenario: No system Java needed
- **WHEN** the user has no Java installation other than the IDE's own runtime
- **THEN** the language server still starts

#### Scenario: Conflicting library versions
- **WHEN** the IDE and the server depend on different versions of the same library (for example lsp4j)
- **THEN** the server uses its own bundled version and the IDE is unaffected

### Requirement: Server failures are reported
If the server process fails to start or exits unexpectedly, the plugin SHALL report the failure through the IDE's language-server status UI with access to the server's error output, and SHALL let the user restart it.

#### Scenario: Server crashes
- **WHEN** the language server process exits unexpectedly
- **THEN** the IDE shows the server as failed, its stderr output is available to the user, and a restart action is offered

### Requirement: Diagnostics
The plugin SHALL show syntax and type errors and warnings reported by the server as editor annotations at the reported ranges.

#### Scenario: Type error
- **WHEN** the user saves or edits a file so that it references an undefined signature
- **THEN** the offending identifier is underlined as an error with the server's message shown on hover

#### Scenario: Error fixed
- **WHEN** the user corrects the error
- **THEN** the error annotation is removed once the server re-publishes diagnostics

### Requirement: Hover documentation
The plugin SHALL show the server's hover information when the user hovers or requests quick documentation on an Alloy element.

#### Scenario: Hovering a field
- **WHEN** the user hovers the mouse over a use of a signature field
- **THEN** a popup shows the information returned by the server for that element

### Requirement: Go to definition
The plugin SHALL navigate to the declaration returned by the server when the user invokes Go to Declaration, including declarations in other files of the project.

#### Scenario: Navigating to a signature
- **WHEN** the user Ctrl/Cmd-clicks a signature name used in a predicate
- **THEN** the editor moves to that signature's declaration

### Requirement: Find usages
The plugin SHALL list the references returned by the server when the user invokes Find Usages on an Alloy element.

#### Scenario: Finding usages of a predicate
- **WHEN** the user invokes Find Usages on a predicate name
- **THEN** every reference returned by the server is listed and each entry navigates to its location

### Requirement: Rename
The plugin SHALL apply the server's rename edits to every affected location when the user invokes Rename on an Alloy element.

#### Scenario: Renaming a signature
- **WHEN** the user renames signature `A` to `B`
- **THEN** the declaration and every reference the server reports are updated to `B` in a single undoable action

### Requirement: Document symbols
The plugin SHALL use the server's document symbols to populate the Structure tool window and editor breadcrumbs for Alloy files.

#### Scenario: Structure view
- **WHEN** the user opens the Structure tool window on an Alloy file with signatures, predicates and commands
- **THEN** those symbols are listed and selecting one moves the caret to it

### Requirement: Workspace symbols
The plugin SHALL include the server's workspace symbols in Go to Symbol / Search Everywhere.

#### Scenario: Searching for a symbol
- **WHEN** the user searches for a predicate name in Go to Symbol
- **THEN** the predicate declared in a project `.als` file appears and selecting it opens that file at the declaration

### Requirement: Single run UI
The plugin SHALL suppress the server's code lenses and command document links, so that the server never exposes its own Execute links in the editor.

#### Scenario: No duplicate run affordances
- **WHEN** the user opens a file containing `run` and `check` commands
- **THEN** no "Execute" code lens or command link from the server appears, and only the plugin's run gutter icons are shown

### Requirement: Supported IDEs
The plugin SHALL declare compatibility only with IDEs that provide the IntelliJ Platform LSP API, with 2026.2 as the minimum build.

#### Scenario: IDE without LSP support
- **WHEN** a user tries to install the plugin in an IDE that does not provide the LSP module
- **THEN** the IDE reports the plugin as incompatible and does not install it
