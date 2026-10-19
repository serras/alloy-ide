# Alloy 6 for IntelliJ

> [Alloy](https://alloytools.org/) 6 in your favorite IDE

## Features

- **File type**: `.als` files are recognized as Alloy models and shown with the Alloy icon. Alloy models embedded in Markdown (`.md`) files are not supported.
- **Syntax highlighting**: keywords, temporal operators (including the prime `'`), operators, identifiers, numbers, strings, comments (line, block and documentation), braces, parentheses, brackets, commas and dots, and invalid characters. Every category can be customized in *Settings | Editor | Color Scheme | Alloy*.
- **Running commands**: every `run` and `check` command has a run icon in the gutter that creates an *Alloy command* run configuration. The Run console streams the analyzer output, and its links open instances and counterexamples in the Alloy visualizer or highlight an unsat core in the editor. *Stop* aborts the analysis. Only one analysis runs at a time in each project.

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

### Manual checklist: command execution

Use a model with `sig Node { succ: lone Node }`, `pred show { some succ }`, `run show for 3`, `check noCycles { no n: Node | n in n.^succ } for 3` and a slow command such as `run big { #Node = 30 } for 30 but 7 Int`.

- [ ] Only the `run` and `check` lines have a run icon. Clicking the icon of `run show` and choosing *Run* creates a temporary *Alloy command* configuration and runs it; the same works for `check noCycles`.
- [ ] Changing `for 3` to `for 4` without saving and running again makes the analyzer report the new scope.
- [ ] The progress line "Writing the XML file..." is replaced in place by the result rather than kept as an extra line.
- [ ] The "Instance" link of `run show` and the "Counterexample" link of `check noCycles` open the Alloy visualizer window.
- [ ] With *Unsat core minimization* enabled, a valid check shows a core link that highlights the core locations in the editor as "part of unsat core" warnings.
- [ ] *Stop* during `run big` ends the run with "Stopped".
- [ ] Starting a second command while `run big` is running is refused with "An Alloy analysis is already running".
- [ ] A saved configuration whose command was renamed fails with "Command ... was not found in ..."; one whose file was deleted reports the missing file and does not start.
- [ ] Running a command in a file with a type error reports that the file has errors, and the error appears in the editor.

## Attribution

The lexer (`src/main/grammars/Alloy.flex`) is a port of `Alloy.lex` from the [Alloy Analyzer](https://github.com/AlloyTools/org.alloytools.alloy) (tag `v6.2.0`, `org.alloytools.alloy.core/parser/Alloy.lex`), Copyright (c) 2006-2008 Felix Chang, distributed under the MIT license. The ported file keeps the original license header.
