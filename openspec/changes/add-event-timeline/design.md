# Design

## Context

- The viewer only gets an `A4Solution`. `AlloyAnalysisEngine.execute` parses its own `CompModule` and drops it, and `CommandLink.Solution` keeps only the solution. Finding the predicates and facts therefore needs the compiled module to travel with the solution.
- The PSI (`AlloyFile`, stubs) knows declarations by name, but it does not resolve calls inside fact bodies the way the analyzer does (overloading, `let`, opened modules). It is used only to navigate to a predicate.
- `AlloySolutionModel` already owns trace state (`state`, `successor`, captions), and `AlloyInstancePanel` builds the toolbar line (the New solution actions and the projection chooser) and the `AlloyStatePanes` captions. A click in the trace path strip calls `goTo` and then `stateChanged()`.
- The sibling change `add-trace-loop-indicator` adds the trace path strip, a state strip with the loop arc, placed on the toolbar line when it fits and below it otherwise. It replaces the stepper: the previous and next actions, the "State i of n" label and the loop label are removed, and clicking a state's circle is the way to select a state. Its `Transition` list is the extension point for labels. Neither change depends on the other (see Decision 6).

## Goals / Non-Goals

**Goals:**
- Zero-annotation support for models written in the book's style (one trace fact, event predicates, optional grouping predicates).
- An override that also covers models that do not follow that style.
- No new analyzer runs: events come from evaluating the existing solution.

**Non-Goals:**
- Showing events inside the graph (as the book's `events` skolem does). Models that keep the idiom still show it as before.
- Validating that the events cover the trace fact. A "no event" label is only a hint.
- Events for static results, or for `Unsat` results.

## Decisions

### 1. Keep the compiled module with the solution
`AlloyAnalysisEvent.Sat` and `CommandLink.Solution` gain the `CompModule` the solution was solved from. Enumerated solutions (`next()`/`fork()`) reuse their session's module. The module is the analysis's own parse, never the editor's, so it is immutable and safe to read off the EDT.
- *Alternative: re-parse the file when a trace is shown.* Rejected. It can disagree with the solution when the file changed since the run, which is exactly the case of an outdated result.

### 2. Detection on the compiled AST, not on the PSI
Walk `module.allReachableFacts`. For each conjunct that is `ExprUnary(ALWAYS, body)`, collect the disjuncts of `body`, looking through:
- `ExprBinary(OR)` and `ExprList(OR)`;
- `ExprQt(SOME)` and `ExprLet`;
- `ExprUnary(NOOP)`.

Each `ExprCall` of a predicate is a candidate. A candidate whose own body is a disjunction of calls, by the same walk, is expanded into its callees. The walk keeps a visited set, and a predicate seen again is kept as a leaf. Anything that is not a call (for example `no inbox'` as a disjunct) is ignored. The result is ordered by declaration position, which is the "model order" of the spec.
- *Why automatic:* the book's models, and the test data under `practicalalloy/`, all use this shape. Detection makes the feature work on first open.
- *Why also a manual choice:* models that encode transitions differently (state-oriented scenarios, `always some e : Event | ...`) have nothing to detect. A per-file choice covers them without new syntax.
- *Alternative: a doc-comment or naming convention* (`/** @event */`, `_event` suffix). Rejected. It reintroduces annotation work, and models in the book would still need edits.

### 3. Evaluation by comprehension, one per predicate per state
For a predicate `p[x1: T1, …, xn: Tn]`, build `{ x1: T1, …, xn: Tn | p[x1, …, xn] }` with `ExprQt.Op.COMPREHENSION.make` over fresh `Decl`s of the parameters' declared types. Evaluate it with `solution.eval(expr, i)`, which handles primes and temporal operators at state *i*. The resulting `A4TupleSet` gives exactly the argument tuples that hold, in the analyzer's order. A parameterless predicate is evaluated as a formula (`Boolean`).
- This is the book's `_happens` idiom, built by the plugin. One evaluation per predicate per state, independent of the number of tuples.
- Only parameters declared as one atom of a signature (`x: S`, `x: one S`) are supported, since a comprehension cannot range over sets. Other predicates are disabled in the dropdown.
- *Alternative: enumerate argument tuples and call `p` on each.* Rejected: it is exponential in the arity, and needs atom expressions.

### 4. Background computation, cached per solution
A `TraceEvents` value (transition index → event instances, plus per-predicate errors) is computed on a pooled thread when a trace session is shown, or when the chosen events change, and is then posted to the EDT. It is cached in `AlloySolutionModel` keyed by the chosen predicate set, so stepping never re-evaluates.
- Cost: traces have at most about 20 states, and models a handful of events, so this means at most a few hundred `eval` calls.
- A new session cancels a computation still running for the previous one.
- An `Err` from one predicate is recorded for that predicate, and the others continue.

### 5. Choice storage
`AlloyInstanceViewerState` (already in the workspace file) gains a map from the file URL to the chosen predicate labels (`Func.label` without `this/`). An absent entry means "detected". Labels missing from the module are filtered out when they are read.
- *Alternative: store the choice in the `.als` file.* Rejected. It edits the user's model.

### 6. Timeline UI and composition with the loop indicator
- A `TraceTimeline` component sits in the panel's `north` box, under the toolbar line. It has one cell per transition, each with the label ("send[Node$1, Id$2]", "+k more", "no event" or "…") and a tooltip with the full list and errors.
- Event names are `ActionLink`-like segments that navigate. The `Func.pos` of the predicate is converted with `AlloyPositions` to a file and an offset; library modules open read-only, as go-to-declaration does.
- The component exposes `labels: (transition) -> TransitionLabel`.
- Composition with the sibling change:
  - If `add-trace-loop-indicator` lands first, this change does not add `TraceTimeline`. It supplies the same `TransitionLabel`s to that strip's transitions.
  - If this change lands first, `TraceTimeline` is the strip, and the loop indicator later adds its arc and state numbers to it.
  - Either way there is one strip. The spec's "A trace state strip, if shown, SHALL carry these labels" states this.
- The successor caption becomes `"$nextCaption, after ${labels.short}"`, using the same shortening rule as the timeline (at most 3 instances).
- The "Events" dropdown reuses the `AlloyProjectionChooser` pattern: a link that opens a checkbox popup, with a "Reset to detected" item at the bottom.

### 7. Atom names
Labels use the analyzer's atom names (`Node$1`), as the Text and Table tabs do, rather than the visualizer's `Node1`. These names can be pasted into the evaluator and the Text tab, and they are stable under the theme.

## Risks / Trade-offs

- **[Detection misses an unusual shape** (for example `always (some e : Event | happens[e])`)**]** → The dropdown makes the choice explicit, and "no event" labels flag the gap.
- **[A grouping predicate that is a disjunction but meant as one event]** → The user can check the grouping predicate and uncheck its parts. This is documented in the dropdown's tooltip.
- **[`eval` of a comprehension over a large signature is slow]** → It runs in the background, does not block the tool window, and is cancelled when the session changes.
- **[Keeping `CompModule` in memory longer]** → It lives only as long as the command result and viewer session that already hold the solution, which references the same sigs.
- **[Overlap with the sibling change]** → Decision 6 fixes a single strip owner. Tasks 4.x check which change landed and adapt.
