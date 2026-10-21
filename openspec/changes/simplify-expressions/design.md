# Design

## Context

- **No expression trees in the PSI.** The native PSI does not parse expressions: `Alloy.bnf` keeps them as flat token runs in balanced groups. Precedence, and so the shape of every formula, is known only to the analyzer.
- **Existing inspection plumbing.** The existing inspections (`alloy-inspections`) already work this way. `AlloyModelInspection` gets the cached type-checked model from `AlloyModelService.model(file)` and maps analyzer `Pos`es to editor ranges through `AlloyInspectionContext`. `AlloyInspectionSuppressor` handles `// noinspection` for any Alloy inspection.
- **The analyzer's AST (`org.alloytools.alloy.core` 6.2.0) is precise about structure but loses some source text:**
  - `BaseExpr ::= LPAREN Expr RPAREN` returns the inner node, so parentheses leave no trace in the AST.
  - `and`/`or` are built by `ExprList.makeAND`/`makeOR`, which flatten nested lists into one n-ary `ExprList` and drop `true`/`false` constants. A block `{ p q }` is an `AND` list as well.
  - `ExprBinary.Op.DOMAIN` rewrites `S <: S.f`-like forms. `;` is desugared into `and`/`after`. Casts (`CAST2INT`, `CAST2SIGINT`) and `NOOP` wrappers have no text of their own.
  - Macro calls are expanded in place. The expanded nodes keep the macro body's positions, and argument nodes keep the call site's positions.
  - `implies … else` is an `ExprITE`; a plain `implies` is an `ExprBinary.IMPLIES`. `!=`, `not in`, `!<` and the like are their own `ExprBinary` ops. `no x: A | p` stays an `ExprQt.NO`.
  - Anonymous command bodies become hidden `run$N`/`check$N` funcs or assertions, positioned in the file.
  - `Expr.isSame` compares structure, and `Expr.hasVar(ExprVar)` finds a bound variable, since bound variables are shared objects after type-checking.
