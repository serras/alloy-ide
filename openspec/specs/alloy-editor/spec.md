# alloy-editor Specification

## Purpose

Gives Alloy 6 models (`.als` files) native editing support in IntelliJ-based IDEs: file recognition, lexical highlighting, a structural model of paragraphs, declarations and references, and the editor conveniences built on them, all working on any content, including models with errors.

## Requirements

### Requirement: Alloy file type recognition
The plugin SHALL register an "Alloy" language and an Alloy file type associated with the `.als` extension, with its own icon, so that `.als` files open in an Alloy-aware editor.

#### Scenario: Opening an .als file
- **WHEN** the user opens a file named `model.als`
- **THEN** the IDE identifies its file type as Alloy and shows the Alloy file icon in the editor tab and Project view

#### Scenario: Markdown models are not claimed
- **WHEN** the user opens a Markdown file (`.md`) containing an Alloy model
- **THEN** the file keeps its Markdown file type and the plugin provides no Alloy support for it

### Requirement: Lexical coverage of Alloy 6
The lexer SHALL tokenize every lexical form accepted by the upstream Alloy 6.2.0 lexer, including keywords, temporal operators, the prime (`'`) operator, identifiers (with `/`, `'`, `"` and `@` forms), integer literals, string literals, and all comment styles, and SHALL never fail on arbitrary input.

#### Scenario: Temporal and prime operators
- **WHEN** a file contains `always some x' and (p releases q) since r`
- **THEN** `always`, `releases` and `since` are tokenized as keywords and the trailing `'` on `x'` is tokenized as the prime operator

#### Scenario: All comment styles
- **WHEN** a file contains `// a`, `-- b`, `/* c */` and `/** d */`
- **THEN** each is tokenized as a comment (the last as a documentation comment), and nothing inside them is highlighted as code

#### Scenario: Invalid characters
- **WHEN** a file contains a character that is not valid Alloy (for example `§`)
- **THEN** that character is marked as a bad-character token and lexing continues with the rest of the file

#### Scenario: Unterminated constructs
- **WHEN** a file ends inside an unterminated block comment or string literal
- **THEN** the remainder of the file is tokenized as that comment or string without raising an error

### Requirement: Syntax highlighting with configurable colors
The plugin SHALL highlight Alloy tokens by category (keyword, temporal operator, operator, identifier, number, string, line comment, block comment, doc comment, braces/parentheses/brackets, comma/dot, bad character), and SHALL expose each category on an Alloy color settings page with a demo snippet.

#### Scenario: Default highlighting
- **WHEN** the user opens an Alloy file with default color scheme settings
- **THEN** keywords, numbers, strings and comments are shown with the IDE scheme's corresponding default colors

#### Scenario: Customizing a color
- **WHEN** the user changes the "Temporal operator" color in Settings | Editor | Color Scheme | Alloy
- **THEN** the demo snippet and every open Alloy editor show temporal operators in the new color

### Requirement: Brace matching
The editor SHALL highlight the matching partner of `{}`, `()` and `[]` and SHALL auto-insert the closing brace when the user types an opening `{`.

#### Scenario: Matching a brace
- **WHEN** the caret is next to the `{` that opens a signature body
- **THEN** the corresponding `}` is highlighted

### Requirement: Line and block commenting
The plugin SHALL support Comment with Line Comment using `//` and Comment with Block Comment using `/* */`.

#### Scenario: Toggling a line comment
- **WHEN** the user invokes Comment with Line Comment on an uncommented line
- **THEN** the line is prefixed with `//`, and invoking it again removes the prefix

#### Scenario: Toggling a block comment
- **WHEN** the user selects text and invokes Comment with Block Comment
- **THEN** the selection is wrapped in `/*` and `*/`

### Requirement: Code folding
The editor SHALL offer fold regions for multi-line paragraph bodies, multi-line block comments, and a contiguous run of `open` declarations.

#### Scenario: Folding a predicate body
- **WHEN** a `pred` body spans several lines
- **THEN** a fold region is offered for the body that collapses to `{...}`

#### Scenario: Folding imports
- **WHEN** a file contains two or more consecutive `open` lines
- **THEN** a single fold region covering those lines is offered

### Requirement: Keyword completion
The plugin SHALL offer Alloy keywords as completion items in code, and SHALL NOT offer them inside comments or string literals.

#### Scenario: Completing a keyword
- **WHEN** the user types `ab` at the start of a paragraph and invokes completion
- **THEN** `abstract` is offered

#### Scenario: No keywords in comments
- **WHEN** the user invokes completion inside a `//` comment
- **THEN** no Alloy keyword items are offered

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
