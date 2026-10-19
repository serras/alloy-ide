# Alloy 6 for IntelliJ

> [Alloy](https://alloytools.org/) 6 in your favorite IDE

## Features

- **File type**: `.als` files are recognized as Alloy models and shown with the Alloy icon. Alloy models embedded in Markdown (`.md`) files are not supported.
- **Syntax highlighting**: keywords, temporal operators (including the prime `'`), operators, identifiers, numbers, strings, comments (line, block and documentation), braces, parentheses, brackets, commas and dots, and invalid characters. Every category can be customized in *Settings | Editor | Color Scheme | Alloy*.

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

## Attribution

The lexer (`src/main/grammars/Alloy.flex`) is a port of `Alloy.lex` from the [Alloy Analyzer](https://github.com/AlloyTools/org.alloytools.alloy) (tag `v6.2.0`, `org.alloytools.alloy.core/parser/Alloy.lex`), Copyright (c) 2006-2008 Felix Chang, distributed under the MIT license. The ported file keeps the original license header.
