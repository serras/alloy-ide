# Proposal

## Why

Practical Alloy asks readers to "exhaustively validate our specification before checking our assertions". It does so with many `run` scenarios and `check` commands, often written with `expect 1` and `expect 0`. Today the plugin runs one command at a time. It only mentions an unmet expectation in the hint text, so a model with twenty scenarios has to be re-run and read line by line after every change.

Worse, a `check` that finds no counterexample looks the same whether the assertion holds or the model has no traces at all. The protocol design chapter shows this trap. In the single-node ring, the effects of `process` contradicted each other, so the liveness check passed for the wrong reason, and only an extra hand-written scenario exposed it. The analyzer can detect this case itself: the facts, or the facts together with the assertion's premise, have no instance within the check's scope.

## What Changes

- **Expectations as test outcomes.**
  - A command that declares `expect 0` or `expect 1` *passes* when the analyzer's outcome matches the expectation, and *fails* otherwise or when the analysis fails.
  - After a result, the gutter icon of such a command shows the outcome as a passed or failed test state, instead of the plain run icon.
  - The hint says "as expected" or "contrary to expectation".
  - Commands without `expect` keep today's run icon and hint.
- **Vacuity check for passing checks.**
  - When a `check` finds no counterexample, the same execution goes on to solve the model's facts under the check's scope, and also the facts together with the assertion's premise when the assertion has one (`A implies B`, also under `all` and `always`).
  - If either has no instance, the check *passed vacuously*. Its gutter icon becomes a distinct "vacuous" icon, whether or not it declares `expect`. Its hint says "(vacuous)", and its tooltip explains whether the facts or the premise have no instance.
  - While this runs, the hint already shows the check's result, followed by "checking vacuity…". Stopping, cancelling and failures in this phase keep the check's own result and say that vacuity was not determined.
  - A new setting, *Check vacuity of passing checks* (on by default), controls the vacuity check.
- **Run all commands of a file as tests.**
  - A new *Alloy Commands* run configuration runs every top-level command of a file in order, in IntelliJ's test runner. Each command is a node with its result, its time and the analyzer output.
  - The run configuration provides navigation to the command, *Rerun Failed Tests*, stop, and the run history.
  - It is created from the context menu of an `.als` file in the editor or the *Project* view ("Run 'All commands in a.als'").
  - A command fails in the tree when its expectation is not met, when its analysis fails, or when it passes vacuously.
  - Hints and gutter icons are updated exactly as for gutter runs. The suite holds the project's single analysis until it ends.
- Gutter runs keep working as today: no tool window and a status-bar progress, now including the vacuity phase.

## Capabilities

### New Capabilities
- `alloy-test-runner`: running the commands of an Alloy file as a test suite in the IDE's test runner, including how each command maps to a passed or failed test, navigation, rerunning failed commands, and stopping.

### Modified Capabilities
- `alloy-command-execution`:
  - "Run gutter markers" gains the passed, failed and vacuous icons.
  - "Inline command result" gains the expectation and vacuity wording.
  - "Result details tooltip" gains the vacuity details.
  - "Analysis progress" now applies to gutter runs only.
  - New requirements cover the vacuity check, how it ends, and how it interacts with stopping and outdated results.
- `alloy-analyzer-settings`: "Analyzer settings page" and "Option values and defaults" gain the *Check vacuity of passing checks* option, on by default.

## Impact

- **Code.**
  - `run/`: `CommandResult` gains an expectation verdict and a vacuity state. `AlloyCommandExecution` runs the vacuity phase as a follow-up analysis. `AlloyRunLog` gains the vacuity wording. `AlloyRunLineMarkerContributor` chooses the icon from the result.
  - `analysis/`: the `Executing` event hands over the analysis's own parsed module, so that the follow-up can build the facts-only and premise commands with `Command.change(Expr)`.
  - New `run/tests/` package: a run configuration type and producer, a `RunProfileState` with an in-process `ProcessHandler` that feeds the SM test runner with service messages, an `SMTestLocator` for `alloy:` locations, and a rerun-failed action.
  - Settings: one more option on the page.
- **Dependencies.** None new. The test runner uses the platform's `com.intellij.execution.testframework.sm` API (part of `com.intellij.modules.platform`). Alloy 6.2.0's `Command.expects`, `Command.change(Expr)`, `CompModule.getAllReachableFacts()` and `Assert.expr` are already on the classpath.
- **Cost.** A passing check is solved again once or twice. Satisfiable queries are usually fast, but a vacuity check can take as long as the check itself. The setting turns it off.
- **Tests.** New and extended tests in `src/test/kotlin/.../run/` and `settings/`. The test runner gets light tests driving the service-message stream. New manual items go to the `DEV.md` checklists.
