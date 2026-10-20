# Alloy 6 for IntelliJ

> [Alloy](https://alloytools.org/) 6 in your favorite IDE

## Features

- **File type**: `.als` files are recognized as Alloy models and shown with the Alloy icon. Alloy models embedded in Markdown (`.md`) files are not supported.
- **Syntax highlighting**: keywords, temporal operators (including the prime `'`), operators, identifiers, numbers, strings, comments (line, block and documentation), braces, parentheses, brackets, commas and dots, and invalid characters. Every category can be customized in *Settings | Editor | Color Scheme | Alloy*.
- **Running commands**: every `run` and `check` command has a run icon in the gutter that executes it right away. The result appears as an inlay hint at the end of the command's line: running, instance found, no instance found, counterexample found, no counterexample found, stopped, or a failure such as "Model has errors". Hovering the hint shows everything the analyzer reported. Clicking it opens an instance or counterexample in the Alloy visualizer, highlights an unsat core in the editor, or stops a running analysis. Editing the file or changing the analyzer settings marks its results as outdated. Progress is shown as a cancellable background task in the status bar. Only one analysis runs at a time in each project. The hints can be turned off in *Settings | Editor | Inlay Hints*.
- **Analyzer settings**: *Settings | Languages & Frameworks | Alloy* sets the solver, skolem depth, overflow prevention, unsat core minimization and granularity, non-fatal warnings, recording of the Kodkod input/output and partial instance inference for every project. The solver list only shows the solvers that can run on this machine: the pure-Java SAT4J variants, and solvers whose executable is on the `PATH`. These settings are independent of the Alloy desktop application's preferences. Applying a change restarts the language server.

## Development

Run `./gradlew runIde` to start a sandbox IDE with the plugin, and `./gradlew check` to run the tests.

### Manual checklist: language server

Some behavior depends on a live language server and the IDE UI, so it is checked by hand in `runIde`. Use a project with two files: `a.als` containing `sig A {}` and `pred p[x: A] { some x }`, and `b.als` containing `open a` and `run p for 3`.

- [ ] On a machine without a system Java (no `java` on `PATH`, no `JAVA_HOME`), opening `a.als` starts the server, and opening `b.als` does not start a second one (*Help | Diagnostic Tools | Activity Monitor*, or the language services widget in the status bar).
- [ ] No "Execute" code lens or link appears above or on `run p for 3`.
- [ ] Typing `sig C { f: Undefined }` underlines `Undefined` as an error with the server's message on hover; fixing it removes the error.
- [ ] Hovering a use of `A` shows the server's hover popup.
- [ ] Cmd/Ctrl-clicking `p` in `b.als` opens `a.als` at the predicate.
- [ ] *Find Usages* on `A` lists its uses, and each entry navigates to its location.
- [ ] Renaming `A` to `B` updates both files, and a single *Undo* reverts it.
- [ ] The *Structure* tool window and the breadcrumbs list the signatures, predicates and commands, and selecting one moves the caret.
- [ ] *Go to Symbol* finds `p`, and selecting it opens `a.als` at the declaration.
- [ ] Killing the server process (`kill <pid>`) shows the server as failed in the language services widget, with its stderr available and a restart action that brings it back.
- [ ] Closing the project terminates the server process.

### Manual checklist: inline command results

Use a model with `sig Node { succ: lone Node }`, `pred show { some succ }`, `run show for 3`, `check noCycles { no n: Node | n in n.^succ } for 3` and a slow command such as `run big { #Node = 30 } for 30 but 7 Int`.

- [ ] Only the `run` and `check` lines have a run icon. Clicking the icon of `run show` executes it right away, with no run configuration created and no Run or other tool window opened.
- [ ] While it runs, the end of the `run show` line shows "Running…", then "Instance found · <n> ms"; `check noCycles` ends with "Counterexample found · <n> ms". A valid check shows "No counterexample found · <n> ms".
- [ ] Hovering a finished hint shows a tooltip rendered as HTML with line breaks: the executed command, the solver line, the variable and clause counts, and the result in bold. The "Writing the XML file..." progress line is not repeated.
- [ ] Typing anywhere in the file dims every hint and adds "(outdated)", and the tooltip says that the file changed. Undoing the edit keeps them outdated; running `run show` again makes only its hint current.
- [ ] Clicking the hint of `run show` (current or outdated) opens the instance in the Alloy visualizer; clicking `check noCycles` opens the counterexample.
- [ ] With *Unsat core minimization* enabled, clicking the hint of a valid check highlights the core locations in the editor as "part of unsat core" warnings. A result without a core is not clickable.
- [ ] Clicking the "Running…" hint of `run big` stops it, and the hint says "Stopped".
- [ ] `run big` shows a background task "Alloy: Run big" in the status bar, whose text follows the analyzer's steps (generating CNF, solving). Cancelling it stops the analysis and the hint says "Stopped".
- [ ] Clicking another run icon while `run big` is running shows "An Alloy analysis is already running" in the editor, and that command's hint does not change.
- [ ] Changing `for 3` to `for 4` without saving and running again makes the tooltip report the new scope.
- [ ] Running a command in a file with a type error shows "Model has errors", and the error appears in the editor.
- [ ] With the language server killed and failed, clicking a run icon shows "Server unavailable", and its tooltip says that the Alloy language server is unavailable.
- [ ] Turning off *Command results* in *Settings | Editor | Inlay Hints* hides the hints.

### Manual checklist: analyzer settings

- [ ] *Settings | Languages & Frameworks | Alloy* shows the eight options with their defaults (solver sat4j, skolem depth 1, prevent overflows on, core minimization Fast, core granularity Top-level, the rest off), and the skolem depth only offers 0 to 4.
- [ ] With skolem depth 3 set in the Alloy desktop application and the IDE settings left at their defaults, the tooltip of a run in the IDE shows `SkolemDepth=1`.
- [ ] After changing the IDE settings and running commands, the desktop application still shows its own values (skolem depth 3).
- [ ] Changing the skolem depth to 2 and applying restarts the server: diagnostics reappear in open files, and the tooltip of the next run shows `SkolemDepth=2`. Results from before the change are shown as outdated.
- [ ] Clicking *OK* without changes does not restart the server.
- [ ] Applying a change while `run big` is running ends that run: its hint says "Interrupted", and its tooltip says "The analysis was interrupted by a settings change".
- [ ] A saved solver that is no longer available is shown as "(unavailable)", and the next run uses sat4j and shows a warning about it in the tooltip of its hint.

## Attribution

The lexer (`src/main/grammars/Alloy.flex`) is a port of `Alloy.lex` from the [Alloy Analyzer](https://github.com/AlloyTools/org.alloytools.alloy) (tag `v6.2.0`, `org.alloytools.alloy.core/parser/Alloy.lex`), Copyright (c) 2006-2008 Felix Chang, distributed under the MIT license. The ported file keeps the original license header.
