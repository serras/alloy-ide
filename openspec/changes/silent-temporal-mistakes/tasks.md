# Tasks

## 1. Shared groundwork

- [ ] 1.1 Add `temporal` to `AlloyFrameAnalysis.Summary`: any of `'`, `after`, `before`, `always`, `eventually`, `historically`, `once`, `until`, `releases`, `since`, `triggered` or `;`, directly or through calls (design §1). Verify with new cases in `AlloyFrameAnalysisTest`:
  - `pred init { no inbox }` is not temporal;
  - `historically …` and a call to a temporal predicate are temporal.

  Also verify that the existing frame-condition tests pass unchanged.
- [ ] 1.2 Move the line and indentation helpers of `AlloyFrameConditionFix` into a shared `AlloyTextEdits`. Verify that `AlloyMissingFrameConditionTest` passes unchanged.
- [ ] 1.3 Check with the analyzer whether signature facts are implicitly temporal. Run `sig A { var f: set A } { lone f }` for a trace whose second state violates `lone f` (design, Risks). Verify with a test that records the outcome. If they are implicitly temporal, drop signature facts from the roots of §2, and from the spec, before continuing.
- [ ] 1.4 Register the three inspections in `plugin.xml` in the "Alloy" group as weak warnings, with `AlloyBundle` names and HTML descriptions. Verify with `AlloyInspectionRegistrationTest`: seven IDs are listed under "Alloy", enabled, each with a description file.

## 2. Fact constrains only the start of the trace

- [ ] 2.1 Implement the weak-warning part of `AlloyFactOnlyAtStart`: depth under `always`/`eventually`/`until`/`releases`, `;` counted as scenario, occurrences being primes, written `after`s and event calls, the first one reported with `after`-aware placement (design §2). Verify with `AlloyFactOnlyAtStartTest`, covering every scenario of "Fact that constrains the next state only at the start".
- [ ] 2.2 Implement the information-level part: no temporal operator, mentions mutable state, header placement, and `ProblemHighlightType.INFORMATION`. Verify with tests covering every scenario of "Fact over mutable state without temporal operators", including that the book's `fact init` is not highlighted, while Alt+Enter offers the fix.
- [ ] 2.3 Implement `AlloyWrapInAlwaysFix` (design §3). Verify with tests of both "Wrap a fact in always" scenarios, of a body with comments and blank lines, and of a signature fact. Check that the problem is gone after each fix.

## 3. Prime on an expression that never changes

- [ ] 3.1 Implement `AlloyStaticPrime`: mutability of the operand through calls and `let`s, rigid variables and parameters, macro and analyzer-built exclusions, and the three messages (design §4). Verify with `AlloyStaticPrimeTest`, covering every scenario of "Prime on an expression that never changes".
- [ ] 3.2 Implement `AlloyRemovePrimeFix`. Verify with a test of the "Remove the prime" scenario, including `(n.succ)'` becoming `(n.succ)`.

## 4. Integer literal outside the command's bitwidth

- [ ] 4.1 Implement the reachability of facts and funcs from a command, and the per-command collection of out-of-range literals. Group the results by literal, with the wrapped values (design §5). Verify with `AlloyIntLiteralRangeTest`, covering the "Default bitwidth", "Literal in a predicate" and "Several commands" scenarios, a literal in a macro body, and a `0 int` command (skipped).
- [ ] 4.2 Add `AlloyScopeEdit.SetBitwidth` and the "Set the bitwidth of C to N int" fixes, for written `int` items, scopes without one, and commands without `for`. Verify with tests of the "Raise the bitwidth" and "Bitwidth already written" scenarios, and of `run a {}` becoming `run a {} for 5 int`, with the number selected for editing.

## 5. Corpus and documentation

- [ ] 5.1 Add the three inspections to `PracticalAlloyInspectionsTest`. Review the new problems and extend its expected list: there must be no weak warnings on the chapters' final models, and the information-level `init` facts are listed separately. Verify that it passes, and that `PracticalAlloyModelsTest` passes unchanged.
- [ ] 5.2 Add the three inspections to the DEV.md "Inspections" checklist:
  - the weak warning on a frame fact without `always`;
  - Alt+Enter "Wrap in always" on `fact init` with no highlighting;
  - `succ'` and the parameter message;
  - `#Node =< 8` under the default bitwidth, with the bitwidth fix selected for editing.

  Add them to the README feature list. Verify by re-reading both against the spec.
- [ ] 5.3 Run `./gradlew test` and verify that it passes. Run `openspec validate silent-temporal-mistakes --strict` and verify that it passes.

## Workflow follow-up

- Archive the change once it has been reviewed.
