# Proposal

## Why

Completion in Alloy files offers only keywords. Writing a model means typing every signature, field, predicate and module path by hand, even though the plugin already knows them by name. It resolves them for Go to Declaration in the same file, in opened project modules and in the bundled `util/*` modules. The bundled modules also behave unlike library code in other languages: they open as plain read-only files, outside Reader mode, and Go to Symbol and the Project view do not show them.

## What Changes

- **Module paths in `open`.** Completion after `open` offers the modules that the analyzer would load from the current file:
  - **Project modules.** Each project `.als` file is offered by its declared `module` name, when that name resolves back to the file from the current file as Alloy 6.2.0 resolves it. A file with no `module` declaration is offered by its path-derived name.
  - **Unreachable modules.** Project files whose declared name would not resolve from here are still listed, greyed out with a hint, so the user can see why they would not work.
  - **Bundled modules.** The `util/*` modules (`boolean`, `graph`, `integer`, `natural`, `ordering`, `relation`, `seqrel`, `sequence`, `sequniv`, `ternary`, `time`) are offered too. A project module with the same path takes precedence, as it does in the analyzer.
- **Names in scope.** Completion of an identifier offers the names visible at the caret, using the same name-based scopes that resolution uses: binders and parameters, the fields of the enclosing signature, the module's top-level declarations, and the declarations of opened modules.
  - It works while the model has errors.
  - Each item shows its kind (signature, field, predicate, function, assertion, enum, macro, binder) with an icon and type text. Items from an opened module show that module as tail text.
- **Qualified names.** After `alias/` (for example `ord/`), only the declarations of the module opened with that alias or path are offered.
- **Context filtering.**
  - The target of a `run` command offers predicates and functions; the target of `check` offers assertions.
  - The parents after `extends` or `in` in a signature header offer signatures.
- **Go to Declaration on module paths.** Cmd/Ctrl-clicking the path of an `open` declaration opens the module file, whether it is a project file or a bundled module.
- **The bundled library is a library.** The `util/*` modules are exposed to the IDE as an "Alloy 6.2.0" library. They appear under External Libraries in the Project view and are indexed, so Go to Symbol finds them when searching all places. They also open in Reader mode according to the user's Reader mode setting for library sources.
- **Out of scope:**
  - Completing names from project modules that are not opened, or adding the `open` automatically.
  - Completion of scopes and numbers in commands.

## Capabilities

### New Capabilities
- `alloy-completion`: completion of Alloy names and module paths (scopes, contexts, presentation and module reachability). Keyword completion stays in `alloy-editor`.

### Modified Capabilities
- `alloy-semantics`:
  - "Go to declaration" also covers the path of an `open` declaration, and bundled modules open in Reader mode.
  - "Go to Symbol" also finds bundled library declarations when searching outside the project.
  - A new requirement covers the bundled modules being exposed as a library.

## Impact

- **Code.**
  - New completion contributor(s) in `src/main/kotlin/com/serranofp/alloy/editor/`.
  - `AlloyScopes` (`semantics/AlloyReference.kt`) gains enumeration of every visible declaration besides lookup by name.
  - `AlloyModelService.openedPath` is reused to decide module reachability.
  - `AlloyQualifiedNameMixin` gives `open` paths a reference to the module file.
  - A new `AdditionalLibraryRootsProvider` for the bundled `models/` directory of `org.alloytools.alloy.core`.
  - `plugin.xml` registrations.
- **Dependencies.** None new. The bundled models stay inside the `org.alloytools.alloy.core` 6.2.0 JAR.
- **Tests.**
  - Completion tests for each context.
  - Tests for module reachability and the greyed-out hint.
  - Reference tests for `open` paths.
  - Tests that library roots are provided and that library declarations are indexed.
- **Manual checks.** New items go into the README's semantic features checklist: Reader mode, the External Libraries node and the look of the lookup.
