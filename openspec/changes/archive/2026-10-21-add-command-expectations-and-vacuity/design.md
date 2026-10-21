# Design

## Context

- **One execution, one result.** A gutter click creates an `AlloyCommandExecution`, which takes the project's single analysis slot in `AlloyRunService` and runs one `AlloyAnalysis` on `AlloyAnalysisEngine` inside an `AlloyCommandTask` (status-bar progress). It records `CommandResult`s in `AlloyCommandResults`, which the hints, tooltips and gutter markers read. The execution ends, and frees the slot, on the analysis's single `End` event.
- **Expectations are already parsed.** `Command.expects` (-1, 0 or 1) already reaches `AlloyRunLog`, which words "as expected" or "contrary to expectation" and sets `unexpected`. Nothing else uses it, and the gutter marker only knows "running" and "not running".
- **What the analyzer gives us.** A resolved Alloy 6.2.0 command's `formula` is `globalFacts.and(e)`, where `e` is the negated assertion for a check (`CompModule.resolveCommand`). `Command.change(Expr)` makes a copy with the same scope, bitwidth, steps, `expects` and parent. `CompModule.getAllReachableFacts()` gives the facts. For a named assertion, `command.nameExpr.referenced()` is its `Assert`, whose `expr` is the type-checked body.
- **Where the parse lives.** The engine's `execute(contents, filename, index, …)` parses the model on the analysis thread; the parsed module is never shared with the editor. A second analysis can be started on existing sigs and a `Command` with the other `execute(sigs, command, …)` overload.

## Goals / Non-Goals

**Goals:**
- Expectations, vacuity and the test suite all derive from one shared result model, so the gutter, hints, tooltips and test tree never disagree.
- The vacuity check is sound: "vacuous" is only reported when the solver proved, within the check's scope, that the facts or the premise have no instance.

**Non-Goals:**
- Vacuity of sub-formulas other than one top-level premise (for example, each conjunct or each `implies` nested inside the assertion).
- Vacuity for `run` commands. A `run` with no instance is already explained by its unsat core.
- Highlighting the vacuity unsat core in the editor. The separate unsat-core change can reuse the core recorded here.
- Running suites over several files or a whole directory.
- Persisting results or verdict icons across IDE restarts.

## Decisions

### 1. Verdict and vacuity are fields of `CommandResult`

`CommandResult` gains `expectation: Expectation?` and `vacuity: Vacuity?`:
- `Expectation` is `Passed` or `Failed`, and is null without `expect`. It is computed by `AlloyCommandExecution` from `log.outcome` and `Command.expects`, and a failure with `expects >= 0` is `Failed`.
- `Vacuity` is one of `Checking`, `NotVacuous(millis)`, `VacuousFacts(millis, coreSize)`, `VacuousPremise(premiseText, millis, coreSize)` or `Unknown(reason)`.

A single function, `verdict(result)`, maps a result to `Run`, `Passed`, `Failed` or `Vacuous`. The gutter marker and the test tree both use it, so they cannot drift.

*Alternative:* derive the verdict separately in each place from `summary` text. Rejected: fragile, and the test tree needs the structured reason anyway.

### 2. Icons

- Passed: `AllIcons.RunConfigurations.TestState.Green2`.
- Failed: `AllIcons.RunConfigurations.TestState.Red2`.
- Vacuous: `AllIcons.RunConfigurations.TestState.Yellow2`.

These are the standard test gutter icons, so users read them the way they read test results. Yellow is the platform's "warning or ignored" color, which is distinct from both pass and fail. The marker's tooltip names the verdict ("Run check safety — passed vacuously last time"), plus "(outdated)" when the result is outdated.

Verdict icons stay when the result becomes outdated, as IntelliJ's test gutter icons do. The hint already dims and says "(outdated)", and keeping the last verdict visible is what a "fix and re-run" loop needs.

*Alternative:* revert to the run icon on edit. Rejected: on every keystroke it would hide which scenarios failed.

### 3. The vacuity check is a follow-up analysis of the same execution

**How it runs:**
- `AlloyAnalysisEvent.Executing` gains the analysis's parsed `CompModule` (null for the sigs/command overload).
- On `Unsat` of a check, with the setting on, `AlloyCommandExecution` records the check's result with `Vacuity.Checking`, but does not finish. Instead it starts a second `AlloyAnalysis` through `engine.execute(sigs = module.allReachableSigs, command = query, …)`, with the same `A4Options` and the same disposable parent.
- That analysis feeds a separate `AlloyRunLog` section ("Vacuity check" in the tooltip), so the check's own lines and time stay as the analyzer printed them.
- The execution finishes, and frees the slot and the latch that `AlloyCommandTask` waits on, after the follow-up's `End`.

**Queries:**
- `premise = premiseOf(assert.expr)`, where `premiseOf` unwraps:
  - a one-element conjunction block;
  - `all x: T | body`, which gives `some x: T | premiseOf(body)`;
  - `always body`, which gives `eventually premiseOf(body)`;
  - `A implies B` without `else`, which gives `A`.
  
  Anything else has no premise.
- With a premise, first solve `facts and premise`. Sat means not vacuous; one query suffices. Unsat means vacuous, and a second query, `facts` alone, tells *which*: unsat means `VacuousFacts`, sat means `VacuousPremise`.
- Without a premise, solve `facts` alone.
- Each query is `command.change(...)`, so the scope, steps, `expects` and `=>` parent are those of the check.
- Inline `check { … }` assertions, whose `nameExpr` has no referenced `Assert`, fall back to the right operand of the resolved formula's top-level `and` when it is a negation. Otherwise only the facts are checked.

