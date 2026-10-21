# Development

Run `./gradlew runIde` to start a sandbox IDE with the plugin, and `./gradlew check` to run the tests.

## Releasing

The plugin is distributed as the ZIP built by `./gradlew buildPlugin`, `build/distributions/alloy-ide-<version>.zip`, whose `lib/` directory holds the plugin's JAR together with the Alloy analyzer and its dependencies. Do not upload `build/libs/alloy-ide-<version>.jar`: it only has the plugin's own classes, so the Marketplace reports every analyzer class (such as `edu.mit.csail.sdg.alloy4.Util`) as missing.

1. Run `./gradlew verifyPlugin`, which checks that ZIP against the supported IDEs.
2. Upload it with `PUBLISH_TOKEN=<Marketplace token> ./gradlew publishPlugin`, or upload the ZIP by hand in the Marketplace.

## Specification-based

This project uses [OpenSpec](https://openspec.dev/) and [SpecBuddy](https://specbuddy.dev/) to guide the implementation.

## Manual checklists

Some behavior depends on the IDE UI, so it is checked by hand in `runIde`. Unless a checklist says otherwise, use a project with two files: `a.als` containing `sig A {}` and `pred p[x: A] { some x }`, and `b.als` containing `open a` and `run p for 3`.

### Diagnostics

- [ ] Typing `sig C { f: Undefined }` in `a.als` underlines `Undefined` as an error, and hovering it shows "The name "Undefined" cannot be found."; fixing it removes the error without saving the file.
- [ ] Adding `fact { some A & none }` highlights `&` as a warning, and its tooltip shows the analyzer's message over several lines.
- [ ] With the error in `a.als`, `b.als` shows an error at `open a` saying that the opened module has errors, with the error's message; fixing `a.als` removes it from both files.
- [ ] The error stripe marks the error, and the *Problems* tool window's *File* tab lists it for the current file; selecting the entry moves the caret to `Undefined`.
- [ ] Typing quickly in a large model keeps the editor responsive while diagnostics update.

## Manual checklist: semantic features

Add `open util/ordering[A]` and `fact { some first }` to `a.als`.

- [ ] Hovering a use of `A` shows a popup with the signature as the analyzer explains it; a `/** ... */` comment before a declaration appears under it. With a type error elsewhere in the file, the popup shows the declaration's source instead.
- [ ] Cmd/Ctrl-clicking `p` in `b.als` opens `a.als` at the predicate, also while `b.als` has a type error.
- [ ] Cmd/Ctrl-clicking `first` opens the bundled `util/ordering` module read-only, at the declaration of `first`.
- [ ] Cmd/Ctrl-clicking `util/ordering` in `open util/ordering[A]` opens the bundled module read-only; Cmd/Ctrl-clicking `a` in `open a` in `b.als` opens `a.als`.
- [ ] *Find Usages* on `p` lists its uses in both files, and each entry navigates to its location; with a type error in `b.als`, its uses are listed under "Unconfirmed usages (the model has errors)".
- [ ] Placing the caret on `A` highlights the declaration and its uses in the file.
- [ ] Renaming `A` to `B` updates both files, and a single *Undo* reverts it. With a type error in either file, the rename is refused with "Rename needs a model without errors". Renaming `first` is refused because the library module is read-only. Renaming the `x` of `pred p[x: A]` edits it in place, inside `p` only; with a type error in `a.als`, that rename is refused too.
- [ ] The *Structure* tool window lists the signatures (with their fields nested), facts, predicates, assertions and commands, also with a type error in the file, and selecting one moves the caret. The breadcrumbs show `p` while the caret is in its body.
- [ ] *Go to Symbol* finds `p` from `b.als`, and selecting it opens `a.als` at the declaration.
- [ ] Invoking completion in a fact body of `a.als` offers `A`, `p` and `first`, each with its kind's icon and type text ("signature", "predicate", "function"); `first` shows `util/ordering` as tail text, and the declarations of `a.als` show none. Completion after `ord/` in a file with `open util/ordering[A] as ord` offers only the `util/ordering` names.
- [ ] Completion after `run ` offers the predicates and functions only, and after `check ` the assertions only; no names are offered in comments or strings.
- [ ] In `b.als`, completion after `open ` offers `a` and the bundled `util/...` modules (`util/ordering`, `util/integer`, ...), but not `b` itself; completing `open util/ord` inserts `util/ordering`.
- [ ] A file in a subdirectory declaring a module name that does not match its location (such as `sub/x.als` with `module foo/x`) is offered after the others, greyed out, with "cannot be opened from this file"; typing its unique prefix does not insert it.
- [ ] *External Libraries* in the *Project* view shows "Alloy 6.2.0", which lists the bundled `util` modules; opening one shows it read-only.
- [ ] With Reader mode enabled for library sources (*Settings | Editor | Reader Mode*), Cmd/Ctrl-clicking `first` opens `util/ordering` in Reader mode.
- [ ] *Go to Symbol* finds the `first` of `util/ordering` only with "All places"; with "Project files" it does not appear.

### Inline command results

Use a model with `sig Node { succ: lone Node }`, `pred show { some succ }`, `run show for 3`, `check noCycles { no n: Node | n in n.^succ } for 3`, and a slow command, such as `sig R { r: R -> R }` with `run big { all a, b, c, d: R | a->b->c in r implies (b->c->d in r or c->d->a in r) } for 40`, which spends most of its time translating the model.

- [ ] On a machine without a system Java (no `java` on `PATH`, no `JAVA_HOME`), running `run show` with SAT4J completes, and no other process is started (*Help | Diagnostic Tools | Activity Monitor*).
- [ ] Only the `run` and `check` lines have a run icon. Clicking the icon of `run show` executes it right away, with no run configuration created and no Run or other tool window opened.
- [ ] Right after clicking the icon, without reopening the file, the end of the `run show` line shows "Running…" and the gutter icon turns into a spinner; then the hint shows "Instance found · <n> ms" and the run icon comes back; `check noCycles` ends with "Counterexample found · <n> ms". A valid check shows "No counterexample found · <n> ms".
- [ ] Hovering a finished hint shows a tooltip rendered as HTML with line breaks: the executed command, the solver line, the variable and clause counts, and the result in bold, with no repeated progress lines.
- [ ] Typing anywhere in the file dims every hint and adds "(outdated)", and the tooltip says that the file changed. Undoing the edit keeps them outdated; running `run show` again makes only its hint current.
- [ ] A plain click (and also a Cmd/Ctrl-click) on the hint of `run show` (current or outdated) shows the instance in the *Alloy Instance* tool window; clicking `check noCycles` shows the counterexample.
- [ ] The hint of a valid check, or of a run with no instance, is not clickable.
- [ ] Clicking the "Running…" hint of `run big` shows "Stopping…" while the analyzer finishes translating, then "Stopped before completion"; hovering it shows the output received before the stop.
- [ ] Clicking the spinner gutter icon of the running `run big` opens a menu with a single *Stop* item; choosing it stops the analysis, the hint says "Stopped before completion", and the run icon comes back.
- [ ] `run big` shows a background task "Alloy: Run big" in the status bar, whose text follows the analyzer's steps (generating CNF, solving). Cancelling it stops the analysis and the hint says "Stopped before completion".
- [ ] Clicking another run icon while `run big` is running shows "An Alloy analysis is already running" in the editor, and that command's hint does not change.
- [ ] Typing while `run big` runs keeps the editor responsive.
- [ ] Closing the project while `run big` runs stops it.
- [ ] Changing `for 3` to `for 4` without saving and running again makes the tooltip report the new scope.
- [ ] Running a command in a file with a type error shows "Model has errors", and the error appears in the editor.
- [ ] With `run show for 3 expect 1`, the hint says "Instance found, as expected · <n> ms" and the gutter icon becomes the green passed icon, whose tooltip says "Run show — passed last time". With `expect 0` instead, the hint says "contrary to expectation" and the icon is the red failed one. `check noCycles for 3 expect 0` (a counterexample) is red, and `expect 1` is green. Commands without `expect` keep the run icon after their result.
- [ ] With a type error in the file, `run show for 3 expect 1` shows "Model has errors" and the red failed icon.
- [ ] Stopping `run big for 40 expect 1` brings back the plain run icon.
- [ ] Typing in the file keeps the green or red icons, and their tooltip adds "(outdated)". Clicking a green or red icon runs the command again, with the spinner while it runs.
- [ ] After adding `fact acyclic { no n: Node | n in n.^succ }` and `assert noLoops { no iden & succ }` with `check noLoops for 3`, running it shows "No counterexample found · <n> ms" and the run icon; its tooltip ends with a *Vacuity check* section saying that the facts have an instance, so the check did not pass vacuously, with the time.
- [ ] Adding `fact { no Node }` and `fact { some Node }` and running `check noLoops` again shows "No counterexample found (vacuous) · <n> ms" and the yellow vacuous icon, whose tooltip says "passed vacuously last time"; the hint's tooltip says that the facts have no instance within the scope. With `expect 0` added, the icon stays yellow and the hint says "as expected (vacuous)".
- [ ] Practical Alloy's single-node ring (the leader election of the protocol design chapter, with `process` effects that contradict each other on one node, and `check liveness { fairness implies eventually some Elected } for exactly 1 Node`) shows the vacuous icon, and the tooltip says that the assertion's premise (`fairness`) has no instance within the scope.
- [ ] While the vacuity check of a slow check runs (for example, a check on the pigeonhole model whose facts take long to solve), the hint already says "No counterexample found · <n> ms, checking vacuity…", the gutter icon is the spinner, and the status-bar task says "Checking vacuity…". Clicking another run icon shows "An Alloy analysis is already running".
- [ ] Stopping that check from the spinner's *Stop* item, or cancelling its status-bar task, during the vacuity check keeps "No counterexample found · <n> ms" with the run icon, and the tooltip says "Vacuity was not determined. The vacuity check was stopped."
- [ ] Turning off *Command results* in *Settings | Editor | Inlay Hints* hides the hints.

### Test runner

Use `a.als` with `sig Node { succ: lone Node }`, `fact acyclic { no n: Node | n in n.^succ }`, `pred show { some succ }`, `assert noSucc { no succ }`, `assert noLoops { no iden & succ }` and `assert vacuous { all n: Node | n in n.succ implies no Node }`, followed by `run show for 3 expect 1`, `check noSucc for 3 expect 0`, `run { some Node and no Node } for 2`, `check noLoops for 3` and `check vacuous for 3`. For stopping, add the pigeonhole model's `run big for 11` (with symmetry breaking off in the settings, or another slow command) between two fast commands.

- [ ] Right-clicking `a.als` in the *Project* view offers *Run 'All commands in a.als'*; so does right-clicking anywhere in its editor. Choosing it creates a temporary *Alloy Commands* configuration, opens the Run tool window with the test tree, and shows no status-bar progress.
- [ ] The tree has one node per command, in source order: `run show`, `check noSucc`, `run command 3`, `check noLoops`, `check vacuous`, each with its duration. Selecting a node shows the analyzer output of that command, the same as the tooltip of its hint, including the *Vacuity check* section of the checks.
- [ ] `run show` and `check noLoops` passed, and so did `run command 3`, which has no `expect`, and whose output says that no instance was found. `check noSucc` failed with "Counterexample found, contrary to expectation"; `check vacuous` failed with a message saying that it passed vacuously because the assertion's premise has no instance.
- [ ] Double-clicking `check noLoops` opens `a.als` with the caret on `check noLoops`; double-clicking `run command 3` goes to the unlabelled command.
- [ ] *Rerun Failed Tests* executes only `check noSucc` and `check vacuous`, in that order, and the tree shows only their nodes.
- [ ] After the suite, every command line shows its hint and verdict icon (green, red or yellow, or the run icon), as after gutter runs; clicking the hint of `run show` opens its instance.
- [ ] Saving the temporary configuration (*Save Configuration*), adding a command to `a.als`, and running the configuration from the toolbar also executes the new command.
- [ ] With a type error in `a.als`, every node failed with "Model has errors".
- [ ] While a command runs from the gutter, running *All commands in a.als* executes no command, and the Run tool window says "An Alloy analysis is already running". While the suite runs, clicking a gutter icon shows "An Alloy analysis is already running" in the editor.
- [ ] Clicking *Stop* while `run big` (the second of three commands) is solving aborts it: its hint says "Stopped before completion", the third command is not executed, and neither node is shown as passed. A gutter icon can be clicked right after.

### Instance viewer

Use the model of the inline command results checklist, a model that opens `util/ordering[State]`, and a temporal model such as `sig A {}` and `var sig X in A {}` with `run { some X and after no X } for 3 but 1..4 steps`.

- [ ] Before any result is opened, the *Alloy Instance* tool window (on the right of the editor) explains that instances appear when a hint is clicked.
- [ ] Clicking the hint of `run show` opens the tool window on the instance; no other window opens. Clicking the hint of `check noCycles` replaces it with the counterexample.
- [ ] The header names the file, the command as written, its line and "Instance" or "Counterexample"; clicking it opens the file with the caret on the command. Typing in the file dims the header and adds "(outdated)", while the instance stays visible.
- [ ] The *Visualization* tab draws the graph inside the tool window: it scrolls, zooms from its right-click menu (*Zoom In*, *Zoom Out*, *Zoom to Fit*), and resizes with the tool window. With `util/ordering[State]`, the magic layout hides the ordering's helper relations and projects over `State`.
- [ ] For a static result, the *Text* and *Table* tabs show the instance as the Alloy desktop application's Text and Table modes do, without any diff marks. Choosing *Table*, then opening another result, keeps *Table* selected, also after restarting the IDE.
- [ ] For the temporal model, the toolbar has no previous and next buttons and no "State i of n" or "Loops back to state k" label: the trace path strip (below) selects the state. A static result has no strip.
- [ ] For the temporal model, the *Visualization* and *Table* tabs show two panes, captioned "State i" and "State i+1"; at the last state, the second one is the loop target, captioned "State k (loop)". The panes are side by side when the tool window is wider than tall, and stacked when it is taller than wide; resizing or moving the tool window (for example, docking it at the bottom) rearranges them. Each graph pane scrolls on its own, and right-clicking either graph, or the canvas around it, shows its *Zoom In*, *Zoom Out* and *Zoom to Fit* menu. A static result shows a single graph and a single table, without a caption.
- [ ] For the temporal model, the *Text* tab shows the difference from the selected state to the next one (at the last state, to the loop target): one line per signature, each followed by its fields, then the skolems, in the order of the desktop application's Text mode. In `this/X`, an atom only in the selected state is struck through, in the color scheme's deleted-text color, and one only in the next state has its inserted-text color; lines that do not change are dimmed, without marks. Under both a light and a dark theme, the inserted and deleted colors are those of the IDE's diff viewer, and stay readable; switching the theme or the editor color scheme updates them.
- [ ] *New Config*, *New Trace*, *New Init* and *New Fork* have their own icons (restart, step over, drop frame and run to cursor). For a static model, only *New Config* is enabled, and it shows another instance with the same header, keeping the graph's theme and projections. For the temporal model, *New Config*, *New Trace*, *New Init* and *New Fork* are enabled; *New Fork* with state 1 selected (clicked in the trace path strip) keeps states 0 and 1. When nothing new exists, the status line says "No more solutions of that kind". A background task shows while a new solution is searched for, and cancelling it keeps the shown solution. While a command runs, the buttons say that an Alloy analysis is already running. With an outdated result, all four are disabled and their tooltip says to run the command again.
- [ ] The *Projection* link names the projected signatures, or "None"; with `util/ordering[State]` it starts on `State`. Its popup has a checkbox for each signature that can be projected over: checking one shows the graph for one of its atoms, with an atom selector for it below the graphs, and several can be checked at once. For the temporal model, there is a single selector per projected signature, and picking an atom changes both graphs; the atom stays chosen while stepping, as long as it exists. *Clear all* unchecks every one and shows the whole instance again.
- [ ] Under a dark theme (*Dark* or *Darcula*), the *Visualization* tab has the IDE's dark background and no white area: node outlines and black edges are light, node labels are light on dark, muted fills, and the magic layout's colors keep their hue (a gold signature is a dark gold, a blue one a dark blue), each distinguishable from the canvas. Colored edges and their labels are lightened and stand out. Selecting a node or an edge stays visible. Under a light theme, the graph has the visualizer's own colors on white.
- [ ] With an instance shown, switching the IDE theme from light to dark (and back) in *Settings | Appearance* redraws the graph in the new colors right away, without opening the result again.

#### Trace path strip

Also use a lasso of three states that loops back to state 1: `abstract sig V {}`, `one sig V1, V2, V3 extends V {}`, `var one sig Cur in V {}`, `fact { Cur = V1 and after Cur = V2 and always (Cur = V2 implies after Cur = V3) and always (Cur = V3 implies after Cur = V2) }` and `run {} for 3 but 1..3 steps`.

- [ ] For the lasso, a strip shows circles numbered 0, 1 and 2 from left to right, each number in the middle of its circle, an arrow from 0 to 1 and from 1 to 2, and a curved arrow above them from 2 back to 1, as in the Alloy visualizer. With the 15..20-step trace below, there is an arrow between every two consecutive circles, also after scrolling. The strip stays visible in the *Visualization*, *Text* and *Table* tabs. A static result has no strip.
- [ ] With four values `V1` to `V4` and the fact `Cur = V1 and always (Cur = V1 implies after Cur = V2) and always (Cur = V2 implies after Cur = V3) and always (Cur = V3 implies after Cur = V4) and always (Cur = V4 implies after Cur = V1)` (and `1..4 steps`), the curved arrow goes from circle 3 to circle 0, over circles 1 and 2. With two values and the fact `Cur = V1 and after always Cur = V2` (and `1..2 steps`), the curved arrow leaves circle 1 and returns to it.
- [ ] In a wide tool window, the strip is on the toolbar's line, after the New buttons and the *Projection* link. Narrowing the tool window until the strip no longer fits moves it to a line of its own below them, and widening it again moves it back; in both places, the strip has the same background as the toolbar, with no visible band or edge.
- [ ] At state 0, circles 0 and 1 are highlighted and circle 2 is dimmed. Clicking circle 1 highlights 1 and 2; clicking circle 2 highlights 2 and 1, joined by the loop arrow, with 0 dimmed. The pane captions always name the highlighted pair.
- [ ] Clicking circle 2 at state 0 makes the panes "State 2" and "State 1 (loop)"; clicking between circles changes nothing. With a projection and a chosen atom, clicking a circle keeps both.
- [ ] Hovering circle 0 shows "State 0"; hovering circle 1 shows that state 2 loops back to it.
- [ ] With `run { some X and after no X } for 3 but 15..20 steps` in the temporal model, in a narrow tool window, the strip is below the toolbar and scrolls horizontally, without a vertical scroll bar, and the horizontal one does not hide the circles; clicking a circle keeps the highlighted pair in view, and at the last state, when the loop target is far away, the selected state.
- [ ] Under a light theme, highlighted circles are white, dimmed ones grey, and numbers and arrows black. Under a dark theme (*Dark* or *Darcula*), the strip shows the toolbar's background, the arrows, outlines and numbers are light, dimmed circles have the toolbar's background, and highlighted circles are lighter. Switching the theme in *Settings | Appearance* redraws the strip in the new colors right away.

#### Evaluator

Use the lasso of the trace path strip checklist, and the model of the inline command results checklist.

- [ ] With a result shown, the toolbar's *Evaluator* toggle shows a panel below the tabs, with the caption "Evaluating at state 0", the expression field, *Add to Watches* and the watches list; turning it off hides it. Dragging the divider resizes both parts, and the panel's visibility and size stay for the next result and after restarting the IDE. Before any result is opened, there is no panel.
- [ ] Typing `succ` in the field and pressing Enter shows `succ = {n tuples}` under the field, which expands to one row per tuple; `some succ` shows `true`, and `Undefined` shows the analyzer's message in red. Pressing Ctrl+Shift+Enter (Cmd+Shift+Enter on macOS) in the field adds the expression to the end of the watches.
- [ ] After evaluating `succ` and then `#Node`, pressing Up in the field brings back `#Node`, then `succ`, and Down goes forward again.
- [ ] With the lasso and a watch `Cur`, clicking circle 1 in the trace path strip makes the caption "Evaluating at state 1" and the watch `{1 tuple}` with the debugger's changed-value color, under both a light and a dark theme (*Dark* or *Darcula*); a watch `V1` keeps the normal color. Clicking circle 1 again removes the highlight.
- [ ] The watches toolbar adds, removes, edits (also with a double-click or F2) and moves watches up and down. After restarting the IDE, opening a result of the same file shows its watches, evaluated; a result of another file shows that file's own watches.
- [ ] Typing in the file adds "(outdated result)" to the caption, and evaluating still shows the values of the shown result.
- [ ] With `sig R { r: R -> R }` and `run many { #R = 30 } for 30`, a watch `all a, b, c, d, e: R | a->b->c in r implies c->d->e in r` shows "Evaluating…" for a while, and the IDE stays responsive meanwhile. With the lasso and a slow watch, clicking another state during the evaluation ends with the values of that state.

### Analyzer settings

- [ ] *Settings | Languages & Frameworks | Alloy* shows the nine options with their defaults (solver sat4j, skolem depth 1, prevent overflows on, core minimization Fast, core granularity Top-level, *Check vacuity of passing checks* on below a separator, the rest off), and the skolem depth only offers 0 to 4. The solver list offers the SAT4J variants, and no native (JNI) solver.
- [ ] With `yices` installed (for example with Homebrew) and the IDE started from the desktop, not from a shell, the solver list also offers `yices.external`; choosing it and running `run show` reports `Solver=yices.external` in the tooltip, and stopping a long run ends the `yices` process.
- [ ] With skolem depth 3 set in the Alloy desktop application and the IDE settings left at their defaults, the tooltip of a run in the IDE shows `SkolemDepth=1`.
- [ ] After changing the IDE settings and running commands, the desktop application still shows its own values (skolem depth 3).
- [ ] Changing the skolem depth to 2 and applying leaves the diagnostics of open files in place, and the tooltip of the next run shows `SkolemDepth=2`. Results from before the change are shown as outdated.
- [ ] Applying a change while `run big` is running lets it go on; its result is shown as outdated when it arrives.
- [ ] Turning off *Check vacuity of passing checks* and applying shows earlier results as outdated; a check that then finds no counterexample ends right away, without "checking vacuity…" in its hint and without a *Vacuity check* section in its tooltip. Turning it back on brings the vacuity check back.
- [ ] A saved solver that is no longer available is shown as "(unavailable)", and the next run uses sat4j and shows a warning about it in the tooltip of its hint.