# alloy-simplifications Specification

## Purpose

Suggests simpler or equivalent forms of the formulas and expressions in a type-checked Alloy model. Strict simplifications are reported as inspections with a quick-fix, and rewrites between equivalent forms are offered as intentions. Either way, the result is a text edit that keeps the meaning and the user's own text.

## Requirements

### Requirement: Simplification inspections are configurable
The plugin SHALL register five inspections in an "Alloy | Simplifications" group of *Settings | Editor | Inspections*: "Boolean formula can be simplified", "Emptiness or cardinality test can be simplified", "Temporal formula can be simplified", "Quantified formula can be simplified" and "Relational expression can be simplified". Each SHALL be enabled by default as a weak warning, have a description, and be disabled or given another severity on its own.

#### Scenario: Inspections listed
- **WHEN** the user opens *Settings | Editor | Inspections*
- **THEN** the "Alloy" group has a "Simplifications" subgroup listing the five inspections, each enabled as a weak warning and with a description

#### Scenario: One family disabled
- **WHEN** the user disables "Temporal formula can be simplified" and opens a model with `always always p` and `not not q`
- **THEN** only `not not q` is reported

### Requirement: Simplifications need a type-checked model
The simplification inspections and intentions SHALL be available only while the file's model parses and type-checks. They SHALL work on the current text of the file, saved or not, and only on formulas and expressions of the file's facts, assertions, predicates, functions, signature facts and command bodies. The intentions SHALL only use the model that highlighting has already computed for the current text, so that showing them never runs an analysis.

#### Scenario: Model with a type error
- **WHEN** a file with `fact { not not some A }` also has a type error
- **THEN** only the analyzer's error is reported, and the double negation is reported once the error is fixed

#### Scenario: Opened module
- **WHEN** `b.als` opens `a.als`, and `a.als` has `fact { not not some A }`
- **THEN** the problem is reported in `a.als` and not in `b.als`

### Requirement: Only written text is rewritten
A simplification SHALL be offered only when the formula, and every part of it that the rewrite reuses, is written in the paragraph where it is reported. Formulas that come from a macro's expansion, or that the analyzer builds itself, SHALL NOT be reported. Formulas inside a macro's declaration SHALL NOT be reported either.

#### Scenario: Negation from a macro parameter
- **WHEN** a model has `let neg[x] { not x }` and `fact { neg[not some A] }`
- **THEN** no double negation is reported, neither in the fact nor in the macro

#### Scenario: Negation split between a fact and a macro
- **WHEN** a model has `let neg[x] { not x }` and `fact { not neg[some A] }`
- **THEN** no double negation is reported, although the expanded fact reads `not not some A`

#### Scenario: Macro declaration
- **WHEN** a model has `let twice[x] { not not x }`, called or not
- **THEN** nothing is reported in the macro

### Requirement: Rewrites keep the meaning and the user's text
Applying a quick-fix or intention SHALL replace exactly the rewritten formula, together with any parentheses written around it, by an equivalent formula. The replacement SHALL reuse the source text of the operands it keeps, comments and line breaks included, and their parentheses. It SHALL add parentheses only where an operand or the result would otherwise be read differently, and it SHALL type-check. A replacement that is a single operand, written in parentheses that its place does not need, SHALL drop them, unless a comment is written inside them.

The places where a quantifier or `let` would extend further than its operand SHALL get it in parentheses, including before a `;`, whose left side a quantifier's body takes in. The receiver `e` of a method-style call `e.f` SHALL be treated as the left side of a `.`, not as an argument in brackets.

#### Scenario: Parentheses no longer needed
- **WHEN** the user applies the quick-fix to `always (always p)`, or to `not (not (a or b))` on a line of its own
- **THEN** the text reads `always p`, or `a or b`

#### Scenario: Quantifier before a sequence
- **WHEN** the user applies the quick-fix to `not (some n: Node | n in n.succ) ; p`
- **THEN** the text reads `(no n: Node | n in n.succ) ; p`, and not `no n: Node | n in n.succ ; p`, which means `no n: Node | (n in n.succ ; p)`

