# Tasks

## 1. Rewrite engine

- [x] 1.1 Add the `simplifications/` package with `AlloySourceExpr`: the editor range of an analyzer node, widened over written parentheses (design §2). Add the "written node" check: the operator token at `pos`, with all ranges inside the containing paragraph and outside any macro declaration (design §1). Verify with `AlloySourceExprTest`:
  - `((not a))` widens to both pairs;
  - `(a) and (b)` keeps each operand's own pair;
  - nodes from a macro expansion, from a macro parameter's argument, from `;`, and from an opened module are not written.
- [x] 1.2 Implement `AlloyRewriteWalker` over the file's facts, assertions, funcs (including hidden `run$`/`check$` bodies) and signature facts. It keeps an ancestor stack (parent operator, rightmost-operand flag), looks through `NOOP` and casts, and does not descend into a matched node (design §1, §6). Verify with a test that a counting rule visits every written `not` of a fixture once, including those in an anonymous `run { … }` body and a signature fact, and that nested matches yield only the outermost.
- [x] 1.3 Implement the `Piece` renderer with the precedence table from `CompParser`, open-ended `Bind`/`else` handling, parentheses for the result in its context, and operator style detection (design §3, §4). Verify with `AlloyRewriteRenderTest`:
  - the spec's "Rewrites keep the meaning and the user's text" and "Operator style is kept" scenarios render as written;
  - a quantifier moved from the rightmost operand is parenthesized;
  - a round-trip check re-type-checks each rendered result in context and compares its AST with `isSame` to the expected text's AST.
- [x] 1.4 Implement `negate`, `isNegation` and `unnegate` (design §5). Verify with unit tests over `not p`, every comparison pair, `some`/`no`, and a plain formula.
- [x] 1.5 Add `AlloyRewriteEquivalenceTest`'s harness. It builds a module with `var lone sig` propositions and `var` sets, renders a rule's rewrite, and checks `always (before iff after)` (or `=`) with the analyzer for `3 but 1..4 steps` (design §8). Verify on a hand-written correct rewrite (passes) and on `not before p` → `before not p` (finds a counterexample).

## 2. Simplification inspections

- [x] 2.1 Add the `AlloySimplificationInspection` base and the `AlloyRewriteFix` quick-fix, which checks that the text is unchanged before replacing (design §6). Register the five inspections in `plugin.xml` under `groupPath="Alloy"`/`groupName="Simplifications"` as weak warnings, with names in `AlloyBundle` and HTML descriptions. Verify with an extension of `AlloyInspectionRegistrationTest`: the five IDs are listed under "Alloy | Simplifications", enabled, as weak warnings, with descriptions. Also verify that a model with a type error reports nothing, and that "Suppress for paragraph" writes `// noinspection AlloySimplifiableBoolean`.
- [x] 2.2 Implement the Boolean rules: double negation, n-ary De Morgan collapse, negated implication (not `else`), negated comparisons, and adjacent converse implications with a range across block lines. Verify with `AlloySimplifiableBooleanTest`, covering every scenario of "Boolean simplifications" and "Converse implications", and with an equivalence test case per rule.
- [x] 2.3 Implement the emptiness and cardinality rules, including mirrored forms and `e = none` / `e != none` for sets only. Verify with `AlloySimplifiableCardinalityTest`, covering every scenario of "Emptiness and cardinality simplifications" (with the negative cases), and with equivalence test cases.
- [x] 2.4 Implement the temporal rules: idempotence for `always`, `eventually`, `historically` and `once`, and the two absorptions. Verify with `AlloySimplifiableTemporalTest`, covering every scenario of "Temporal simplifications" (`after after` and `before before` are not reported), and with equivalence test cases.
- [x] 2.5 Implement the quantifier rules: negated quantifiers, `all … | not p`, the membership eliminations (single variable, plain or `one` bound, `hasVar` check) and `disj` introduction (exactly two names, not already `disj`). Verify with `AlloySimplifiableQuantifierTest`, covering every scenario of the three quantifier requirements, including the three-variable and variable-dependent negatives, and with equivalence test cases.
- [x] 2.6 Implement the relational rules: `~~r`, `r + r` / `r & r` by `isSame`, `^r + iden` in either order, and adjacent mutual inclusion. Verify with `AlloySimplifiableRelationalTest`, covering every scenario of "Relational simplifications", and with equivalence test cases.
- [x] 2.7 Add `PracticalAlloySimplificationsTest`. It runs the five inspections over every `practicalalloy` fixture and compares the reported problems with a reviewed expected list. It also applies each fix on a copy and checks that the model still type-checks. Verify that it passes, and that `PracticalAlloyModelsTest` and `PracticalAlloyInspectionsTest` pass unchanged.
- [x] 2.8 Add a "Simplifications" section to the DEV.md manual checklists: the settings subgroup, weak-warning highlighting, one fix per family, the nested double negation, and suppression. Add the simplification inspections to the README feature list. Verify by re-reading both against the spec's inspection requirements.

## 3. Intentions

- [x] 3.1 Add `AlloyRewriteIndex`, a lazily built, per-model map from written-node ranges to nodes and contexts, cached weakly by `CompModule`. Add the intention base, which uses `availableModel` and offers the innermost matching node around the caret (design §7). Verify with a test that an intention is offered after highlighting, and is not offered while the model has a type error.
- [x] 3.2 Implement "Apply De Morgan's law", "Replace 'or' with 'implies'" and "Replace 'implies' with 'or'", using `negate`. Register them under the "Alloy" category with description directories. Verify with `AlloyLogicalIntentionsTest`, covering every scenario of "Logical equivalence intentions" and "Negation of an operand", and with equivalence test cases.
- [x] 3.3 Implement "Move negation into/out of quantifier". Verify with a test of the "Negation moved in and out" scenario and with equivalence test cases.
- [x] 3.4 Implement "Move negation into/out of temporal operator" for `always`/`eventually`, `after`, `historically`/`once`, `until`/`releases` and `since`/`triggered`, with no rule for `before`. Verify with `AlloyTemporalIntentionsTest`, covering every scenario of "Temporal duality intentions" (including "Before is not self-dual"), and with equivalence test cases.
- [x] 3.5 Implement "Distribute temporal operator" and "Factor out temporal operator". Verify with tests covering the "Temporal distribution intentions" scenarios and with equivalence test cases.
- [x] 3.6 Implement "Swap join with transpose" with arity checks. Verify with tests covering the "Transpose join intention" scenarios and with an equivalence test case.
- [x] 3.7 Add the intentions to the DEV.md "Simplifications" checklist (*Settings | Editor | Intentions* category, previews, one intention per kind, `before` not offered) and to the README feature list. Verify by re-reading both against the intention requirements.

## 4. Integration

- [x] 4.1 Run `./gradlew test` and verify that it passes. Run `openspec validate simplify-expressions --strict` and verify that it passes.

## Workflow follow-up

- Tick the manual DEV.md items once the automated tests pass, and leave the manual checks in the DEV.md checklists.
- Archive the change once it has been reviewed.
