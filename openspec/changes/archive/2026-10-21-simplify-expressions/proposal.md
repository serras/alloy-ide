# Proposal

## Why

Alloy formulas often end up in forms that are harder to read than they need to be. This happens when they grow through edits, when they are translated from prose, or when a modeller is still learning the language. Common cases are double negations, negated disjunctions of negations, `#e = 0` instead of `no e`, `all x: A | x in B` instead of `A in B`, and `always always p`. Some of these are worse than unreadable: `#e = 0` overflows silently under a small bitwidth, while `no e` does not.

Modellers also often want to move between equivalent forms on purpose. Examples are pushing a negation through `always`, applying De Morgan's law, or turning `not a or b` into `a implies b`. Doing this by hand is error-prone, especially with temporal operators, where an intuitive dual can be wrong: `not before p` is not `before not p`.

The plugin already type-checks every model and has an inspection framework (`alloy-inspections`). It can therefore offer these rewrites as the user edits, with exact precedence taken from the analyzer.

## What Changes

- **Five new inspections**, one per family of rewrites. They sit in an "Alloy | Simplifications" group of *Settings | Editor | Inspections*. Each is a weak warning, and each offers a quick-fix that replaces the formula or expression with its simpler form:
  - **Boolean**: double negation, negated `or`/`and` of negations, a negated `implies`, a pair of converse implications turned into `iff`, and negated comparisons (`not (a = b)` → `a != b`, `not (a in b)` → `a not in b`, `not (i < j)` → `i >= j`, …).
  - **Emptiness and cardinality**: `not some e` → `no e`, `not no e` → `some e`, `e = none` → `no e`, `#e = 0` → `no e`, `#e > 0` → `some e`, `#e = 1` → `one e`, `#e =< 1` → `lone e`, and their variants.
  - **Temporal**: idempotent operators (`always always p` → `always p`, and likewise for `eventually`, `historically` and `once`), and absorption (`eventually always eventually p` → `always eventually p`, `always eventually always p` → `eventually always p`).
  - **Quantifier**: a negated `some`/`no`/`all` that becomes `no`/`some`/`some … not`, `all x: A | not p` → `no x: A | p`, `all x: A | x in B` → `A in B`, `some x: A | x in B` → `some A & B`, `no x: A | x in B` → `no A & B`, and `x != y implies p` / `x != y and p` turned into a `disj` quantifier.
  - **Relational**: `~~r` → `r`, `r + r` and `r & r` → `r`, `^r + iden` → `*r`, and `a in b and b in a` → `a = b`.
- **New intentions (Alt+Enter, no highlighting)** for rewrites between equivalent forms where neither form is simpler. They are in an "Alloy" category of *Settings | Editor | Intentions*:
  - De Morgan's law, both ways.
  - `not a or b` ↔ `a implies b`.
  - Moving a negation into or out of a quantifier (`not all x | p` ↔ `some x | not p`, `not some x | p` ↔ `all x | not p`).
  - Moving a negation into or out of a temporal operator: `always`/`eventually`, `after`, `historically`/`once`, `until`/`releases` and `since`/`triggered`. `before` is deliberately left out, since it is not self-dual.
  - Distributing `always` over `and` and `eventually` over `or` (and `historically` and `once` alike), both ways.
  - `e.~r` ↔ `r.e`.
- The new inspections follow the rules of the existing Alloy inspections:
  - they report nothing while the model has errors;
  - they look only at text written in the file;
  - they can be suppressed with a `// noinspection <id>` comment.
- The README's feature list and the DEV.md manual checklists cover the simplifications and intentions.

## Capabilities

### New Capabilities
- `alloy-simplifications`: rewrites of Alloy formulas and expressions into simpler or equivalent forms. These are found on the type-checked model, offered as inspections with quick-fixes for strict simplifications and as intentions for equivalent forms, and applied as text edits that keep the meaning and the user's own text.

### Modified Capabilities
<!-- none: the existing Alloy inspections keep their requirements; the new inspections use the same group root and the same suppression comment -->

## Impact

- **Code.** A new `simplifications/` package:
  - a rule engine that matches the analyzer's type-checked `Expr` nodes (`ExprUnary`, `ExprBinary`, `ExprList`, `ExprQt`) and maps them back to source text, widened over any surrounding parentheses;
  - a builder for replacement text, made from slices of the source;
  - the five `AlloyModelInspection` subclasses and the intention actions.
- **`plugin.xml`.** Five `localInspection` entries and the `intentionAction` entries, with descriptions under `resources/inspectionDescriptions/` and `resources/intentionDescriptions/`.
- **Dependencies.** No new ones. The code uses the public analyzer AST of `org.alloytools.alloy.core` 6.2.0.
- **Tests.**
  - Unit tests per rule.
  - An equivalence test that checks, with the analyzer, that each rewrite gives an equivalent formula.
  - A corpus test over the Practical Alloy fixtures listing every simplification reported there.
  - The existing `PracticalAlloyModelsTest` and `PracticalAlloyInspectionsTest` are unaffected: they list WARNING-level problems and the four existing inspections, respectively.
