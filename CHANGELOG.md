<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Alloy 6 for IntelliJ Changelog

## [Unreleased]

### Added

- Alloy file type for `.als` files, with syntax highlighting (configurable in *Settings | Editor | Color Scheme | Alloy*) for keywords, temporal operators, operators, literals and comments.
- Structure-aware editing that works without the language server: brace matching and auto-closing, line and block comments, folding of bodies, comments and imports, and keyword completion.
- Integration with the Alloy 6.2.0 language server, running in its own process on the IDE's Java runtime: error and warning diagnostics, hover, go to declaration, find usages, rename, the *Structure* view, breadcrumbs and *Go to Symbol*.
- Running `run` and `check` commands from the gutter, with their results inline (see *Changed*).
- Analyzer settings in *Settings | Languages & Frameworks | Alloy* (solver, skolem depth, overflow prevention, unsat core minimization and granularity, non-fatal warnings, recording the Kodkod input/output, partial instance inference), independent of the Alloy desktop application's preferences.

### Changed

- Command results are shown as inlay hints at the end of each command's line instead of in a console: the hint names the outcome, its tooltip holds the full analyzer output, and clicking it opens the instance, counterexample or unsat core, or stops a running analysis. Results are marked as outdated after an edit of the file or a change of the analyzer settings, and progress is a cancellable background task in the status bar.

### Removed

- The run configuration type for `run` and `check` commands, its configuration producer and its Run tool window output. Commands are executed from the gutter.

### Fixed

- Opening an unsat core: the server reports the core as a bare file path, which its own `OpenModel` ignores, so the plugin adds the `CORE: ` prefix.

### Known limitations

- Only solvers that need no native library are available: the pure-Java SAT4J variants, and solvers whose executable is on the `PATH`.
- Instances and counterexamples open in the Alloy visualizer, a separate window outside the IDE.
- Alloy models embedded in Markdown (`.md`) files are not supported.
- Only one analysis runs at a time in each project.
