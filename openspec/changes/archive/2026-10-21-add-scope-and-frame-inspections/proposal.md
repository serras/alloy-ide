# Proposal

## Why

The *Protocol design* chapter of Practical Alloy shows several mistakes that the analyzer accepts in silence. Each one gives a spurious "no instance" or a check that passes only because the scope is too small: a "false sense of safety".

- In `bad_all_initiate`, the `Message` signature gets the default scope while `Node` is scoped explicitly.
- `util/ordering[Id]` makes the scope of `Id` exact, whatever the command says.
- In the bounded-queue model, `for 3 but 2 seq` makes the inbox too short to hold every node's identifier.
- An event predicate that forgets a frame condition such as `outbox' = outbox` lets that field change freely. This is the most common bug in the event style that the book teaches.

The plugin already type-checks every model (see `alloy-semantics`), so it can point these out while the user edits, before any analysis runs.

This change covers items #9 (scope warnings) and #12 (missing frame conditions) of the feature list. The request called the second one #11.

## What Changes

- **Four new IntelliJ inspections**, in an "Alloy" group of *Settings | Editor | Inspections*. Each one can be turned off on its own, and each one runs only while the model type-checks.
  - **Signature in mutable state left at the default scope.** This covers commands that set the scope of some signatures but leave a signature used by a `var` field, or a `var sig`, at the overall or default scope. It is a weak warning, with a quick-fix that adds an explicit scope for that signature.
  - **Exact scope forced by a module parameter.** This applies when the command's scope for a signature `X` reads as an upper bound, but `open util/ordering[X]` (or another `exactly` module parameter) makes it exact.
    - A scope written for `X` without `exactly` is a weak warning.
    - An overall `for N` that applies to `X` is reported only as information, so it is not highlighted.
    - The quick-fix writes `exactly`.
  - **Sequence bound smaller than its elements' scope.** This covers commands whose `seq` bound is smaller than the scope of the elements stored in a `var` field of `seq` type. It is a weak warning, with a quick-fix that raises the `seq` bound.
  - **Missing frame condition.** This flags an event predicate (one that constrains the next state, with `'` or `after`) that leaves some `var` field or `var sig` unconstrained in the next state. It is a weak warning, with quick-fixes that add `f' = f` for one field or for all of them.
- A `// noinspection <id>` comment on the line before a paragraph suppresses an inspection for that paragraph. This is the IDE's standard suppression, implemented for Alloy.
- The README's feature list mentions the inspections.

## Capabilities

### New Capabilities
- `alloy-inspections`: static checks of a type-checked Alloy model for likely modelling mistakes (scopes of commands and frame conditions of events), reported as IDE inspections with quick-fixes and suppression.

### Modified Capabilities
<!-- none: the analyzer's own diagnostics (alloy-semantics) are unchanged -->

## Impact

- **Code.** A new `inspections/` package:
  - four `LocalInspectionTool`s;
  - a scope calculator for commands, based on the analyzer's `Command`;
  - an analysis of the analyzer's expressions that finds primed relations and calls;
  - quick-fixes that edit the command or predicate text;
  - an `InspectionSuppressor` for `//` comments.
- **`plugin.xml`.** `localInspection` entries, the `lang.inspectionSuppressor`, and inspection descriptions under `resources/inspectionDescriptions/`.
- **Dependencies.** No new ones. The code uses public analyzer classes (`CompModule`, `Command`, `CommandScope`, `Sig`, `Func`, `Expr` and its visitors) from `org.alloytools.alloy.core` 6.2.0.
- **Tests.**
  - New inspection tests use the Practical Alloy fixtures: `bad_all_initiate`, the `util/ordering[Id]` model, `for 3 but 2 seq`, and the frame-condition macro model.
  - The existing `PracticalAlloyModelsTest` checks at WARNING severity, so it is unaffected by weak warnings. A new test lists the expected weak warnings of the protocol-design and behavioral fixtures.
  - New items go into the DEV.md manual checklists.
