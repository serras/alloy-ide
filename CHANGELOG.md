<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Alloy 6 for IntelliJ Changelog

## [Unreleased]

### Added

- Alloy file type for `.als` files, with syntax highlighting (configurable in *Settings | Editor | Color Scheme | Alloy*) for keywords, temporal operators, operators, literals and comments.
- Structure-aware editing that works without the language server: brace matching and auto-closing, line and block comments, folding of bodies, comments and imports, and keyword completion.
- Integration with the Alloy 6.2.0 language server, running in its own process on the IDE's Java runtime: error and warning diagnostics, hover, go to declaration, find usages, rename, the *Structure* view, breadcrumbs and *Go to Symbol*.
- Running `run` and `check` commands from the gutter or from *Alloy command* run configurations, with the analyzer output in the Run console, *Stop*, and links that open instances and counterexamples in the Alloy visualizer or highlight unsat cores in the editor.
- Analyzer settings in *Settings | Languages & Frameworks | Alloy* (solver, skolem depth, overflow prevention, unsat core minimization and granularity, non-fatal warnings, recording the Kodkod input/output, partial instance inference), independent of the Alloy desktop application's preferences.

### Known limitations

- Only solvers that need no native library are available: the pure-Java SAT4J variants, and solvers whose executable is on the `PATH`.
- Instances and counterexamples open in the Alloy visualizer, a separate window outside the IDE.
- Alloy models embedded in Markdown (`.md`) files are not supported.
- Only one analysis runs at a time in each project.
