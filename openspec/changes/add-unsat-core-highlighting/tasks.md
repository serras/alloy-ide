# Tasks

## 1. Core finder

- [ ] 1.1 Add the conjunct splitter to `analysis/` (design §1): it flattens `NOOP`, `ExprList` `AND` and `ExprBinary` `AND`, and keeps anything else whole. Verify with a new `AlloyCoreFinderTest`, on parsed models:
  - two facts and an empty `run` give their bodies in source order;
  - `fact { some A and no A and some B }` gives three conjuncts;
  - a `check` gives the facts plus one negated-assertion conjunct with the assertion's span.
- [ ] 1.2 Add `AlloyCoreFinder`, started through `AlloyAnalysisEngine.start`: first the empty-formula solve, then deletion in source order, with a progress callback "formula k of n". Verify in `AlloyCoreFinderTest` with SAT4J:
  - the spec's "Two contradicting facts", "Contradiction inside one fact" and "Valid assertion" scenarios give exactly the expected spans;
  - `sig A { f: one B } sig B {}` with `run {} for 3 but exactly 1 A, 0 B` gives an empty core;
  - progress reports n steps;
  - stopping during the deletion ends with `Stopped`.
- [ ] 1.3 Extend `AlloyAnalysisEvent.Executing` with the reachable sigs, reported by both `execute` overloads. Verify that `AlloyAnalysisEngineTest` passes with an assertion that the sigs of the parsed module are reported, and that `AlloyRunLogTest` still passes.

## 2. Unsat results keep their command

- [ ] 2.1 Add `CommandLink.Core(command, sigs, core)` and record it from `AlloyCommandExecution` for `Unsat` ends. Make the hints provider attach the click action for any `link`. Verify:
  - a new `AlloyCommandResultHintsProviderTest` case: a no-instance hint has the click action and a failed one has none;
  - `AlloyCommandResultClickHandlerTest` still passes.
- [ ] 2.2 Replace the DEV.md "Inline command results" item "The hint of a valid check, or of a run with no instance, is not clickable" with "Clicking the hint of a valid check, or of a run with no instance, computes its unsat core". Verify by re-reading the checklist against the delta spec.

## 3. Computing and recording the core

- [ ] 3.1 Add the `AlloyUnsatCores` project service, plus `startCore`/`coreFinished` in `AlloyRunService` sharing the single-analysis exclusion (design §3). The click handler starts it for current `CommandLink.Core` results without a core, as a cancellable background task. Verify with `AlloyRunServiceTest` cases:
  - a core computation refuses to start while a command runs, and a command refuses to start while a core is computed (`BUSY_MESSAGE`);
  - the gutter shows the spinner during the computation.
- [ ] 3.2 Record progress and outcome in the result summary: " · computing core…", then " · core: n formulas" or " · no formula in core". Clicking during the computation stops it and restores the plain summary. Verify with `AlloyCommandResultClickHandlerTest` cases for each of the three summaries and for stopping.
- [ ] 3.3 Add the core section to `AlloyCommandResultTooltip`: one line per formula ("file.als:line" and its first line of text) in source order, or the empty-core sentence (design §5). Verify with `AlloyCommandResultTooltipTest` cases for a two-formula core spread over two files and for the empty core.
- [ ] 3.4 For outdated no-instance hints, show the "run the command again" information hint and start nothing. Verify with an `AlloyCommandResultClickHandlerTest` case: after an edit, clicking starts no analysis and the summary is unchanged.

## 4. Highlighting and clearing

- [ ] 4.1 Add `AlloyHighlighterColors.UNSAT_CORE` (fallback `EditorColors.SEARCH_RESULT_ATTRIBUTES`), its "Unsat core" descriptor and a `<core>` demo span on `AlloyColorSettingsPage`. Verify with `AlloyColorSettingsPageTest`: the descriptor is listed and the demo tag maps to the key.
- [ ] 4.2 Show a core: add `HighlightManager` highlighters with `HIDE_BY_ESCAPE | HIDE_BY_TEXT_CHANGE` and error-stripe marks in every editor of every core file. Add them to editors opened later through `FileEditorManagerListener`. Map positions with `AlloyPositions.file`/`textRange`. Verify with a new `AlloyUnsatCoreHighlightTest` (code-insight fixture, two files, `open`):
  - the highlighted ranges equal the core spans in both files;
  - opening the module's file after the computation highlights it;
  - showing another core replaces the first.
- [ ] 4.3 Clear everywhere on Esc in any editor showing the core, on any Alloy document change, on a settings generation change, and on `startRun`. Re-show the stored core on a click without solving. Verify in `AlloyUnsatCoreHighlightTest`:
  - each trigger leaves no `UNSAT_CORE` highlighters in any editor;
  - clicking the current hint again restores them without a new analysis (check this with a counting `AlloyAnalysisEngine` stub).
- [ ] 4.4 Add a DEV.md "Unsat core" manual checklist (model: `fact f1 { some A }`, `fact f2 { no A }`, `run {}`, plus a module with a fact in the core). Cover:
  - clicking the hint, the progress task and stopping it;
  - highlight color in light and dark themes, and error-stripe marks;
  - Esc in one editor clears all;
  - the highlight in a module opened later;
  - the color change in settings;
  - the empty-core message;
  - the outdated-hint message.
  
  Verify by re-reading the checklist against the delta spec scenarios.

## 5. Documentation and integration

- [ ] 5.1 Describe unsat cores in the README's "Running commands" item, including that the core is per top-level formula and computed with the configured solver, and that the core settings only apply to provers, which are not offered. Mention it in `plugin.xml`'s description and in CHANGELOG.md. Verify by re-reading them against the proposal.
- [ ] 5.2 Run `./gradlew test`, including the Practical Alloy example tests, and `openspec validate add-unsat-core-highlighting --strict`. Verify that both pass.
