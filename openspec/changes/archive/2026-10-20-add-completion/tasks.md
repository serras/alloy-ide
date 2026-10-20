# Tasks

## 1. Bundled modules as a library

- [x] 1.1 Add `AlloyLibraryRootsProvider` (design §6). It returns one `SyntheticLibrary` rooted at the analyzer JAR's `models/` directory, presented as "Alloy " + the analyzer's version (the core JAR's `Bundle-Version` without its last segment, since `Version.getShortversion()` is empty with the core JAR) with the Alloy icon. Register it in `plugin.xml` as `additionalLibraryRootsProvider`. Verify with a new `AlloyLibraryRootsProviderTest`:
  - the project's additional libraries contain one library, whose root holds `util/ordering.als`;
  - `ProjectFileIndex.isInLibrary` holds for the file that `AlloyPositions.libraryFile` returns for `util/ordering`.
- [x] 1.2 Check that library declarations are indexed and that Go to Symbol honours the scope. Verify with new cases in `AlloySymbolContributorTest`:
  - `first` is found from `util/ordering` with `GlobalSearchScope.allScope`, and selecting it targets `models/util/ordering.als`;
  - it is not found with `projectScope`.

  `AlloyNameIndexTest`, `AlloySymbolContributorTest`, `AlloyFindUsagesTest` and `AlloyRenameTest` still pass, including the refusal to rename library declarations.
- [x] 1.3 Add README "semantic features" manual checklist items:
  - External Libraries shows "Alloy 6.2.0" with the `util` modules;
  - with Reader mode enabled for library sources, Cmd/Ctrl-clicking `first` opens `util/ordering` in Reader mode;
  - Go to Symbol finds `first` only with "All places".

  Verify by re-reading the checklist against the `alloy-semantics` delta scenarios.

## 2. Go to Declaration on open paths

- [x] 2.1 Add `AlloyModuleReference` and return it from `AlloyQualifiedNameMixin` for the first name of an `AlloyOpenDecl` (design §5). It resolves through `AlloyModelService.openedModule`, its range is the whole path, and rename leaves the text unchanged. Aliases and `module` names stay non-references. Verify with new `AlloyReferenceTest` cases:
  - `open a` resolves to `a.als`;
  - `open util/ordering[S]` resolves to `models/util/ordering.als`;
  - an unknown path resolves to nothing;
  - `as ord` has no reference.

  Existing reference, find usages and rename tests still pass.
- [x] 2.2 Update the README "semantic features" checklist: Cmd/Ctrl-clicking `util/ordering` in `open util/ordering[A]` opens the bundled module read-only. Verify by re-reading the checklist against the delta scenarios.

## 3. Visible names

- [x] 3.1 Extend `AlloyScopes` with `visible(position, qualifier)` (design §2). It returns each visible declaration with its source module, using the same layers as `resolve`. An inner layer shadows the same name in outer ones. `private` declarations of opened modules are excluded. A qualifier restricts the result to matching opens, or to the current file for `this`. `resolve` keeps its behavior and shares the per-layer helpers. Verify with a new `AlloyScopesTest`:
  - binders appear inside their body and not in their bound;
  - parameters appear in their paragraph;
  - fields of the enclosing signature appear;
  - top-level declarations appear;
  - opened project and `util/ordering` declarations appear;
  - `private` ones of other modules do not;
  - `ord/` yields only `util/ordering`'s declarations;
  - an unknown qualifier yields nothing.

  `AlloyReferenceTest` still passes unchanged.

## 4. Name completion

- [x] 4.1 Add `AlloyCompletionContributor` with the context detection of design §1. In reference positions it offers `AlloyScopes.visible` items, and it filters by PSI class for `run` targets (predicates and functions), `check` targets (assertions) and signature parents after `extends`/`in`. Register it in `plugin.xml`, and stop `AlloyKeywordCompletionContributor` in module paths and after a qualifier. Verify with a new `AlloyCompletionTest` covering every scenario of the `alloy-completion` requirements:
  - names in scope;
  - qualified names;
  - completion filtered by context;
  - the model-with-errors case;
  - no names in comments or strings.

  `AlloyKeywordCompletionTest` still passes.
- [x] 4.2 Build lookup elements as in design §3: the icon from `AlloyPresentation.icon`, the kind as type text, the module name as tail text for declarations from other files, and priority by layer. Completing after `ord/f` inserts only the identifier. Verify with `AlloyCompletionTest` cases that render the items through `LookupElementPresentation`:
  - `first` from `util/ordering` has the function icon, type text "function" and tail text `util/ordering`;
  - a local signature has no tail text;
  - a binder is ranked before a top-level declaration of the same prefix;
  - `ord/fi` completes to `ord/first`.
- [x] 4.3 Add README "semantic features" checklist items: completion in a fact body shows icons, kinds and module tails, and completion after `run ` offers predicates and functions only. Verify by re-reading the checklist against the `alloy-completion` scenarios.

## 5. Module path completion

- [x] 5.1 Add the pure reachability helpers next to `openedPath` in `AlloyModelService`'s companion (design §7): the opener's root directory, and the path-derived module name of a file under it, or null. Verify with `AlloyModelServiceTest` cases on plain paths:
  - `/m/main.als` with module `main` has root `/m`;
  - with module `app/main` it is also `/m`;
  - `/m/sub/y.als` is `sub/y`;
  - a file outside the root, or with a non-identifier segment, gives null;
  - for each case, `openedPath` of the derived name leads back to the file.
- [x] 5.2 Offer module paths in `open` declarations (design §4):
  - reachable project files by their declared or path-derived name;
  - unreachable declared names greyed out, with tail text "cannot be opened from this file" and ranked last;
  - the bundled `util/*` modules, minus those shadowed by a reachable project file;
  - never the current file.

  Verify with `AlloyCompletionTest` cases for every scenario of "Completion of module paths" and "Unreachable modules shown as such", using `myFixture.addFileToProject` under `m/`. The unreachable item's presentation is greyed out and carries the hint, and a unique prefix never auto-inserts it.
- [x] 5.3 Add README "semantic features" checklist items: `open ` offers `a` and the `util/*` modules, and a file declaring a mismatched module name appears greyed out with the hint. Verify by re-reading the checklist against the scenarios.

## 6. Integration

- [x] 6.1 Update the plugin description in `plugin.xml` and add a `CHANGELOG.md` entry: completion of names and module paths, Go to Declaration on `open` paths, and the bundled library shown as a library in Reader mode. Verify that `./gradlew verifyPluginProjectConfiguration` (or `buildPlugin`) succeeds.
- [x] 6.2 Run the full test suite (`./gradlew test`), including the Practical Alloy example tests, and verify that it passes. Check that `openspec validate add-completion --strict` passes.
