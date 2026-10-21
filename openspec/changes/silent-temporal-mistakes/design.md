# Design

## Context

- **The existing inspection framework.** `AlloyModelInspection` gives each check the cached type-checked model and maps analyzer positions to PSI (`AlloyInspectionContext`). `AlloyCommandInspection` already iterates over every command of the file, including each part of a `=>` chain. `AlloyScopeText`/`AlloyScopeEdit`/`AlloyScopeFix` edit a command's written scope and select the inserted number for editing. `AlloyInspectionSuppressor` handles `// noinspection` for any Alloy inspection.
- **`AlloyFrameAnalysis` already summarizes a `Func` body, memoized and through calls.** It records the mutable relations the body mentions and the ones it constrains under `'`/`after`. It also records whether it constrains the `next` state and whether it uses a `future` operator (`always`, `eventually`, `until`, `releases`, `;`). `isEvent(func)` is `next && !future`. Past operators are not tracked yet.
- **The analyzer AST.** Macros are expanded in place, keeping the macro body's positions; arguments keep the call site's positions. `;` is desugared into `after`, at the one column of `;` (`AlloyFrameAnalysis.isSequence`). Quantified variables and predicate/function parameters are `ExprVar`s, rigid across states. A `let` keeps an `ExprLet` with its variable and definition.
- **Integers in the analyzer** (`org.alloytools.alloy.core` 6.2.0):
  - Number literals are lexed as non-negative `ExprConstant.NUMBER`s; a minus sign is an operator.
  - `ScopeComputer` uses `Command.bitwidth` when it is written (`N int`), and 4 otherwise. The `shouldUseInts` condition is hard-wired to `true`.
  - The range is `Util.min(bw)..Util.max(bw)`.
  - `TranslateAlloyToKodkod` turns a literal into `IntConstant` with its range check commented out, so out-of-range literals wrap modulo `2^bw`.
- **Facts.** `CompModule.getAllFacts()` lists the module's facts, anonymous ones included as `fact$N`. `Sig.getFacts()` lists a signature's appended facts. `getAllReachableFacts()` adds the facts of opened modules.

## Goals / Non-Goals

**Goals:**
- High confidence for highlighted problems: none of the book's final models gets a weak warning from these inspections.
- Deliberate initial-state facts are never highlighted.
- The quick-fixes are mechanical text edits, consistent with the existing ones.

**Non-Goals:**
- Assertions and `run` bodies are not checked for missing `always`. A scenario or an assertion about the first state is legitimate there.
- Overflow of computed integers (`#Node` when the scope of `Node` exceeds the maximum integer, or `fun/add` results). Only literals are checked.
- `after`, `always` and the like over static formulas. Only `'` is checked.
- Reporting a macro's call site when a static expression is passed to a macro that primes it.

## Decisions

### 1. One temporal summary, extended

`AlloyFrameAnalysis.Summary` gains `temporal: Boolean`. It is true when the body uses any temporal operator, directly or through calls: `'`, `after`, `before`, `always`, `eventually`, `historically`, `once`, `until`, `releases`, `since`, `triggered` or `;`. The analysis is not otherwise changed, so the missing-frame-condition inspection behaves as before. The extension is a single extra flag in the same walk, memoized the same way.

*Alternative considered:* a separate walker for facts. Rejected, because it would duplicate the call-following and cycle handling already tested in `AlloyFrameAnalysisTest`.

### 2. Fact checks (`AlloyFactOnlyAtStartInspection`, ID `AlloyFactOnlyAtStart`)

The roots are the file's facts from `getAllFacts()` and the facts of the file's user signatures from `Sig.getFacts()`. A root's PSI is the `AlloyFactDecl`, or the signature's appended `block`, that contains the start of its span.

**Weak warning.** A `VisitQuery` keeps a depth counter of enclosing `ALWAYS`, `EVENTUALLY`, `UNTIL` and `RELEASES` (either operand). At depth 0, the occurrences are:
- an `ExprUnary.PRIME`;
- a written `ExprUnary.AFTER`, meaning not `isSequence`;
- an `ExprCall` whose callee `isEvent`.

A `;` (a sequence `AFTER`) increases the depth instead, because it describes a scenario. The first occurrence in source order is reported:
- If its range lies in the fact, the problem goes there. Otherwise, for example inside an expanded macro, it goes on the fact's header.
- The message names the occurrence and says it constrains only the transition out of the first state.
- If the occurrence is under an `after` at depth 0, the problem goes on that `after` instead, and the message names the shifted transition.

**Information.** If there is no weak warning, and the root's summary has `temporal == false` and a non-empty `mentions`, the fact gets a `ProblemHighlightType.INFORMATION` problem on its header. This uses the same mechanism as `AlloyExactParameterScope`'s overall-scope problem. The header is the `fact` keyword and name, or, for a signature fact, the signature's name. Calls are followed through the summary, so `fact { init }` with `pred init { no inbox }` counts.

Both levels belong to one inspection, so a user can turn them off together. Its default level is WEAK WARNING.