**Why this is sound.** Each query is weaker than the check's negated formula (facts ∧ ¬assert implies facts, and implies facts ∧ premise when assert = premise ⇒ B). So "no instance" proves there was nothing for the assertion to be checked against within the scope. The rewrites under `all` and `always` keep this: if `some x | A` or `eventually A` is unsat, then `all x | A ⇒ B` and `always (A ⇒ B)` hold trivially.

**Alternatives considered:**
- *Run the queries inside the first analysis thread before emitting `Unsat`.* Rejected: the check's result would appear only after the vacuity check, and the engine's "exactly one `End`" contract would have to change.
- *Start a separate execution.* Rejected: another execution could grab the slot in between, and stopping would be split across two results.

**Stopping and failures:**
- `stop()` and `terminate()` act on the current analysis, the check's or the follow-up's.
- A `Stopped` or `Failed` end of the follow-up records `Vacuity.Unknown(reason)` and keeps the check's state and summary.
- `AlloyCommandTask` shows "Checking vacuity…" as `text2` while `vacuity == Checking`.

Results keep the `documentStamp` and `settingsGeneration` taken at launch, so "Outdated results" works unchanged.

### 4. The setting is on by default

`AlloySettingsState.checkVacuity` defaults to true. It is the only non-analyzer option, so it sits under the analyzer options on the page, under a separator.

**Why on by default:**
- The protocol-design workflow is exactly the case where a vacuous pass goes unnoticed.
- The cost only applies to checks that passed. A satisfiable query (the common, non-vacuous case) usually solves faster than the unsat check that came before it, and the user can stop it at any time without losing the check's result.

Changing the setting bumps the settings generation like any other option, so existing results become outdated. That is harmless and keeps "Settings changes outdate results" uniform.

### 5. Suites use IntelliJ's SM test runner, without a process

This is worth it over a lighter "run all and show a notification" action, for these reasons:
- The book's workflow is a suite of scenarios re-run after each change.
- The SM runner gives the tree, durations, failure messages, output per test, navigation, *Rerun Failed Tests*, stop, history and export for free, in the form IntelliJ users already know.
- With no external process, the cost is moderate.

**Pieces:**
- `AlloyCommandsConfigurationType` and `AlloyCommandsRunConfiguration`: the file path, plus an optional list of command refs used by rerun-failed. The configuration is Run-executor only.
- `AlloyCommandsConfigurationProducer` (`LazyRunConfigurationProducer`) on an `.als` `PsiFile` context: the editor and *Project* view context menus. The gutter keeps its direct, configuration-less runs, as its spec requires.
- `AlloyCommandsRunState.execute` returns a `DefaultExecutionResult`. Its console comes from `SMTestRunnerConnectionUtil.createAndAttachConsole("Alloy", handler, properties)`. Its `handler` is an in-process `ProcessHandler` whose "process" is a background task:
  1. It takes the slot through a new `AlloyRunService.startSuite()` / `suiteFinished()` pair, like enumeration.
  2. It runs the commands one by one through the existing `AlloyCommandLauncher` and `AlloyCommandExecution`, which record results as usual.
  3. It prints `##teamcity[testStarted/testFailed/testFinished duration=…]` service messages with `locationHint='alloy://<path>#<index>:<label>'`.
- `destroyProcess` stops the current execution and skips the rest. The handler then finishes, so the remaining nodes stay "not run".
- `AlloyTestLocator` (`SMTestLocator`) resolves `alloy://` to the command's PSI through `AlloyCommands.find`.
- `AlloyRerunFailedAction` (`AbstractRerunFailedTestsAction`) builds a state restricted to the failed nodes' refs.
- When the slot is busy, the handler prints `BUSY_MESSAGE` to the console and terminates with exit code 1, without running any command.

**Alternatives considered:**
- *Implement the tree ourselves in a tool window.* Rejected: it would duplicate the platform.
- *Spawn the analyzer in a child JVM.* Rejected by the "Analysis runs inside the IDE" requirement.

### 6. Commands of a suite are found at launch

The suite reads the file's top-level commands in a read action at start (`AlloyCommands.commands`) and refers to each by `AlloyCommandRef`. Each command is then resolved again at its own launch, so edits during a suite behave as for gutter runs: the results become outdated, and a removed command fails with "Command not found".

## Risks / Trade-offs

- [Vacuity doubles the time of a passing check in the worst case] → On by default but switchable; stoppable without losing the check's result; the time is shown in the tooltip.
- [`premiseOf` misses premises written in other shapes, such as `not A or B`, or nested in a block with other conjuncts] → The facts-only query still runs, so the result is still sound, only less sharp. Documented as a non-goal; it can be extended later without spec changes.
- [The `Executing` event carrying `CompModule` couples the run layer to the parse] → The module is the analysis's own parse, never shared with the editor, and it is used only on the analysis's follow-up within the same execution.
- [SM runner APIs are partly internal-looking (`SMTestRunnerConnectionUtil`)] → They are widely used by third-party test plugins and are part of the platform module. `./gradlew verifyPlugin` will flag incompatibilities.
- [Yellow vacuous icon could be mistaken for "ignored"] → The marker tooltip and the hint both say "vacuous". In the test tree the node is red (failed), so a suite never reports green over a vacuous check.
- [A suite holds the slot for a long time] → It can be stopped from the Run tool window at any time, and gutter clicks say why they are refused.

## Migration Plan

No data to migrate. Results are per session. The new setting gets its default the first time it is read. Rollback is removing the plugin version.
