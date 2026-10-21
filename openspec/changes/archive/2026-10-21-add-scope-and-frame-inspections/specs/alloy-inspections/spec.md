# Spec Delta

## Purpose

Points out likely modelling mistakes in a type-checked Alloy model as IDE inspections. These are mistakes the analyzer accepts silently: command scopes that hide behaviors, and event predicates with missing frame conditions. Where the fix is mechanical, a quick-fix is offered.

## ADDED Requirements

### Requirement: Alloy inspections are configurable
The plugin SHALL register its inspections in an "Alloy" group of *Settings | Editor | Inspections*. Each one SHALL be enabled by default, with the default severity this spec gives it. Each one SHALL have a description and can be disabled or given another severity on its own.

#### Scenario: Inspections listed
- **WHEN** the user opens *Settings | Editor | Inspections*
- **THEN** an "Alloy" group lists "Signature in mutable state left at the default scope", "Exact scope forced by a module parameter", "Sequence bound smaller than its elements' scope" and "Missing frame condition", each enabled and with a description

#### Scenario: Inspection disabled
- **WHEN** the user disables "Missing frame condition" and opens a model with an event predicate that misses a frame condition
- **THEN** no problem is reported for it, and the other Alloy inspections still report theirs

### Requirement: Inspections need a type-checked model
The Alloy inspections SHALL report problems only while the file's model parses and type-checks. They SHALL report nothing while it has errors. They SHALL work on the current text of the file, saved or not, and only on declarations and commands of the file being inspected.

#### Scenario: Model with a type error
- **WHEN** a file with an event predicate that misses a frame condition also has a type error
- **THEN** only the analyzer's error is reported, and the frame-condition problem appears once the error is fixed

#### Scenario: Declarations of an opened module
- **WHEN** `b.als` opens `a.als`, and `a.als` has an event predicate that misses a frame condition
- **THEN** the problem is reported in `a.als` and not in `b.als`

### Requirement: Mutable-state signature left at the default scope
A command SHALL get a weak warning when it sets the scope of at least one signature and leaves a top-level signature `S` at the overall or default scope. This applies when `S` occurs in the type of a `var` field, other than as the field's owner, or `S` or one of its descendants is a `var sig`. Signatures that have a scope through an ancestor or descendant, `one`, `lone` and enum signatures, and library signatures are not reported.

#### Scenario: The book's bad_all_initiate
- **WHEN** a model declares `var inbox : set Message` in `Node`, and has `run bad_all_initiate {...} for 3 but exactly 3 Node`
- **THEN** the command's scope gets a weak warning saying that `Message` gets the default scope of 3, although the command sets the scope of `Node`, and that `Message` is used by the mutable field `inbox`

#### Scenario: Scope given to a subsignature
- **WHEN** the same model has `run all_initiate {...} for exactly 3 Node, exactly 6 Message`, or the scope is given to `CandidateMsg` instead
- **THEN** no problem is reported for `Message`

#### Scenario: No per-signature scope
- **WHEN** a command is `run example {}`, `check p for 4` or `check p for 4 but 20 steps`
- **THEN** this inspection reports nothing for it

#### Scenario: Add the scope
- **WHEN** the user applies "Add a scope for Message" to `for 3 but exactly 3 Node`
- **THEN** the command reads `for 3 but exactly 3 Node, 3 Message`, the inserted number is selected for editing, and the warning is gone

### Requirement: Exact scope forced by a module parameter
When a module parameter declared `exactly` (such as `util/ordering[X]`) makes the scope of `X` exact, a command scope for `X` written without `exactly` SHALL get a weak warning. When `X` takes the command's written overall `for N` instead, the command SHALL get an information-level problem. Commands without a written scope are not reported.

#### Scenario: Explicit upper bound on an ordered signature
- **WHEN** a model opens `util/ordering[Id]` and has `check safe for 3 but 2 Id`
- **THEN** `2 Id` gets a weak warning saying that `util/ordering` makes the scope of `Id` exactly 2, so configurations with fewer `Id` atoms are not analyzed

#### Scenario: Overall scope applied to an ordered signature
- **WHEN** the same model has `check at_most_one_leader for 4 but 20 steps`
- **THEN** the command has an information-level problem, without highlighting, saying that `Id` gets exactly 4; Alt+Enter offers the quick-fix

