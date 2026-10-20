# Alloy 6 for IntelliJ

> [Alloy](https://alloytools.org/) 6 in your favorite IDE

## Features

The plugin includes the Alloy 6.2.0 analyzer and runs it inside the IDE, on its own Java runtime: no separate process, no system Java installation and no IntelliJ Platform LSP support are needed. It installs in any IntelliJ-based IDE from 2026.2.

- **File type**: `.als` files are recognized as Alloy models and shown with the Alloy icon. Alloy models embedded in Markdown (`.md`) files are not supported.
- **Syntax highlighting**: keywords, temporal operators (including the prime `'`), operators, identifiers, numbers, strings, comments (line, block and documentation), braces, parentheses, brackets, commas and dots, and invalid characters. Every category can be customized in *Settings | Editor | Color Scheme | Alloy*.
- **Editing**: brace matching and auto-closing, line and block comments, folding of bodies, comments and imports, and keyword completion, on any content, including models with errors.
- **Diagnostics**: the analyzer's syntax and type errors and its warnings, at the ranges it reports, for the current text of the file, saved or not. An error in an opened module is also reported at its `open`. Diagnostics appear in the error stripe and the *Problems* tool window.
- **Semantic features**: hover and *Quick Documentation* (the declaration as the analyzer explains it, with its `/** ... */` comment), go to declaration (also into the bundled `util/*` library modules, read-only), highlight usages, *Find Usages*, rename, the *Structure* view, breadcrumbs and *Go to Symbol*. When the model type-checks, references resolve exactly as the analyzer resolves them; while it does not, they resolve by name, innermost scope first, so navigation keeps working while editing. *Find Usages* lists the uses found by name in files with errors as unconfirmed, and rename is refused until every affected file type-checks.
- **Running commands**: every `run` and `check` command has a run icon in the gutter that executes it right away. The result appears as an inlay hint at the end of the command's line: running, instance found, no instance found, counterexample found, no counterexample found, stopped before completion, or a failure such as "Model has errors". Hovering the hint shows everything the analyzer reported. The gutter icon of a running command is a spinner, and clicking it opens a menu with *Stop*. Clicking the hint shows an instance or counterexample in the *Alloy Instance* tool window, or stops a running analysis. Editing the file or changing the analyzer settings marks its results as outdated. Progress is shown as a cancellable background task in the status bar. Only one analysis runs at a time in each project.
- **Alloy Instance tool window** (on the right of the editor): shows one instance or counterexample at a time, with a header naming the file, command and line it came from (clicking it navigates there). Its *Visualization* tab draws the graph with the Alloy visualizer's magic layout; the *Text* and *Table* tabs show it as the analyzer prints it. Its toolbar asks for a new configuration, trace, initial state or fork, which keep the graph's theme and projections, steps through the states of a trace, and projects over any number of signatures.
- **Analyzer settings**: *Settings | Languages & Frameworks | Alloy* sets the solver, skolem depth, overflow prevention, unsat core minimization and granularity, non-fatal warnings, recording of the Kodkod input/output and partial instance inference for every project. The solver list offers the pure-Java SAT4J variants, and the external solvers whose executables are on the `PATH`: Yices (`yices`), Plingeling (`plingeling`), and Electrod with NuSMV or nuXmv (`electrod` with `NuSMV` or `nuXmv`). Solvers that need a native library are not offered, since they could crash the IDE. These settings are independent of the Alloy desktop application's preferences. Applied changes take effect with the next analysis; a running analysis goes on with the settings it started with.

Analyses use the IDE's own memory. An analysis that runs out of memory or stack is reported as a failure, but models with very large scopes are better run in the Alloy desktop application.

## Development

Run `./gradlew runIde` to start a sandbox IDE with the plugin, and `./gradlew check` to run the tests.

Some behavior depends on the IDE UI, so it is checked by hand in `runIde`. Unless a checklist says otherwise, use a project with two files: `a.als` containing `sig A {}` and `pred p[x: A] { some x }`, and `b.als` containing `open a` and `run p for 3`.

### Manual checklist: diagnostics

- [ ] Typing `sig C { f: Undefined }` in `a.als` underlines `Undefined` as an error, and hovering it shows "The name "Undefined" cannot be found."; fixing it removes the error without saving the file.
- [ ] Adding `fact { some A & none }` highlights `&` as a warning, and its tooltip shows the analyzer's message over several lines.
- [ ] With the error in `a.als`, `b.als` shows an error at `open a` saying that the opened module has errors, with the error's message; fixing `a.als` removes it from both files.
- [ ] The error stripe marks the error, and the *Problems* tool window's *File* tab lists it for the current file; selecting the entry moves the caret to `Undefined`.
- [ ] Typing quickly in a large model keeps the editor responsive while diagnostics update.

### Manual checklist: semantic features

Add `open util/ordering[A]` and `fact { some first }` to `a.als`.

- [ ] Hovering a use of `A` shows a popup with the signature as the analyzer explains it; a `/** ... */` comment before a declaration appears under it. With a type error elsewhere in the file, the popup shows the declaration's source instead.
- [ ] Cmd/Ctrl-clicking `p` in `b.als` opens `a.als` at the predicate, also while `b.als` has a type error.
- [ ] Cmd/Ctrl-clicking `first` opens the bundled `util/ordering` module read-only, at the declaration of `first`.
- [ ] *Find Usages* on `p` lists its uses in both files, and each entry navigates to its location; with a type error in `b.als`, its uses are listed under "Unconfirmed usages (the model has errors)".
- [ ] Placing the caret on `A` highlights the declaration and its uses in the file.
- [ ] Renaming `A` to `B` updates both files, and a single *Undo* reverts it. With a type error in either file, the rename is refused with "Rename needs a model without errors". Renaming `first` is refused because the library module is read-only. Renaming the `x` of `pred p[x: A]` edits it in place, inside `p` only; with a type error in `a.als`, that rename is refused too.
- [ ] The *Structure* tool window lists the signatures (with their fields nested), facts, predicates, assertions and commands, also with a type error in the file, and selecting one moves the caret. The breadcrumbs show `p` while the caret is in its body.
- [ ] *Go to Symbol* finds `p` from `b.als`, and selecting it opens `a.als` at the declaration.

### Manual checklist: inline command results

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
- [ ] Turning off *Command results* in *Settings | Editor | Inlay Hints* hides the hints.

### Manual checklist: instance viewer

Use the model of the inline command results checklist, a model that opens `util/ordering[State]`, and a temporal model such as `sig A {}` and `var sig X in A {}` with `run { some X and after no X } for 3 but 1..4 steps`.

- [ ] Before any result is opened, the *Alloy Instance* tool window (on the right of the editor) explains that instances appear when a hint is clicked.
- [ ] Clicking the hint of `run show` opens the tool window on the instance; no other window opens. Clicking the hint of `check noCycles` replaces it with the counterexample.
- [ ] The header names the file, the command as written, its line and "Instance" or "Counterexample"; clicking it opens the file with the caret on the command. Typing in the file dims the header and adds "(outdated)", while the instance stays visible.
- [ ] The *Visualization* tab draws the graph inside the tool window: it scrolls, zooms from its right-click menu (*Zoom In*, *Zoom Out*, *Zoom to Fit*), and resizes with the tool window. With `util/ordering[State]`, the magic layout hides the ordering's helper relations and projects over `State`.
- [ ] For a static result, the *Text* and *Table* tabs show the instance as the Alloy desktop application's Text and Table modes do, without any diff marks. Choosing *Table*, then opening another result, keeps *Table* selected, also after restarting the IDE.
- [ ] For the temporal model, the toolbar shows "State 0 of n" and the loop-back state; next and previous step through the trace one state at a time in every tab, and stepping past the last state goes to the loop target. A static result has no stepper.
- [ ] For the temporal model, the *Visualization* and *Table* tabs show two panes, captioned "State i" and "State i+1"; at the last state, the second one is the loop target, captioned "State k (loop)". The panes are side by side when the tool window is wider than tall, and stacked when it is taller than wide; resizing or moving the tool window (for example, docking it at the bottom) rearranges them. Each graph pane scrolls on its own, and right-clicking either graph, or the canvas around it, shows its *Zoom In*, *Zoom Out* and *Zoom to Fit* menu. A static result shows a single graph and a single table, without a caption.
- [ ] For the temporal model, the *Text* tab shows the difference from the selected state to the next one (at the last state, to the loop target): one line per signature, each followed by its fields, then the skolems, in the order of the desktop application's Text mode. In `this/X`, an atom only in the selected state is struck through, in the color scheme's deleted-text color, and one only in the next state has its inserted-text color; lines that do not change are dimmed, without marks. Under both a light and a dark theme, the inserted and deleted colors are those of the IDE's diff viewer, and stay readable; switching the theme or the editor color scheme updates them.
- [ ] *New Config*, *New Trace*, *New Init* and *New Fork* have their own icons (restart, step over, drop frame and run to cursor). For a static model, only *New Config* is enabled, and it shows another instance with the same header, keeping the graph's theme and projections. For the temporal model, *New Config*, *New Trace*, *New Init* and *New Fork* are enabled; *New Fork* at state 1 keeps states 0 and 1. When nothing new exists, the status line says "No more solutions of that kind". A background task shows while a new solution is searched for, and cancelling it keeps the shown solution. While a command runs, the buttons say that an Alloy analysis is already running. With an outdated result, all four are disabled and their tooltip says to run the command again.
- [ ] The *Projection* link names the projected signatures, or "None"; with `util/ordering[State]` it starts on `State`. Its popup has a checkbox for each signature that can be projected over: checking one shows the graph for one of its atoms, with an atom selector for it below the graphs, and several can be checked at once. For the temporal model, there is a single selector per projected signature, and picking an atom changes both graphs; the atom stays chosen while stepping, as long as it exists. *Clear all* unchecks every one and shows the whole instance again.
- [ ] Under a dark theme (*Dark* or *Darcula*), the *Visualization* tab has the IDE's dark background and no white area: node outlines and black edges are light, node labels are light on dark, muted fills, and the magic layout's colors keep their hue (a gold signature is a dark gold, a blue one a dark blue), each distinguishable from the canvas. Colored edges and their labels are lightened and stand out. Selecting a node or an edge stays visible. Under a light theme, the graph has the visualizer's own colors on white.
- [ ] With an instance shown, switching the IDE theme from light to dark (and back) in *Settings | Appearance* redraws the graph in the new colors right away, without opening the result again.

### Manual checklist: analyzer settings

- [ ] *Settings | Languages & Frameworks | Alloy* shows the eight options with their defaults (solver sat4j, skolem depth 1, prevent overflows on, core minimization Fast, core granularity Top-level, the rest off), and the skolem depth only offers 0 to 4. The solver list offers the SAT4J variants, and no native (JNI) solver.
- [ ] With `yices` installed (for example with Homebrew) and the IDE started from the desktop, not from a shell, the solver list also offers `yices.external`; choosing it and running `run show` reports `Solver=yices.external` in the tooltip, and stopping a long run ends the `yices` process.
- [ ] With skolem depth 3 set in the Alloy desktop application and the IDE settings left at their defaults, the tooltip of a run in the IDE shows `SkolemDepth=1`.
- [ ] After changing the IDE settings and running commands, the desktop application still shows its own values (skolem depth 3).
- [ ] Changing the skolem depth to 2 and applying leaves the diagnostics of open files in place, and the tooltip of the next run shows `SkolemDepth=2`. Results from before the change are shown as outdated.
- [ ] Applying a change while `run big` is running lets it go on; its result is shown as outdated when it arrives.
- [ ] A saved solver that is no longer available is shown as "(unavailable)", and the next run uses sat4j and shows a warning about it in the tooltip of its hint.

## Attribution

The lexer (`src/main/grammars/Alloy.flex`) is a port of `Alloy.lex` from the [Alloy Analyzer](https://github.com/AlloyTools/org.alloytools.alloy) (tag `v6.2.0`, `org.alloytools.alloy.core/parser/Alloy.lex`), Copyright (c) 2006-2008 Felix Chang, distributed under the MIT license. The ported file keeps the original license header.
