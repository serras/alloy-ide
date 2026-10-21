# Design

## Context

- `AlloyModelService.model(file)` parses and type-checks a file once per version of its text. It returns `AlloyModelResult.Model` with the analyzer's `CompModule`, and the analyzer positions map to editor ranges through `AlloyPositions`.
- The native PSI does not parse expressions: the grammar's comment says so. A command's scope is a flat run of tokens (`scope_item`) inside `AlloyCommandDecl`.
- Anything that needs expression structure or resolved signatures therefore has to come from the analyzer's AST. This includes primes, calls, field types, and what a scope name refers to. The PSI is used only to place problems and to edit text.
- The analyzer's `ScopeComputer`, which decides every signature's final bound, is package-private, and its `compute` builds a whole `A4Solution`. Its public inputs are `Command.overall`, `Command.scope` (`CommandScope`: `sig`, `isExact`, `startingScope`, `pos`), `Command.additionalExactScopes` (the signatures made exact by `exactly` module parameters, such as `util/ordering`'s) and `Command.maxseq`.
- `PracticalAlloyModelsTest` highlights every book model and compares the problems of severity WARNING and above with an expected list.

## Goals / Non-Goals

**Goals:**
- Few false positives on the book's models, which serve as the regression corpus.
- Problems are attached to the text the user would edit, and the quick-fixes are mechanical text edits.

**Non-Goals:**
- Recomputing every rule of `ScopeComputer`. Only the bounds of top-level signatures and of `seq` are needed.
- Per-branch frame analysis. A field that is constrained in one branch of a disjunction or `implies … else` and missing in another is not reported.
- Inspections for opened modules. Each file is inspected on its own declarations.
- Quick-fixes that use a user's frame macro (such as `unchanged[f]`). The fix always writes `f' = f`.

## Decisions

### 1. Analyzer AST for facts, PSI for placement

Each inspection is a `LocalInspectionTool`.
- Its `checkFile` calls `AlloyModelService.model(file)` (inspections run in a background read action) and returns nothing unless the result is `Model`.
- It walks the analyzer objects whose `pos.filename` is the file: `getAllCommands()` for the scope checks, and the module's own `getAllFunc()` predicates for frames.
- Each one is mapped to PSI through `AlloyPositions.textRange`: the `AlloyCommandDecl` that contains `Command.pos`, the token run of a `CommandScope.pos`, or the name of the `AlloyPredDecl`.

*Alternative considered:* a PSI-only analysis. Rejected, because the PSI cannot see primes, calls or field types, and name-based resolution would misattribute overloaded fields.

*Alternative considered:* reporting through `AlloyExternalAnnotator`. Rejected, because inspections give per-check settings, severities, suppression and batch *Inspect Code* for free.

### 2. Scope calculator for top-level signatures

`AlloyCommandBounds.bound(command, sig)` (not to be confused with the existing name-resolution `AlloyScopes`) returns `(n, exact, source)`. `source` is one of `EXPLICIT(scope)`, `INHERITED` (from an ancestor's or descendant's scope), `OVERALL`, `DEFAULT` (3) or `FIXED` (`one`/`lone`/enum).
- `exact` is true if the scope is `isExact` or if `sig` is in `additionalExactScopes`.
- The effective `seq` bound is `maxseq` when it is non-negative, and otherwise the analyzer's default.

A unit test checks the calculator against the analyzer's own bounds. It translates fixture commands with `TranslateAlloyToKodkod` and an `A4Reporter` that captures the `scope(...)` messages ("Sig this/Message scope <= 3", and the sequence bound). This pins the default rules (especially the default `maxseq`) without copying `ScopeComputer`.

*Alternative considered:* calling `ScopeComputer` by reflection. Rejected, because it is fragile across analyzer updates, and it is slow, since it allocates the solution's bounds.

### 3. Which scope checks warn

The three checks differ in severity.
- **Mutable-state signature: WEAK_WARNING.** It fires only when the command already scopes some signature explicitly. A plain `for N` or `run {}` treats every signature alike, which is a deliberate choice; once one signature is singled out, leaving the one that feeds the mutable state at the default is the book's exact pitfall.
  - "Used by mutable state" is read from the analyzer's types: the field's `type()` columns other than the first, folded to top-level signatures, plus the top-level ancestors of every `var sig`.
  - In the book this also flags `run example3 {} for exactly 3 Node`, where `Message` stays at 3. That is intended: it is the same mistake.
- **Exact parameter: WEAK_WARNING or INFORMATION.** The analyzer's behavior is certain, but whether it matters is not.
  - A written `2 Id` is clearly read as "up to 2", so it gets a WEAK_WARNING.
  - An implicit overall `for 4` that applies to `Id` gets `ProblemHighlightType.INFORMATION`: the quick-fix stays available on Alt+Enter, but nothing is drawn. Otherwise every command of an ordering-based model would be highlighted.
  - Commands without `for` are not reported.
- **Seq bound: WEAK_WARNING.** It applies to `var` fields whose declaration contains `ISSEQ_ARROW_LONE` (`seq T`). It compares the effective `seq` bound with `T`'s top-level signature bound. Static `seq` fields are left out: their contents are not a queue that grows over a trace.

Problems are placed on the scope's token run, from `for` to the last `scope_item`. They are placed on the specific `CommandScope` or `seq` item when it is written. Each check runs on every command of a `=>` chain, and is reported at that chain's `AlloyCommandDecl`.

### 4. Event predicates and constrained relations

A recursive `VisitQuery` over a predicate's body collects four things:
- the mutable relations (`Sig.Field` with `isVariable`, and `Sig` with `isVariable`) mentioned inside an `ExprUnary.PRIME` operand;
- the mutable relations mentioned inside an `ExprUnary.AFTER` operand;
- whether any future-unbounded operator (`ALWAYS`, `EVENTUALLY`, `UNTIL`, `RELEASES`) occurs. The `;` operator desugars into these or into `after`, and is checked against the analyzer's desugaring in a test;
- the `ExprCall`s.

Calls are followed into the callee's body, memoized per `Func`, with a guard against cycles.

Macros (`let unchanged[x] { x = (x)' }`) need nothing special, because the analyzer has already expanded them in the type-checked body.

- **Event predicate:** a `pred` (not a `fun`) whose closure mentions some prime or `after`, and has no future-unbounded operator.
- **Missing relations:** all mutable relations of the model (`getAllReachableSigs()` of the file's module, excluding library modules), minus the ones constrained by the closure.
- **Union over branches.** Taking the union over all branches keeps false positives low: `processCandidate`'s three `implies … else` branches all constrain `inbox`.

Past operators (`historically`, `once`, `before`) are guards and do not make a predicate an event.

*Alternative considered:* counting only `f' = …` equalities as frame conditions. Rejected, because it misreports pointwise effects (`all n | n.inbox' = …`) and `no trashed'`.

### 5. Quick-fixes

All quick-fixes are `LocalQuickFix`es that edit the document text, found from the PSI at fix time.
- **Add scope:** insert `, N S` after the last scope item, or ` but N S` when the scope is only `for N`. `N` is the current bound. The number is selected through a template so that it can be edited at once.
- **Make exact:** insert `exactly ` before a written scope, or append `exactly N X` (with `, ` or ` but `) for the overall case.
- **Raise seq:** replace the number of the written `seq` item, or append `seq` with the element bound.
- **Frame conditions:** insert one line per relation before the closing `}` of the predicate body, with the indentation of the body's last line. For fields whose name is ambiguous among the model's mutable fields, the line is `S <: f' = S <: f`.

### 6. Suppression

An `InspectionSuppressor` for Alloy treats a `// noinspection <id>[,<id>]` line comment, placed immediately before a top-level paragraph or command, as suppressing those inspections inside it. It offers "Suppress for predicate/command" through `SuppressQuickFix`es that write that comment, using `SuppressionUtil`'s format. The inspection IDs are `AlloyUnscopedMutableSignature`, `AlloyExactParameterScope`, `AlloySeqBoundTooSmall` and `AlloyMissingFrameCondition`.

## Risks / Trade-offs

- **[Risk] The default scope rules differ from `ScopeComputer` in rare cases**, such as subset signatures, sigs with both an ancestor and a descendant scope, and `Int`. → The checks look only at top-level non-builtin signatures. The cross-check test (§2) covers each fixture command, and an unknown case reports nothing.
- **[Risk] Inspecting runs the type checker.** → It uses the same cached `AlloyModelService` result as diagnostics, so no extra parse happens.
- **[Trade-off] A union over branches misses a frame missing in one branch only.** This is acceptable for a first version, and the predicate gets no report.
- **[Trade-off] A predicate deliberately left nondeterministic is reported.** → The user can suppress it per predicate (§6).
- **[Risk] Quick-fix text edits on flat scope tokens can mis-place text in exotic commands** (`run a => check b`). → The fixes find their insertion point from the specific `CommandScope.pos`, and are tested on every command form of `parser/Command.als`.
