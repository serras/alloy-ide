# Tasks

## 1. Inspection infrastructure

- [ ] 1.1 Add the `inspections/` package with a base `AlloyModelInspection`. Its `checkFile` gets `AlloyModelService.model(file)` and returns nothing unless the result is `Model` (design §1). Add helpers that map an analyzer `Pos` to the containing `AlloyCommandDecl` or `AlloyPredDecl`, and to the token range of a scope item. Verify with a test that a model with a type error yields no problems from a trivial test inspection, and that `Pos` → PSI mapping finds each command of `parser/Command.als`.
- [ ] 1.2 Register the four inspections in `plugin.xml` (`localInspection`, `language="Alloy"`, `groupName="Alloy"`, `enabledByDefault="true"`, with the default levels of design §3), each with an HTML description under `resources/inspectionDescriptions/`. Verify with a test that `InspectionProfileManager` lists the four IDs under the "Alloy" group, enabled, and that each description file exists.
- [ ] 1.3 Add the Alloy `InspectionSuppressor` and the "Suppress for predicate/command" fixes that write `// noinspection <id>` (design §6). Verify with a test that a suppressed predicate is not reported while another one is, and that applying the suppress fix writes the comment line before the paragraph.

## 2. Command bounds

- [ ] 2.1 Implement `AlloyCommandBounds`: the bound, exactness and source of every top-level signature, and the effective `seq` bound (design §2). Verify with `AlloyCommandBoundsTest`. For every command of the protocol-design and `behavioral-topics/sequences` fixtures, the computed bounds of the top-level user signatures and of `seq` must equal the ones the analyzer reports through `A4Reporter.scope` when it translates the command.

## 3. Scope inspections

- [ ] 3.1 Implement `AlloyUnscopedMutableSignature` with its "Add a scope for S" quick-fix (design §3, §5). Verify with `AlloyScopeInspectionsTest` on the `explicit-messages-as-signatures` fixture:
  - `bad_all_initiate` and `example3` report `Message`;
  - `all_initiate`, `example`, and `at_most_one_leader for 3 but 6 Message` do not;
  - a scope on `CandidateMsg` counts as scoping `Message`;
  - the fix turns `for 3 but exactly 3 Node` into `for 3 but exactly 3 Node, 3 Message`.
- [ ] 3.2 Implement `AlloyExactParameterScope`, with WEAK_WARNING for a written non-exact scope and INFORMATION for an overall `for N`, and its "Write the scope of X as exact" fix. Verify with tests on the `verifying-the-expected-properties` fixture (`util/ordering[Id]`):
  - `for 3 but 2 Id` is a weak warning;
  - `for 4 but 20 steps` is information-level;
  - `example3`, `example` and commands without `for` report nothing;
  - the fix gives `for 3 but exactly 2 Id` and `for 4 but exactly 4 Id, 20 steps`.
- [ ] 3.3 Implement `AlloySeqBoundTooSmall` with its "Raise the seq bound to N" fix. Verify with tests on the `refining-the-network-model` fixture:
  - `for 3 but 2 seq` is reported on `2 seq`;
  - the other commands are not;
  - the fix gives `for 3 but 3 seq`.
  
  Also verify that a static (non-`var`) `seq` field is not reported.
- [ ] 3.4 Add the scope inspections to the DEV.md manual checklists, in a new "Inspections" section: the weak-warning highlighting, Alt+Enter on an information-level problem, quick-fixes with the number selected for editing, and the settings page group. Verify by re-reading the checklist against the delta spec scenarios.

## 4. Frame-condition inspection

- [ ] 4.1 Implement the event-predicate analysis: the constrained relations under `'` and `after`, the future-operator detection, and the memoized traversal of calls (design §4). Verify with `AlloyFrameAnalysisTest`:
  - `initiate` is an event, while `initiate_enabled` and `fairness` are not;
  - `unchanged[x]` macros and helper predicates count;
  - all three `implies … else` branches of `processCandidate` count;
  - a recursive call terminates;
  - a scenario using `;` is not an event.
- [ ] 4.2 Implement `AlloyMissingFrameCondition` on the predicate name, with the "Add `f' = f`" and "Add all missing frame conditions" fixes (design §5). Verify with tests:
  - the spec's `send` example reports `outbox`;
  - the fixes insert indented lines before `}` and remove the warning;
  - an ambiguous field name is written as `S <: f`;
  - a model with a type error reports nothing.
- [ ] 4.3 Add `PracticalAlloyInspectionsTest`. It runs the four inspections over the `protocol-design`, `behavioral-modeling` and `behavioral-topics` fixtures, and compares the weak warnings with an explicit expected list (like `PracticalAlloyModelsTest`), which must contain no missing frame conditions for the chapters' final models. Verify that it passes, and that `PracticalAlloyModelsTest` still passes unchanged.
- [ ] 4.4 Add the frame inspection to the DEV.md "Inspections" checklist (weak warning on the name, both fixes, suppression comment). Mention the inspections in the README's feature list. Verify by re-reading both against the spec.

## 5. Integration

- [ ] 5.1 Run `./gradlew test` and verify that it passes. Run `openspec validate add-scope-and-frame-inspections --strict` and verify that it passes.