- **Precedence** (from `CompParser`, loosest first):
  1. `;`
  2. quantifiers and `let` (`Bind`); these extend to the right, and may end any binary formula without parentheses (the grammar's `…A` variants)
  3. `or`
  4. `iff`
  5. `implies` / `else`, right-associative
  6. `and`
  7. `until`, `releases`, `since`, `triggered`
  8. prefix `not`, `always`, `eventually`, `after`, `before`, `historically`, `once`
  9. comparisons and `no`/`some`/`lone`/`one`/`set` tests
  10. then the relational levels: shift, `+`/`-`, `#`, `++`, `&`, `->`, `<:`/`:>`, `[]`, `.`, and the unary `~`, `^`, `*`.

## Goals / Non-Goals

**Goals:**
- No false rewrites: a quick-fix or intention never changes the meaning of the model, and never produces text that fails to type-check.
- Rewrites read like the user's own text: operands are copied verbatim, the operator style is kept, and parentheses are added only where precedence requires them.
- One rule engine serves both inspections and intentions.

**Non-Goals:**
- A real expression grammar in `Alloy.bnf`.
- Rewrites inside field and signature declarations, command scopes, or macro declarations.
- Further identities: `r - r`, `r + none`, `r & univ`, unused quantified variables, redundant parentheses, `after` pushed through primes. These can be added later as rules.
- User-defined rules, or a "simplify everything" action beyond the IDE's standard *Fix all* of an inspection.
- Bounded-integer subtleties. The cardinality rules change behavior only when `#e` would overflow, and then the result is what the user meant (see Risks).

## Decisions

### 1. Rules match the analyzer's AST and are anchored to written tokens

An `AlloyRewriteRule` belongs to a `Family`: `BOOLEAN`, `CARDINALITY`, `TEMPORAL`, `QUANTIFIER` or `RELATIONAL` for the inspections, or a named intention. It has a kind (`SIMPLIFICATION` or `EQUIVALENCE`) and a function `match(node, ctx): Rewrite?`. A `Rewrite` carries the range it replaces, the pieces of its replacement (§3), a message and a fix name.

An `AlloyRewriteWalker` visits the roots declared in the file: `getAllFacts`, `getAllAssertions`, `getAllFunc` (including the hidden `run$`/`check$` bodies), and every user signature's `getFacts`. It is a `VisitQuery` that keeps a stack of ancestors, so that a rule can see its node's parent operator and whether the node is the rightmost operand (§3). Casts and `NOOP` are looked through, with `deNOP` and a cast-stripping helper.

A node is **written** when the PSI leaf at its `pos` is the token of its operator: for example `NOT` (`not` or `!`) for `ExprUnary.NOT`, `ALWAYS` for `ALWAYS`, `HASH` for `CARDINALITY`. An n-ary `ExprList` is written when each argument it uses is. A rewrite is offered only when the matched node is written, and when every range it uses lies inside the PSI paragraph that contains the match and outside any `AlloyMacroDecl`.

This one check covers every case of the "Only written text is rewritten" requirement:
- desugarings such as `;`, whose generated `after` sits on the `;` token;
- macro expansions, whose nodes lie in another paragraph or inside a macro declaration;
- argument nodes substituted from a call site;
- opened modules, whose positions are in another file.

*Alternative considered:* a precedence parser over the PSI tokens. Rejected (option B in the exploration). A precedence bug there would silently produce a quick-fix that changes the meaning, whereas a lossy AST can only cause a missed match. It would also duplicate the analyzer's type information, which some rules need (arity, formula vs. set).

### 2. Source ranges and parentheses

`AlloySourceExpr(node, range, parenthesized)` holds the editor range of `node.span()`, widened over parentheses written around it. Widening repeatedly steps over whitespace and comments to the neighbouring leaves; while they are `LPAREN` before and `RPAREN` after, it extends the range. They are necessarily a matching pair, because the span between them is balanced. A node whose span cannot be mapped, because it is `Pos.UNKNOWN` or in another file, yields no rewrite.

The replaced range of a rewrite is the matched node's widened range: written parentheses around the match are replaced too. The result is wrapped again in parentheses only if its context needs them (§3).

### 3. Replacement text: source slices and precedence-aware parentheses

A replacement is built from a small tree of `Piece`s:
- `Slice(AlloySourceExpr)`: the operand's exact text. If the operand was written in parentheses, they are kept, and the slice then counts as atomic.
- `Op(level, token, children)`: an operator written with the chosen style (§4).
- `Bind(prefix, decls, body)`: for quantifier rewrites. It reuses the slices of the declarations and of the body, and may add or remove `disj`.

Rendering compares each child's level with what its position needs, using the precedence table of Context. A child is wrapped in `(`…`)` when its level is looser than its parent operator allows. This also applies to the left operand of a left-associative operator at the same level and to the right operand of a right-associative one. A `Bind`, or a slice whose top node is a quantifier, `let` or `implies … else`, counts as "open-ended": it is wrapped unless it ends up as the rightmost operand.

The rendered result is checked the same way against the matched node's context. That context comes from the walker's ancestor stack: the parent operator, and whether the node is the rightmost operand. A match that is a whole block line, a paragraph body, or a quantifier or `let` body needs no parentheses.

A slice's level is the level of its top analyzer node. A slice keeps any comments and line breaks inside it. A rewrite that drops text (such as the dropped `not`s) drops only operator tokens and the whitespace between them, never a comment. A match whose dropped tokens include a comment is not offered.

*Alternative considered:* always parenthesize non-atomic operands. Rejected: `this/A.f in B and some g` would become `(this/A.f in B) and (some g)`, which is noisy for the most common rewrites. The precedence table is small, fixed by the grammar, and checked by round-trip tests (§8).

### 4. Operator style

A rewrite is symbolic when the tokens it replaces include at least one of `!`, `&&`, `||`, `=>` and `<=>`, and none of `not`, `and`, `or`, `implies` and `iff`. Otherwise it uses keywords. The style decides how `Op` pieces are written: `not`/`!`, `and`/`&&`, `or`/`||`, `implies`/`=>`, `iff`/`<=>`. Negated comparisons follow the same rule (`not in`/`!in`). Temporal and multiplicity keywords have no symbolic form.

### 5. Negation helper

`negate(operand): Piece` returns the operand's negated form:
- `not p` gives the slice of `p`;
- a comparison gives the comparison with its operator swapped. The pairs are `=`/`!=`, `in`/`not in`, `<`/`>=`, `=<`/`>`, `>`/`=<`, `>=`/`<`, and the `!<`-style ops;
- `some e` gives `no e`, and `no e` gives `some e`;
- any other operand gives `Op(not, operand)`.

`isNegation(operand)` recognizes the same forms, and `unnegate` is the inverse. Quick-fixes (`not (a implies b)` → `a and not b`) and intentions (De Morgan, quantifier and temporal dualities) all use this one helper. That keeps the results free of `not not` and `not some` without a second pass.

### 6. Inspections

`AlloySimplificationInspection(family)` extends `AlloyModelInspection`. There is one subclass per family:
- `AlloySimplifiableBoolean`
- `AlloySimplifiableCardinality`
- `AlloySimplifiableTemporal`
- `AlloySimplifiableQuantifier`
- `AlloySimplifiableRelational`

Its `check` runs the walker with the family's `SIMPLIFICATION` rules. When a rule matches, the walker does not descend into the matched node for that inspection. This gives the "outermost only" behavior: inner matches show up on the next highlighting pass, after the fix.

- **Problem placement.** The problem is placed on the replaced range as a `WEAK_WARNING`, with the rule's message, for example "Double negation". The element is the paragraph that contains it, so that `AlloyInspectionSuppressor` finds it.
- **Quick-fix.** The quick-fix (`AlloyRewriteFix`) holds the rendered replacement and the original text. On apply, it re-reads the descriptor's current range, checks that the text there is unchanged, replaces it and commits. If the text has changed it does nothing: the file was edited after highlighting, and the next pass reports afresh.
- **Fix names.** They come from each rule: "Remove double negation", "Simplify to 'a and b'" (the replacement is shown when it is at most 40 characters, otherwise "Apply De Morgan's law"), "Replace with 'no'", "Replace with 'iff'", "Use 'disj'", and so on.
- **Adjacent-conjunct rules.** These are converse implications and mutual inclusion. They match an `ExprList.AND` whose arguments `i` and `i+1` are both written. The replaced range runs from the start of the first argument to the end of the second, including the line break between block lines.

Registration in `plugin.xml`: `localInspection` with `groupPath="Alloy"` and `groupName="Simplifications"`, `level="WEAK WARNING"`, `enabledByDefault="true"`, and names in `AlloyBundle`. Each has an HTML description under `inspectionDescriptions/` with before/after examples. The cardinality description notes the overflow safety.

### 7. Intentions

Each intention extends `PsiElementBaseIntentionAction` and registers an `intentionAction` with `<language>Alloy</language>` and `<category>Alloy</category>`. The intentions are:
- `AlloyDeMorganIntention`
- `AlloyOrToImpliesIntention`
- `AlloyImpliesToOrIntention`
- `AlloyQuantifierNegationIntention` (both directions, with the text depending on direction)
- `AlloyTemporalNegationIntention` (both directions)
- `AlloyTemporalDistributionIntention` (distribute and factor out)
- `AlloyTransposeJoinIntention`

`isAvailable` runs on the EDT, so it uses `AlloyModelService.availableModel`, which returns only the cached, up-to-date model there. When no fresh model is cached, the intention is not offered; it becomes available once highlighting has run. The intention then finds the innermost written node around the caret that one of its `EQUIVALENCE` rules matches. To avoid a full walk per caret move, an `AlloyRewriteIndex` maps each written node's range to its node and ancestor context. It is built lazily per `AlloyModelResult.Model` and cached in a `WeakHashMap` keyed by the model's `CompModule`. `invoke` re-checks the match and applies the same text replacement as the quick-fix.

Each intention has a description directory under `intentionDescriptions/<ClassName>/` (`description.html`, `before.als.template`, `after.als.template`).

**`before` exclusion.** `AlloyTemporalNegationIntention` has no rule for `before`. Under Alloy 6 trace semantics, `before p` is false in the first state, so `not before p` is not `before not p`. A unit test pins that neither direction is offered. `after` is included because traces are infinite, which makes it self-dual.

### 8. Testing strategy

- **Per-rule fixture tests** (`BasePlatformTestCase`), following `AlloyMissingFrameConditionTest`. For each spec scenario they check the problems reported and their text, then apply the fix or intention and use `checkResult`. They also cover negative cases.
- **Equivalence test.** For each rule, an `AlloyRewriteEquivalenceTest` builds a small module where formula variables are propositions over `var lone sig P, Q, R {}` (`some P`, …) and sets are `var` subsets of a small signature. It renders the rewrite of the scenario's formula. It then checks the assertion `always (before iff after)` (or `before = after` for expressions) with the analyzer (`TranslateAlloyToKodkod`, SAT4J, `for 3 but 1..4 steps`), expecting no counterexample. This proves soundness under Alloy's own semantics, `before`'s non-duality included: the test asserts that the naive dual does have a counterexample.
- **Round-trip test.** Each rendered replacement, put back into its context, must type-check, and its analyzer AST must be `isSame` with the AST obtained by parsing the expected text. This guards the precedence table (§3).
- **Corpus test** (`PracticalAlloySimplificationsTest`):
  - it runs the five inspections over every `practicalalloy` fixture and compares the problems with an explicit expected list, as `PracticalAlloyInspectionsTest` does;
  - it applies every reported fix on a copy and checks that the model still type-checks.

  `PracticalAlloyModelsTest` and `PracticalAlloyInspectionsTest` must keep passing unchanged.

## Risks / Trade-offs

- **[Risk] `Expr.span()` may not match the written text for some node kinds** (for example a box join `a[b]` or a call's closing bracket). → Only written nodes are matched (§1). The round-trip and corpus tests exercise every rule on real models. If a span cannot be mapped, the rewrite is skipped.
- **[Risk] A wrong precedence entry yields a fix that changes meaning.** → There is one precedence table, derived from `CompParser`. The round-trip test compares ASTs, and the equivalence test compares semantics. When unsure, parenthesize: the table errs towards looser levels for operands.
- **[Trade-off] Cardinality rewrites are equivalent only up to integer overflow.** `#e = 1` and `one e` differ when `#e` wraps around the bitwidth. The rewrite is still offered: the multiplicity form is what the formula means, and it is the reason the inspection exists. The description says so, and the equivalence test runs with a bitwidth large enough for its scopes.
- **[Trade-off] Intentions need a fresh cached model.** Right after typing, Alt+Enter may not offer them until highlighting has finished. This matches how the IDE behaves when analysis is pending.
- **[Risk] Adjacent-conjunct rules delete text between block lines.** → Only whitespace may lie between the two conjuncts. If there is a comment, the rule is not offered (§3).
- **[Trade-off] Weak warnings on the book's models.** Some fixtures will get simplification warnings, such as `#x = 0`-style tests. The corpus test lists them explicitly, so each one is a reviewed true positive. Users who dislike a family can turn it off.

## Migration Plan

Nothing to migrate. The inspections and intentions are new, enabled by default, and can be turned off in the settings.
