# Proposal

## Why

Alloy 6's temporal logic makes some mistakes very easy to write, and the analyzer accepts all of them without a word:

- **A fact over mutable state without `always` constrains only the first state.** Writing `fact { all n: Node | lone n.inbox }` is meant as an invariant, but it says nothing about later states. Writing `fact { all n: Node | n.inbox' = n.inbox }` is meant as a frame, but it constrains only the first transition. Checks then pass, or fail, for the wrong reason.
- **A prime on something that never changes does nothing.** Static fields and signatures are the same in every state, and so are quantified variables. So `succ'` is just `succ`, which usually means the user primed the wrong name, or forgot to declare a field `var`.
- **An integer literal outside the command's bitwidth wraps around.** With the default 4-bit integers (`-8..7`), `#Node > 8` compares against `-8`. The range check in the analyzer's translator is commented out, so nothing warns about this.

These are the same kind of mistake as the ones `alloy-inspections` already reports: the model type-checks, the analyzer runs, and the result is misleading.

## What Changes

- **Three new inspections** in the existing "Alloy" group of *Settings | Editor | Inspections*. Each one runs only while the model type-checks and can be suppressed with `// noinspection`.
  - **Fact constrains only the start of the trace.** There are two levels of confidence:
    - A fact that constrains the next state outside `always`, `eventually`, `until` and `releases` gets a weak warning, placed on that occurrence. The occurrence can be a prime, an `after`, or a call to an event predicate, since each of these constrains only the first transition.
    - A fact that mentions mutable state but uses no temporal operator at all is reported only as information. Such a fact is most often a deliberate initial-state fact such as `fact init { no inbox }`, so it is not highlighted.

    Both offer a quick-fix that wraps the body in `always { … }`.
  - **Prime on an expression that never changes.** `e'` where `e` mentions no `var` field or `var sig`, directly or through the functions it calls, gets a weak warning. A quick-fix removes the prime.
  - **Integer literal outside the command's bitwidth.** A literal that a command depends on, and that falls outside that command's integer range, gets a weak warning. The message names the affected commands and the value the literal wraps to. A quick-fix raises the bitwidth of an affected command to the smallest one that fits.
- The README feature list and the DEV.md "Inspections" checklist cover the three inspections.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `alloy-inspections`: three new inspections for temporal and integer mistakes the analyzer accepts in silence. The list of Alloy inspections in "Alloy inspections are configurable" grows accordingly.

## Impact

- **Code.**
  - Three `AlloyModelInspection`s in `inspections/`.
  - `AlloyFrameAnalysis` is extended to also tell whether a body uses any temporal operator, past ones included.
  - A small reachability walk finds the facts and functions each command depends on.
  - A new `AlloyScopeEdit` sets the `int` bitwidth.
  - Two new quick-fixes: wrapping a body in `always`, and removing a prime.
- **`plugin.xml`.** Three `localInspection` entries in the "Alloy" group, with descriptions under `resources/inspectionDescriptions/`.
- **Dependencies.** None.
- **Tests.**
  - New inspection tests.
  - `PracticalAlloyInspectionsTest` gains the three inspections, and its expected list is reviewed. None of the book's final models should report a weak warning.
  - `PracticalAlloyModelsTest` is unaffected.
