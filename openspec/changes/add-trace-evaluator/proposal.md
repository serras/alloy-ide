# Proposal

## Why

Practical Alloy keeps turning to the evaluator to validate a model. In the protocol design chapter, the authors evaluate `stutter`, `after …` and event guards on the state they are looking at, and that is how they find that a guard differed from true enabledness. The Alloy Instance tool window has no evaluator, so the user has to reopen the result in the Alloy desktop application. Users of IntelliJ already know how such a tool should work from the debugger: evaluate one expression, or keep a list of watches that follow the current frame.

## What Changes

- **Evaluator panel.** The Alloy Instance tool window gains an Evaluator panel below its tabs, shown or hidden from the toolbar, with the debugger's two parts:
  - **Evaluate field.** The user types one Alloy expression or formula and presses Enter. The panel shows its value in the shown solution, at the selected state of a trace. Any syntax or type error, or the analyzer's refusal (such as higher-order quantification), is shown in place of the value.
  - **Watches.** The user can add the expression to a list of watches, and can add, edit, remove and reorder watches. All watches are evaluated again whenever the shown state changes, by stepping, or when a new solution is shown, whether from a command hint or from New Config, New Trace, New Init or New Fork.
- **Values.**
  - A set of tuples is shown with its size, and expands to one row per tuple, in the analyzer's order and with its atom names.
  - Formulas show `true` or `false`, and integers show their value.
  - A value that changed since the previous evaluation of the same watch is highlighted, as the debugger highlights changed variables.
- **Temporal expressions.** Expressions are evaluated at the selected state of the trace, as the book's trace evaluator does. So `after`, `always`, `eventually`, `once`, primes and the rest are interpreted from that state, along the whole lasso. The panel names the state it evaluates at.
- **Names.** The expression can use every name of the analyzed model (signatures, fields, predicates, functions, opened modules), the atoms of the solution (`Node$0`) and its skolems (`$show_n`). The field completes those names and highlights Alloy syntax.
- **Watches per model file.** Watches are kept per model file, across results and IDE sessions, in the project's workspace. The file's watches come back whenever one of its results is shown.
- **Outdated results.** An outdated result can still be evaluated, against the model as it was analyzed. The panel says so, as the header does.

## Capabilities

### New Capabilities
- `alloy-evaluator`: evaluating Alloy expressions and watches against the solution shown in the Alloy Instance tool window, at the selected state of a trace.

### Modified Capabilities
<!-- none: the tabs, stepper, projections and new-solution requirements of `alloy-instance-viewer` are unchanged; the evaluator sits beside them -->

## Impact

- **Code.**
  - `run/`: a solution's `CommandLink.Solution` also keeps the module that the analysis parsed. `AlloyAnalysisEvent.Sat` carries that module, and enumerated sessions share it.
  - `viewer/`: `AlloySolutionModel` exposes evaluation at its selected state. A new evaluator panel (field, watches tree, persistence) sits in a splitter under the tabs of `AlloyInstancePanel`. A small completion contributor serves the evaluator field.
  - `editor/` and `semantics/`: the existing completion contributors and the external annotator skip the evaluator's fragment.
- **Dependencies.** No new ones. The panel uses the analyzer's own `CompUtil.parseOneExpression_fromString` and `A4Solution.eval(Expr, int)`, as `SimpleGUI`'s evaluator does, and IntelliJ platform UI (`EditorTextField`, tree tables, persistent state).
- **Tests.**
  - New viewer tests for evaluation, errors, temporal operators at a state, re-evaluation on stepping and new solutions, watches editing and persistence, and completion.
  - New items in the manual checklists of `DEV.md` for the panel in `runIde`.