#### Scenario: Receiver of a call
- **WHEN** a model has `fun g[s: Node -> Node]: set Node`, and the user applies the quick-fix to `Node->Node + Node->Node` in `some (Node->Node + Node->Node).g`
- **THEN** the text reads `some (Node->Node).g`, and not `some Node->Node.g`, which reads as `Node -> (Node.g)`

#### Scenario: Fixes in one paragraph
- **WHEN** a fact reads `not not p and not not q`, and the quick-fixes of both problems are applied one after the other, as "Fix all" does
- **THEN** the text reads `p and q`

#### Scenario: Operands kept as written
- **WHEN** the user applies the quick-fix to `not ((not this/A.f in B) or (not some g))`
- **THEN** the text reads `this/A.f in B and some g`

#### Scenario: Parentheses kept
- **WHEN** the user applies the quick-fix to `x.r = y implies not not (a or b)`
- **THEN** the text reads `x.r = y implies (a or b)`

#### Scenario: Parentheses added where needed
- **WHEN** the user applies the quick-fix to `c and not (not a and not b)`
- **THEN** the text reads `c and (a or b)`, and not `c and a or b`, which means something else

#### Scenario: Result of a rewrite inside an operator
- **WHEN** the user applies "Replace 'implies' with 'or'" to `p implies q` in `(p implies q) and r`
- **THEN** the text reads `(not p or q) and r`

### Requirement: Operator style is kept
A replacement SHALL use the symbolic operators `!`, `&&`, `||`, `=>` and `<=>` when the text it replaces writes its logical operators symbolically, and the keywords `not`, `and`, `or`, `implies` and `iff` otherwise.

#### Scenario: Symbolic operators
- **WHEN** the user applies the quick-fix to `!(!a || !b)`
- **THEN** the text reads `a && b`

#### Scenario: Keyword operators
- **WHEN** the user applies the quick-fix to `not (not a or not b)`
- **THEN** the text reads `a and b`

### Requirement: Nested simplifications
When simplifications of the same inspection nest, only the outermost one SHALL be reported. Once it is applied, any inner simplification that is still present SHALL be reported.

#### Scenario: Quadruple negation
- **WHEN** a fact reads `not not not not some A`
- **THEN** one problem is reported, on the whole formula. After its quick-fix the text reads `not not some A`, which is reported again, and after a second quick-fix it reads `some A`

### Requirement: Boolean simplifications
"Boolean formula can be simplified" SHALL report, and its quick-fix SHALL rewrite: `not not p` → `p`; `not (not a or not b …)` → `a and b …` and `not (not a and not b …)` → `a or b …`, with any number of operands; `not (a implies b)` → `a and not b`; and `not` over a comparison → the negated comparison. `!`, `&&`, `||` and `=>` count as their keyword forms.

#### Scenario: De Morgan collapse
- **WHEN** a fact reads `not ((not a) or (not b))`, with `a` and `b` formulas
- **THEN** it gets a weak warning, and "Simplify to 'a and b'" turns it into `a and b`

#### Scenario: Negated comparisons
- **WHEN** formulas read `not (x = y)`, `!(x in y)`, `not (x != y)`, `not (x not in y)`, `not (i < j)`, `not (i =< j)`, `not (i > j)` and `not (i >= j)`
- **THEN** their quick-fixes turn them into `x != y`, `x not in y`, `x = y`, `x in y`, `i >= j`, `i > j`, `i =< j` and `i < j`

#### Scenario: Negated implication
- **WHEN** a fact reads `not (p implies q)`
- **THEN** the quick-fix turns it into `p and not q`

#### Scenario: Implication with an else
- **WHEN** a fact reads `not (p implies q else r)`
- **THEN** this inspection reports nothing for it

### Requirement: Converse implications
"Boolean formula can be simplified" SHALL report adjacent conjuncts `a implies b` and `b implies a`, and its quick-fix SHALL replace both with `a iff b`. Conjuncts are adjacent when they follow each other in an `and` or in a block.

#### Scenario: Converse implications
- **WHEN** a block has the lines `p implies q` and `q implies p`, one after the other
- **THEN** both lines are reported as one problem, and the quick-fix replaces them by `p iff q`

