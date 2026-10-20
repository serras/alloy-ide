# Spec Delta

## Purpose

Completes Alloy names and module paths in the editor: the declarations visible at the caret, the declarations of opened modules, and the modules that an `open` declaration can load. It works while the model has errors.

## ADDED Requirements

### Requirement: Completion of names in scope
Completion of an identifier SHALL offer every name visible at the caret, under the same scope rules as name-based resolution:
- binders and parameters in scope;
- fields of the enclosing signature;
- the module's top-level declarations;
- the declarations of the modules it opens, project or bundled, except their `private` ones.

It SHALL work whether or not the model type-checks.

#### Scenario: Top-level and opened names
- **WHEN** `b.als` contains `open a`, `a.als` declares `sig A {}` and `pred p[x: A] {}`, and the user invokes completion in a fact body of `b.als`
- **THEN** `A` and `p` are offered

#### Scenario: Binders and parameters
- **WHEN** the caret is inside `pred q[y: A] { all z: A | <caret> }`
- **THEN** `y` and `z` are offered

#### Scenario: Binder out of scope
- **WHEN** the caret is in the bound of a quantifier, as in `all z: <caret> | some z`
- **THEN** `z` is not offered

#### Scenario: Fields of the enclosing signature
- **WHEN** the caret is inside the signature fact of `sig S { f: set S } { <caret> }`
- **THEN** `f` is offered

#### Scenario: Bundled module names
- **WHEN** the file contains `open util/ordering[S]` and the user invokes completion in a fact body
- **THEN** `first`, `last` and `next` are offered

#### Scenario: Private declarations of an opened module
- **WHEN** `a.als` declares `private pred hidden {}` and `b.als`, which contains `open a`, invokes completion in a fact body
- **THEN** `hidden` is not offered

#### Scenario: Model with errors
- **WHEN** the file has a type error in another paragraph and the user invokes completion in a fact body
- **THEN** the same names are offered as without the error

#### Scenario: No names in comments or strings
- **WHEN** the user invokes completion inside a comment or a string literal
- **THEN** no names are offered

### Requirement: Completion of qualified names
After a qualifier that names an opened module, by its alias or its path, completion SHALL offer only that module's top-level declarations. After `this/`, it SHALL offer only the current module's top-level declarations.

#### Scenario: Alias qualifier
- **WHEN** the file contains `open util/ordering[S] as ord` and the user invokes completion after `ord/`
- **THEN** `first`, `last` and `next` are offered, and the current module's own declarations are not

#### Scenario: Unknown qualifier
- **WHEN** the user invokes completion after a qualifier that names no opened module
- **THEN** no names are offered

### Requirement: Completion filtered by context
Where Alloy accepts only some kinds of declaration, completion SHALL offer only those:
- the target of `run`: predicates and functions;
- the target of `check`: assertions;
- the parents after `extends` or `in` in a signature header: signatures.

#### Scenario: Run target
- **WHEN** the file declares `sig A {}`, `pred p {}`, `fun g: A { A }` and `assert q {}`, and the user invokes completion after `run `
- **THEN** `p` and `g` are offered, and `A` and `q` are not

#### Scenario: Check target
- **WHEN** the user invokes completion after `check ` in that file
- **THEN** `q` is offered, and `A`, `p` and `g` are not

#### Scenario: Signature parent
- **WHEN** the user invokes completion after `sig B extends ` in that file
- **THEN** `A` is offered, and `p`, `g` and `q` are not

### Requirement: Presentation of completion items
Each name item SHALL show the kind of its declaration with an icon and as type text: signature, field, enum, enum member, predicate, function, assertion, macro, parameter or binder. Items declared in another module SHALL show that module's name as tail text.

#### Scenario: Item from an opened module
- **WHEN** the file contains `open util/ordering[S]` and `first` is offered
- **THEN** its item shows a function icon, the type text "function" and the tail text `util/ordering`

#### Scenario: Local item
- **WHEN** a signature `A` of the current file is offered
- **THEN** its item shows a signature icon and the type text "signature", with no module tail text

### Requirement: Completion of module paths
Completion of the path in an `open` declaration SHALL offer:
- each project Alloy file that the analyzer would load under that path from the current file, by its declared `module` name;
- for a file without a `module` declaration, the path under which the analyzer would load it;
- the bundled library modules, under their `util/...` names.

A project file SHALL take precedence over a bundled module with the same path, and the current file SHALL NOT be offered.

#### Scenario: Sibling module
- **WHEN** `m/main.als` declares `module main`, `m/lib.als` declares `module lib`, and the user invokes completion after `open ` in `main.als`
- **THEN** `lib` is offered

#### Scenario: Module in a subdirectory
- **WHEN** `m/main.als` declares `module app/main`, `m/sub/x.als` declares `module app/sub/x`, and the user invokes completion after `open ` in `main.als`
- **THEN** `app/sub/x` is offered

#### Scenario: File without a module declaration
- **WHEN** `m/sub/y.als` has no `module` declaration and the user invokes completion after `open ` in `m/main.als`, which declares `module main`
- **THEN** `sub/y` is offered

#### Scenario: Bundled modules
- **WHEN** the user invokes completion after `open util/`
- **THEN** `util/ordering`, `util/integer`, `util/boolean` and the other bundled `util` modules are offered

#### Scenario: Project module shadows a bundled one
- **WHEN** the project has a file that the analyzer would load as `util/ordering` from the current file, and `util/ordering` is offered
- **THEN** it is offered once, as the project file

### Requirement: Unreachable modules shown as such
Project Alloy files whose declared `module` name would not lead the analyzer back to them from the current file SHALL be offered greyed out, with a hint saying that the module cannot be opened from here.

#### Scenario: Declared name does not match the location
- **WHEN** `m/b/x.als` declares `module foo/x`, the current file is `m/main.als` declaring `module main`, and the user invokes completion after `open `
- **THEN** `foo/x` is offered greyed out, with a hint that it cannot be opened from this file, after the modules that can be opened
