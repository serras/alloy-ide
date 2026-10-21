# Spec Delta

## MODIFIED Requirements

### Requirement: Alloy inspections are configurable
The plugin SHALL register its inspections in an "Alloy" group of *Settings | Editor | Inspections*. Each one SHALL be enabled by default, with the default severity this spec gives it. Each one SHALL have a description and can be disabled or given another severity on its own.

#### Scenario: Inspections listed
- **WHEN** the user opens *Settings | Editor | Inspections*
- **THEN** an "Alloy" group lists "Signature in mutable state left at the default scope", "Exact scope forced by a module parameter", "Sequence bound smaller than its elements' scope", "Missing frame condition", "Fact constrains only the start of the trace", "Prime on an expression that never changes" and "Integer literal outside the command's bitwidth", each enabled and with a description

#### Scenario: Inspection disabled
- **WHEN** the user disables "Missing frame condition" and opens a model with an event predicate that misses a frame condition
- **THEN** no problem is reported for it, and the other Alloy inspections still report theirs

## ADDED Requirements

### Requirement: Fact that constrains the next state only at the start
A fact or signature fact SHALL get a weak warning when it constrains the next state outside every `always`, `eventually`, `until` and `releases`. Constraining the next state means using a `'` or a written `after`, or calling an event predicate (see "Event predicates are recognized"). The warning SHALL be placed on the first such occurrence, and one SHALL be reported per fact.

#### Scenario: Frame without always
- **WHEN** a model with `var inbox` has `fact { all n: Node | n.inbox' = n.inbox }`
- **THEN** `n.inbox'` gets a weak warning saying that the fact constrains only the transition out of the first state, because it is not under `always`

#### Scenario: Event called from a fact
- **WHEN** a model has an event predicate `initiate[n]` and `fact { some n: Node | initiate[n] }`
- **THEN** `initiate[n]` gets a weak warning saying that the event is constrained to happen only in the first transition

#### Scenario: Events under always
- **WHEN** a fact reads `always (stutter or some n: Node | node_acts[n])`, or `no inbox` followed by `always (…)`, or `eventually some n: Node | initiate[n]`
- **THEN** this requirement reports nothing for it

#### Scenario: Under after only
- **WHEN** a fact reads `after (inbox' = inbox)`
- **THEN** the `after` gets the weak warning, since the fact constrains only the second transition

#### Scenario: Sequence operator
- **WHEN** a fact reads `no inbox ; some inbox`
- **THEN** nothing is reported for it, since `;` describes a scenario

### Requirement: Fact over mutable state without temporal operators
A fact or signature fact that mentions a `var` field or `var sig`, but uses no temporal operator at all, SHALL get an information-level problem on its `fact` keyword and name, without highlighting. Temporal operators here include past operators, `after`, `'` and `;`, used directly or through the predicates, functions and macros the fact calls.

#### Scenario: Intended invariant
- **WHEN** a model with `var inbox` has `fact inbox_bound { all n: Node | lone n.inbox }`
- **THEN** `fact inbox_bound` has an information-level problem, not highlighted, saying that the fact constrains only the first state. Alt+Enter offers "Wrap in always"

#### Scenario: Initial-state fact
- **WHEN** the book's `fact init { no inbox }` is inspected
- **THEN** it has the same information-level problem, and nothing is highlighted

#### Scenario: Static fact
- **WHEN** a fact reads `all n: Node | Node in n.^succ`, with `succ` a static field
- **THEN** nothing is reported for it

#### Scenario: Through a predicate
- **WHEN** a fact reads `init`, with `pred init { no inbox }`, or `always invariant`
- **THEN** the first has the information-level problem, and the second has nothing

### Requirement: Wrap a fact in always
Both fact problems SHALL offer "Wrap in always". It SHALL wrap the whole body of the fact in `always { … }`, indent the body's lines one more level, and keep one-line bodies on one line.

#### Scenario: Multi-line body
- **WHEN** the user applies "Wrap in always" to `fact inbox_bound {` / `  all n: Node | lone n.inbox` / `}`
- **THEN** the fact reads `fact inbox_bound {` / `  always {` / `    all n: Node | lone n.inbox` / `  }` / `}`, and the problem is gone

#### Scenario: One-line body
- **WHEN** the user applies it to `fact { all n: Node | n.inbox' = n.inbox }`
- **THEN** the fact reads `fact { always { all n: Node | n.inbox' = n.inbox } }`

### Requirement: Prime on an expression that never changes
An expression `e'` SHALL get a weak warning when `e` mentions no `var` field or `var sig`, directly or through the functions it calls. Quantified variables and predicate or function parameters do not change between states. A `let` variable counts as its definition. Primes in macro declarations, and primes of an argument a macro substitutes, SHALL NOT be reported.

#### Scenario: Static field primed
- **WHEN** a model has a static `succ` and `pred step { inbox' = inbox + succ' }`
- **THEN** `succ'` gets a weak warning saying that `succ` is static, so `succ'` is the same as `succ`

#### Scenario: Predicate parameter primed
- **WHEN** a model has `pred unchanged[r: Node -> Node] { r' = r }`
- **THEN** `r'` gets a weak warning saying that parameters keep their value across states, and that a macro (`let unchanged[r] { r' = r }`) primes its argument instead

#### Scenario: Mutable expressions
- **WHEN** a model has `n.inbox'` with `var inbox`, `(n.succ.inbox)'`, `Active'` with `var sig Active`, `let x = inbox | x'`, or `let unchanged[x] { x = (x)' }` called as `unchanged[succ]`
- **THEN** nothing is reported for them

#### Scenario: Remove the prime
- **WHEN** the user applies "Remove the prime" to `succ'`
- **THEN** the text reads `succ`, and the warning is gone

### Requirement: Integer literal outside the command's bitwidth
An integer literal SHALL get a weak warning when, for some command of the file, it is larger than the largest integer of that command's bitwidth. The bitwidth is the written `int` scope, or the analyzer's default of 4. A command depends on a literal in its own body, in any fact of the model, or in the predicates and functions they call. The message SHALL name the commands and the value the literal wraps to.

#### Scenario: Default bitwidth
- **WHEN** a model has `fact { #Node =< 8 }` and `run example {} for 3`
- **THEN** `8` gets a weak warning saying that `example` uses 4-bit integers (-8..7), so `8` is read as `-8`

#### Scenario: Literal in a predicate
- **WHEN** a model has `pred big { #Node > 20 }`, `run big for 5 int` and `run small {} for 3`
- **THEN** `20` gets a weak warning naming only `big`, which uses 5-bit integers (-16..15), so `20` is read as `-12`; `small` does not call `big`, so it is not named

#### Scenario: Several commands
- **WHEN** a model has `fact { #Node < 12 }`, `run a {} for 4`, `run b {} for 4 but 5 int` and `run c {} for 4 but 3 int`
- **THEN** `12` gets one weak warning naming `a` (wraps to -4) and `c` (wraps to -4), and not `b`

#### Scenario: Raise the bitwidth
- **WHEN** the user applies "Set the bitwidth of a to 5 int" to the literal `12`, with `run a {} for 4`
- **THEN** the command reads `run a {} for 4 but 5 int`, the inserted number is selected for editing, and `a` is no longer named in the warning

#### Scenario: Bitwidth already written
- **WHEN** the same fix is applied for `run c {} for 4 but 3 int`
- **THEN** the command reads `run c {} for 4 but 5 int`