#### Scenario: Implications that are not converse
- **WHEN** a block has `p implies q` and `q implies r`, or has `p implies q` and `q implies p` with another formula between them
- **THEN** nothing is reported

### Requirement: Emptiness and cardinality simplifications
"Emptiness or cardinality test can be simplified" SHALL report, and its quick-fix SHALL rewrite:
- `not some e` and `#e = 0` → `no e`;
- `not no e` and `#e > 0` → `some e`;
- `#e = 1` → `one e`;
- `#e =< 1` → `lone e`;
- `e = none` → `no e`, and `e != none` → `some e`, for a set `e`.

Mirrored and equivalent forms count too: `0 = #e`, `#e < 1`, `#e =< 0`, `#e >= 1`, `#e != 0` and `#e < 2`. The description SHALL say that the multiplicity form is also safe from integer overflow.

#### Scenario: Cardinality tests
- **WHEN** formulas read `#inbox = 0`, `0 = #inbox`, `#inbox > 0`, `#inbox >= 1`, `#inbox != 0`, `#leader = 1`, `#leader =< 1` and `#leader < 2`
- **THEN** their quick-fixes turn them into `no inbox`, `no inbox`, `some inbox`, `some inbox`, `some inbox`, `one leader`, `lone leader` and `lone leader`

#### Scenario: Negated multiplicities and none
- **WHEN** formulas read `not some n.inbox`, `not no n.inbox`, `n.inbox = none` and `n.inbox != none`
- **THEN** their quick-fixes turn them into `no n.inbox`, `some n.inbox`, `no n.inbox` and `some n.inbox`

#### Scenario: Other cardinality comparisons
- **WHEN** formulas read `#inbox = 2`, `#inbox > 1` or `#inbox = #outbox`
- **THEN** this inspection reports nothing for them

### Requirement: Temporal simplifications
"Temporal formula can be simplified" SHALL report, and its quick-fix SHALL rewrite:
- `always always p` → `always p`, and likewise for `eventually`, `historically` and `once`;
- `eventually always eventually p` → `always eventually p`;
- `always eventually always p` → `eventually always p`.

#### Scenario: Idempotent operators
- **WHEN** formulas read `always always some inbox`, `eventually eventually p`, `historically historically p` and `once once p`
- **THEN** their quick-fixes turn them into `always some inbox`, `eventually p`, `historically p` and `once p`

#### Scenario: Absorbed operators
- **WHEN** a fairness fact reads `eventually always eventually n.acts` or `always eventually always n.waits`
- **THEN** the quick-fixes turn them into `always eventually n.acts` and `eventually always n.waits`

#### Scenario: Operators that do not absorb
- **WHEN** formulas read `after after p`, `before before p`, `always eventually p` or `eventually always p`
- **THEN** this inspection reports nothing for them

### Requirement: Negated quantifier simplifications
"Quantified formula can be simplified" SHALL report, and its quick-fix SHALL rewrite: `not (some x: A | p)` → `no x: A | p`; `not (no x: A | p)` → `some x: A | p`; `not (all x: A | not p)` → `some x: A | p`; and `all x: A | not p` → `no x: A | p`.

#### Scenario: Negated quantifiers
- **WHEN** formulas read `not (some n: Node | n in n.^succ)`, `not (no n: Node | p[n])`, `not (all n: Node | not p[n])` and `all n: Node | not p[n]`
- **THEN** their quick-fixes turn them into `no n: Node | n in n.^succ`, `some n: Node | p[n]`, `some n: Node | p[n]` and `no n: Node | p[n]`

### Requirement: Membership quantifier simplifications
"Quantified formula can be simplified" SHALL rewrite `all x: A | x in B` → `A in B`, `some x: A | x in B` → `some A & B` and `no x: A | x in B` → `no A & B`. This applies only when `x` is the quantifier's only variable, has a plain or `one` bound, and does not occur in `B`.