#### Scenario: Exactness already written
- **WHEN** the command is `run example3 {} for exactly 3 Node, exactly 3 Id`, or it has no `for` at all
- **THEN** this inspection reports nothing for it

#### Scenario: Make the scope exact
- **WHEN** the user applies "Write the scope of Id as exact" to `for 3 but 2 Id`, or to `for 4 but 20 steps`
- **THEN** the command reads `for 3 but exactly 2 Id`, or `for 4 but exactly 4 Id, 20 steps`, and the problem is gone

### Requirement: Sequence bound smaller than its elements' scope
A command SHALL get a weak warning when the sequence bound it gives a `var` field declared with `seq T` is smaller than the scope of `T`'s top-level signature in that command. The bound is the written `seq` scope, or the analyzer's default. The warning is placed on the `seq` scope if it is written, and on the command's scope otherwise.

#### Scenario: The book's bounded queues
- **WHEN** a model declares `var inbox : seq Node` and has `check at_least_one_leader_fair for 3 but 2 seq`
- **THEN** `2 seq` gets a weak warning saying that sequences in `inbox` hold at most 2 elements, fewer than the 3 `Node` atoms

#### Scenario: Bound large enough
- **WHEN** the same model has `check at_most_one_leader for 4 but 20 steps`
- **THEN** this inspection reports nothing for it

#### Scenario: Raise the bound
- **WHEN** the user applies "Raise the seq bound to 3" to `for 3 but 2 seq`
- **THEN** the command reads `for 3 but 3 seq`, and the warning is gone

### Requirement: Event predicates are recognized
A predicate SHALL be an event predicate when its body constrains the next state, directly or through the predicates and macros it calls. Constraining the next state means using `'` on an expression that mentions a mutable relation, or `after`. A predicate that also uses `always`, `eventually`, `until`, `releases` or `;`, directly or through its calls, is not an event predicate.

#### Scenario: Events, guards and properties
- **WHEN** a model has `pred initiate[n: Node] { ... inbox' = inbox + ... }`, `pred initiate_enabled[n: Node] { historically ... }` and `pred fairness { all n: Node | eventually always ... implies always eventually node_acts[n] }`
- **THEN** `initiate` is an event predicate, while `initiate_enabled` and `fairness` are not

### Requirement: Missing frame condition
An event predicate SHALL get a weak warning, on its name, listing every mutable relation (a `var` field or `var sig` of the model) that its body leaves unconstrained in the next state. A relation is constrained if it is mentioned under `'` or `after` anywhere in the body, in any branch, or in the bodies of the predicates and macros it calls.

#### Scenario: Missing frame condition reported
- **WHEN** a model has `var inbox` and `var outbox` fields, and `pred send[n: Node] { some i: n.outbox | inbox' = inbox + n.succ->i }`
- **THEN** `send` gets a weak warning saying that it does not constrain the next value of `outbox`

#### Scenario: Conditional effects and helpers
- **WHEN** every branch of an `implies … else` constrains `inbox'`, or the predicate calls `unchanged[outbox]` with `let unchanged[x] { x = (x)' }`, or it calls a predicate that does
- **THEN** neither `inbox` nor `outbox` is reported

#### Scenario: The book's models
- **WHEN** the final models of the *Behavioral modeling* and *Protocol design* chapters are inspected
- **THEN** no missing frame condition is reported

#### Scenario: Add frame conditions
- **WHEN** the user applies "Add `outbox' = outbox`", or "Add all missing frame conditions", to `send`
- **THEN** each missing condition is added on its own line at the end of the body with the body's indentation, and the warning is gone

### Requirement: Suppression by comment
Each Alloy inspection SHALL offer the IDE's "Suppress" actions. Suppressing writes a `// noinspection <id>` line before the paragraph or command, which turns that inspection off for it.

#### Scenario: Suppress for a predicate
- **WHEN** the user picks "Suppress for predicate" on a missing frame condition of `send`
- **THEN** the line before `pred send` reads `// noinspection AlloyMissingFrameCondition`, and `send` is no longer reported, while other predicates still are