*Alternative considered:* a weak warning on every fact over mutable state, or one that skips facts named `init`. Rejected in exploration: init facts are everywhere in the book's models, and naming is not something to rely on.

### 3. "Wrap in always"

`AlloyWrapInAlwaysFix` edits the fact's `block`, found again from the PSI at fix time:
- **One-line block:** `{ X }` becomes `{ always { X } }`.
- **Multi-line block:**
  - `always {` is inserted on the line after `{`;
  - every non-blank body line gets one more indentation unit;
  - a matching `}` is inserted before the closing brace's line.

  The unit is the body's indentation minus the declaration's, or two spaces when that is empty. It reuses the line and indentation helpers of `AlloyFrameConditionFix`, which move to a shared `AlloyTextEdits` object. Comments in the body move with their lines.

### 4. Static primes (`AlloyStaticPrimeInspection`, ID `AlloyStaticPrime`)

The roots are the file's facts, assertions, funcs (hidden `run$`/`check$` bodies included) and signature facts. For each written `ExprUnary.PRIME`, `isMutable(sub)` walks the operand:
- a `Sig` or `Field` with `isVariable` is mutable;
- an `ExprCall` is mutable when its callee's summary `mentions` something;
- an `ExprVar` bound by an enclosing `ExprLet` is mutable when its definition is. The walk keeps a map from let variables to their definitions.

Any other `ExprVar` is a quantified variable or a parameter, and so rigid.

A prime is skipped in these cases:
- its position lies inside an `AlloyMacroDecl`;
- its operand's span lies outside the paragraph of the prime, meaning a macro argument;
- the PSI leaf at the prime's end is not the `'` token, meaning an analyzer-built prime.

The message is chosen by the operand:
- When the operand, after `deNOP`, is a parameter of the enclosing func, the message explains that parameters are rigid and suggests a macro.
- When it is a quantified variable, the message says it is rigid.
- Otherwise, it names the static relations.

`AlloyRemovePrimeFix` deletes the `'` token.

### 5. Integer literals (`AlloyIntLiteralRangeInspection`, ID `AlloyIntLiteralRange`)

This is an `AlloyCommandInspection`. For each command:
- **Range.** `bw = command.bitwidth.takeIf { it >= 0 } ?: 4` and `max = Util.max(bw)`. A command with `0 int` has no integers; it is skipped, since every literal is then meaningless and the analyzer's behavior there is not worth modelling.
- **Reachability.** The command depends on `command.formula`, every expression of `getAllReachableFacts()`, and the bodies of every `Func` called from those, transitively. The calls are found with a visited set of funcs.
- **Collection.** It collects written `ExprConstant.NUMBER` nodes in the file with `num > max`, where the PSI leaf at `pos` must be a `NUMBER` token.

After all commands are checked, the results are grouped by literal, and each literal gets one weak warning:
- **Message.** It lists each affected command with its bitwidth, range and wrapped value. The wrapped value is `((n - min) mod 2^bw) + min`. Anonymous commands are named by their keyword and line, as in "the `run` at line 12".
- **Fixes.** There is one fix per affected command: "Set the bitwidth of C to N int", where `N` is the smallest bitwidth with `Util.max(N) >= n`, capped at the analyzer's maximum of 30. The fix uses a new `AlloyScopeEdit.SetBitwidth(N)`:
  - if the scope has an `int` item, its number is replaced;
  - otherwise `N int` is added as an item, the same way `AddItem` adds one;
  - a command without `for` gets ` for N int` after its body or name.

  The number is selected for editing, as in the other scope fixes.

### 6. Registration and descriptions

There are three `localInspection` entries in the "Alloy" group, enabled as `WEAK WARNING`, with `AlloyBundle` names and HTML descriptions under `inspectionDescriptions/`. `AlloyInspectionRegistrationTest` and the spec's "Inspections listed" scenario grow to seven inspections.

## Risks / Trade-offs

- **[Risk] Signature facts may be implicitly under `always` in Alloy 6.** In that case the fact checks would be wrong for them. → A test runs the analyzer on `sig A { var f: set A } { lone f }` to check that a later state can violate it, before signature facts are included. If they turn out to be implicitly temporal, they are dropped from the roots, and the spec's mention of signature facts goes with them.
- **[Risk] A deliberately non-temporal fact over mutable state** (an invariant of the first state only) is shown at information level. → It is not highlighted. Alt+Enter offers the fix only on request, and suppression works.
- **[Trade-off] One warning per fact.** A fact with several next-state occurrences shows only the first one. After wrapping, all of them are fixed at once.
- **[Risk] Literal reachability misses funcs used only through macros.** → Macros are expanded in the bodies, so their calls and literals are seen at the expansion. A literal written inside a macro is reported at its written position, and grouped across expansions.
- **[Trade-off] The default bitwidth is applied even when the model uses no integers.** This matches the analyzer, whose `shouldUseInts` is always `true`.

## Migration Plan

Nothing to migrate. The three inspections are new and enabled by default.