#### Scenario: Membership quantifiers
- **WHEN** formulas read `all m: Message | m in Node.inbox`, `some m: Message | m in Node.inbox` and `no m: Message | m in Node.inbox`
- **THEN** their quick-fixes turn them into `Message in Node.inbox`, `some Message & Node.inbox` and `no Message & Node.inbox`

#### Scenario: Membership that depends on the variable
- **WHEN** formulas read `all n: Node | n in n.succ`, `all n: set Node | n in Node.inbox`, or `all n: Node, m: Message | n in m.from`
- **THEN** this inspection reports nothing for them

### Requirement: Distinct variable simplifications
"Quantified formula can be simplified" SHALL rewrite `all x, y: A | x != y implies p` → `all disj x, y: A | p` and `some x, y: A | x != y and p` → `some disj x, y: A | p`. This applies only when `x` and `y` are the only names of their declaration and the declaration is not already `disj`. `y != x` and `not x = y` count as `x != y`.

#### Scenario: Distinct variables
- **WHEN** formulas read `all x, y: Node | x != y implies x.id != y.id` and `some x, y: Node | x != y and x.id = y.id`
- **THEN** their quick-fixes turn them into `all disj x, y: Node | x.id != y.id` and `some disj x, y: Node | x.id = y.id`

#### Scenario: Three variables
- **WHEN** a formula reads `all x, y, z: Node | x != y implies p[x, y, z]`
- **THEN** this inspection reports nothing for it, since `disj` would also make `z` distinct

### Requirement: Relational simplifications
"Relational expression can be simplified" SHALL report, and its quick-fix SHALL rewrite:
- `~~r` → `r`;
- `r + r` and `r & r` → `r`, when both operands are the same expression;
- `^r + iden` and `iden + ^r` → `*r`;
- adjacent conjuncts `a in b` and `b in a` → `a = b`.

#### Scenario: Relational identities
- **WHEN** expressions read `~~succ`, `n.inbox + n.inbox`, `outbox & outbox`, `^succ + iden` and `iden + ^succ`
- **THEN** their quick-fixes turn them into `succ`, `n.inbox`, `outbox`, `*succ` and `*succ`

#### Scenario: Mutual inclusion
- **WHEN** a fact reads `Node.inbox in Message and Message in Node.inbox`
- **THEN** the quick-fix turns it into `Node.inbox = Message`

#### Scenario: Different operands
- **WHEN** expressions read `n.inbox + n.outbox` or `^succ + ^pred`
- **THEN** this inspection reports nothing for them

### Requirement: Logical equivalence intentions
The plugin SHALL offer intentions in an "Alloy" category of *Settings | Editor | Intentions*. They are never highlighted, and are offered with Alt+Enter for the innermost formula around the caret that they apply to:
- "Apply De Morgan's law": `not (a or b)` ↔ `not a and not b`, and `not (a and b)` ↔ `not a or not b`;
- "Replace 'or' with 'implies'": `not a or b` → `a implies b`;
- "Replace 'implies' with 'or'": `a implies b` → `not a or b`.

#### Scenario: De Morgan both ways
- **WHEN** the user invokes "Apply De Morgan's law" on `not (p or q)`, and then again on the result
- **THEN** the text reads `not p and not q`, and then `not (p or q)` again

#### Scenario: Implication to disjunction
- **WHEN** the user invokes "Replace 'implies' with 'or'" on `some inbox implies no outbox`
- **THEN** the text reads `no inbox or no outbox`

#### Scenario: Implication with an else
- **WHEN** the caret is on `implies` in `p implies q else r`
- **THEN** "Replace 'implies' with 'or'" is not offered

### Requirement: Negation of an operand
When a quick-fix or an intention negates an operand, it SHALL write the operand's negated form rather than add a `not`. That means removing a `not` the operand already has, negating its comparison (`=`/`!=`, `in`/`not in`, `<`/`>=`, …), or turning `some e` into `no e` and `no e` into `some e`. These negated forms SHALL also count as negations wherever an intention looks for a negated formula.

#### Scenario: Negating a negation or a comparison
- **WHEN** the user invokes "Apply De Morgan's law" on `not (not p and x = y)`
- **THEN** the text reads `p or x != y`

#### Scenario: Negated multiplicity
- **WHEN** the user applies the quick-fix to `not (some inbox implies some outbox)`
- **THEN** the text reads `some inbox and no outbox`, and not `some inbox and not some outbox`

### Requirement: Quantifier negation intentions
The plugin SHALL offer "Move negation into quantifier" on a negated `all` or `some` (`not all x: A | p` → `some x: A | not p`, `not some x: A | p` → `all x: A | not p`). It SHALL offer "Move negation out of quantifier" for the reverse, on an `all` or `some` whose body is negated.

#### Scenario: Negation moved in and out
- **WHEN** the user invokes "Move negation into quantifier" on `not (all n: Node | some n.inbox)`, and then "Move negation out of quantifier" on the result
- **THEN** the text reads `some n: Node | no n.inbox`, and then `not (all n: Node | some n.inbox)`

### Requirement: Temporal duality intentions
The plugin SHALL offer "Move negation into temporal operator", and its reverse, for these pairs: `not always p` ↔ `eventually not p`, `not eventually p` ↔ `always not p`, `not after p` ↔ `after not p`, `not historically p` ↔ `once not p`, `not once p` ↔ `historically not p`, `not (p until q)` ↔ `not p releases not q`, `not (p releases q)` ↔ `not p until not q`, `not (p since q)` ↔ `not p triggered not q`, and `not (p triggered q)` ↔ `not p since not q`.

#### Scenario: Negated always
- **WHEN** the user invokes "Move negation into temporal operator" on `not always some inbox`
- **THEN** the text reads `eventually no inbox`

#### Scenario: Negated until
- **WHEN** the user invokes it on `not (some inbox until no outbox)`
- **THEN** the text reads `no inbox releases some outbox`

#### Scenario: Before is not self-dual
- **WHEN** the caret is on `not before p`, or on `before not p`
- **THEN** neither direction of the intention is offered, because `before p` is false in the first state

### Requirement: Temporal distribution intentions
The plugin SHALL offer "Distribute temporal operator" on `always (p and q …)` → `always p and always q …`, `eventually (p or q …)` → `eventually p or eventually q …`, `historically (p and q …)` → `historically p and historically q …` and `once (p or q …)` → `once p or once q …`. It SHALL offer "Factor out temporal operator" for the reverse, on an `and` or `or` whose operands around the caret all start with that operator.

#### Scenario: Always over a conjunction
- **WHEN** the user invokes "Distribute temporal operator" on `always (some inbox and lone outbox)`, and then "Factor out temporal operator" on the result
- **THEN** the text reads `always some inbox and always lone outbox`, and then `always (some inbox and lone outbox)`

#### Scenario: Operators that do not distribute
- **WHEN** the caret is on `always` in `always (p or q)`, or on `eventually` in `eventually (p and q)`
- **THEN** "Distribute temporal operator" is not offered

### Requirement: Transpose join intention
The plugin SHALL offer "Swap join with transpose" on `e.~r` → `r.e` and on `r.e` → `e.~r`, when `e` is a set and `r` a binary relation. When `r` is itself a transpose `~s`, the result SHALL be `e.s`, rather than `e.~~s`.

#### Scenario: Join swapped
- **WHEN** the user invokes "Swap join with transpose" on `n.~succ`, and then on the result
- **THEN** the text reads `succ.n`, and then `n.~succ`

#### Scenario: Join with a transpose on the left
- **WHEN** the user invokes "Swap join with transpose" on `(~succ).n`
- **THEN** the text reads `n.succ`

#### Scenario: Other joins
- **WHEN** the caret is on the `.` of `r.n` where `r` is a ternary relation, or of `succ.succ` where both operands are binary
- **THEN** the intention is not offered

### Requirement: Suppression of simplifications
Each simplification inspection SHALL offer the IDE's "Suppress" actions. These write the same `// noinspection <id>` comment before the paragraph or command as the other Alloy inspections do.

#### Scenario: Suppressed for a fact
- **WHEN** the user picks "Suppress for paragraph" on a double negation in a fact
- **THEN** the line before the fact reads `// noinspection AlloySimplifiableBoolean`, and other facts are still reported
