# Design

## Context

What the plugin already has:

- **Name resolution.** `AlloyScopes` (`semantics/AlloyReference.kt`) resolves a name by walking the PSI, so it works on broken text. It looks at binders and parameters, then the fields of the enclosing signature, then `declarations(file)`, then opened modules by alias, path or unqualified. Opened modules come from `AlloyModelService.openedModule`.
- **Finding modules.** `openedModule` finds a project file with `openedPath(path, moduleName, opened)`, a copy of `CompUtil.computeModulePath`. Otherwise it falls back to `AlloyPositions.libraryFile`, a `jar://…/org.alloytools.alloy.core-6.2.0.jar!/models/util/*.als` entry.
  - This order is the analyzer's. In 6.2.0, `CompUtil.parseRecursively` tries the computed path, then `.md`, and only then the bundled model. The quickguide's "bundled first" is Alloy 4 behavior.
- **References.** `AlloyReference.getVariants()` is empty. `AlloyQualifiedNameMixin` gives no reference to the names of `open` and `module` declarations.
- **Completion.** `AlloyKeywordCompletionContributor` offers keywords everywhere outside comments and strings.
- **Presentation.** `AlloyPresentation` (`editor/AlloyStructure.kt`) already maps each declaration kind to an icon.
- **Library files.** The bundled library files are outside every content and library root, so the stub index skips them and the platform does not treat them as library sources. Reader mode (`ReaderModeSettings.matchMode`) does not apply to them.
- **Completion text.** At completion time the text holds the dummy identifier, so the model never type-checks. Completion has to be by name.

## Goals / Non-Goals

**Goals:**
- One source of truth for "what is visible here", shared by resolution and completion.
- Module reachability computed by the same function that resolution uses (`openedPath`), so completion never offers a path that Go to Declaration would not follow.
- Library files behave like library sources of other languages, without copying them out of the JAR.

**Non-Goals:**
- No type-aware completion (for example, only the fields of `x`'s signature after `x.`).
- No change to how an `open` path resolves. The analyzer's order stays.
- Renaming or moving a project file does not update the `open` paths that name it.
- No completion of the alias after `as`, or of the arguments in `[...]` of an `open`. The arguments get name completion like any other reference position, if they parse as references.

## Decisions

### 1. Completion through one `CompletionContributor`, not `getVariants`

A new `AlloyCompletionContributor` decides the context from the PSI around the caret:

| context | position |
|---|---|
| module path | first `QUALIFIED_NAME` of an `AlloyOpenDecl` |
| qualified | an identifier preceded by `x/` |
| command target | a command's target name |
| signature parent | a `QUALIFIED_NAME` after `extends`/`in` in an `AlloySigDecl` |
| names in scope | any other reference position |

It adds the items for that context. `AlloyReference.getVariants()` stays empty, so the platform does not add the same items twice.

`AlloyKeywordCompletionContributor` stops offering keywords in module paths and after a qualifier. In every other position it keeps its current behavior.

*Alternative:* `getVariants` on `AlloyReference`. It cannot reach `open` paths, which are not references in the mixin today, and it gives less control over the context. It was rejected.

### 2. `AlloyScopes.visible(position)` alongside `resolve`

`AlloyScopes` gets an enumeration with the same layers as `resolve`. It returns each visible declaration together with the module it comes from (null for the current one):
- **Shadowing.** A name found in an inner layer hides the same name in outer layers, as in resolution. Within one layer, every declaration is kept, for example overloaded fields of different signatures or names from two opened modules.
- **Opened modules.** Their `private` declarations are excluded, as in `CompModule.getRawNQS`.
- **Qualifiers.** A qualifier restricts the result to the matching opens. `this` restricts it to `declarations(file)`.
- **Command targets and signature parents.** These filter the result by PSI class.

`resolve` keeps its own early-return walk. Rewriting it on top of `visible` would be cleaner, but it would put the existing resolution tests at risk for no user-visible gain. Both share `locals`, `enclosingFields`, `declarations` and the open matching, which become functions over every name.

### 3. Lookup elements

- **Lookup string.** The declaration name. Insertion replaces only the identifier after any qualifier.
- **Icon.** From `AlloyPresentation.icon`.
- **Type text.** The kind ("signature", "field", "predicate", …).
- **Tail text.** The module name of the declaration's file: its `module` declaration, or `util/ordering` for library files.
- **Priority.** Locals rank before signature fields, then top-level declarations, then opened-module declarations, through `PrioritizedLookupElement`.

### 4. Module path candidates

For an opener `F` with module name `N` (empty if undeclared), the candidates come from `FileTypeIndex.getFiles(AlloyFileType, GlobalSearchScope.projectScope(project))`, which searches from the project's content roots. For each candidate `G` other than `F`:

1. **`G` declares `module M`.** It is *reachable* when `openedPath(F.path, N, M) == G.path`. A reachable `G` is offered as `M`. An unreachable `G` is offered as `M` greyed out, with tail text "cannot be opened from this file", and ranked last.
2. **`G` has no `module` declaration.** Take the opener's root: `F`'s own directory, whatever the depth of `N`. Before it climbs directories, `openedPath` drops the leading segments that the two names share, so a name that starts with the directory part of `N` resolves against `F`'s directory. For example, `app/main` in `/m/main.als` opens `app/sub/y` from `/m/sub/y.als`. If `G` lies under the root, its path-derived name is the directory part of `N` followed by `G`'s relative path without `.als`: `sub/y` under `main`, and `app/sub/y` under `app/main`. It is offered when `openedPath(F.path, N, name) == G.path`, which also checks the edge cases where `openedPath` returns null. Otherwise it is not offered, since there is no declared name to show.

Candidates whose name is not a valid Alloy qualified name (one segment is not an identifier) are skipped.

The bundled modules are the children of the JAR's `models/util/` directory, offered as `util/<name>`. Any name already offered by a reachable project file is dropped, because the project file shadows the bundled one in the analyzer.

The declared module name is read from the candidate's PSI (`AlloyModuleDecl`). This parses every Alloy file in the project once per completion at most, and the platform caches the PSI. If profiling shows it matters, `module` names can go into the stub index later. That would not change the behavior.

*Alternative:* list only files whose `module` name matches. This would miss files without a declaration, which the analyzer opens fine. It was rejected.

### 5. Reference on `open` paths

`AlloyQualifiedNameMixin` returns an `AlloyModuleReference` for the first name of an `AlloyOpenDecl`:
- It resolves to the `AlloyFile` from `AlloyModelService.openedModule`, or to nothing.
- Its range covers the whole path.
- `handleElementRename` leaves the text unchanged (see Non-Goals).
- `getVariants` is empty, because Decision 1 handles completion.

### 6. Bundled modules as a `SyntheticLibrary`

An `AlloyLibraryRootsProvider : AdditionalLibraryRootsProvider` returns one `SyntheticLibrary`:
- **Root.** `models/` inside the analyzer JAR, the parent of `AlloyPositions.libraryFile` results.
- **Presentation.** It implements `ItemPresentation`, named "Alloy " + the analyzer's version (6.2.0), with the Alloy icon. `Version.getShortversion()` cannot provide the version: it reads only the manifest of Alloy's `dist` JAR (`Bundle-SymbolicName: org.alloytools.alloy.dist`), and the plugin bundles the `core` JAR, so it returns an empty string. Instead, the version is the core JAR's `Bundle-Version` (`6.2.0.202503101505`) without its last segment, which is the rule `Version` applies.
- **Watched roots.** `getRootsToWatch` returns nothing, because the JAR does not change while the IDE runs.

This has these effects:
- The files are indexed: stub index, `FileTypeIndex`. `AlloySymbolContributor` already honours the search scope, so library symbols appear only outside "Project files".
- `ProjectFileIndex.isInLibrary` holds, so Reader mode applies under the user's "library sources" setting.
- External Libraries shows the entry.

The VirtualFiles are the same `JarFileSystem` files that `openedModule` and `declarationAt` already return, so navigation targets and index entries agree.

The library is provided for every project, so that `getAdditionalProjectLibraries` stays cheap and needs no index lookup. Indexes must not be queried during indexing.

*Alternative:* a `ReaderModeMatcher` only. It is smaller, but the files would stay invisible and unindexed, and Reader mode would be forced regardless of the user's setting. It was rejected in exploration.

### 7. Module reachability helper

The "opener's root" and "path-derived name" rules from Decision 4 go into small pure functions next to `openedPath` in `AlloyModelService`'s companion, so that both can be unit-tested on plain strings, like `openedPath` is today:
- `openerRoot(path)` is the opener's directory.
- `pathDerivedName(opener, moduleName, path)` is the directory part of `moduleName` followed by the path relative to that root, without `.als`. It is null when the path is outside the root, when a segment is not an Alloy identifier, or when `openedPath` of the name does not lead back to the path.

## Risks / Trade-offs

- **Unreachable hint.** [The hint relies on the declared name matching the path rule. A project using unusual layouts may see many greyed-out entries.] → They are ranked last and never inserted on a unique prefix match, so they do not get in the way.
- **Name collisions in Go to Symbol.** [Library declarations are now in the stub index, so they also take part in name-based lookups that use the index with an "all" scope.] → `AlloyScopes` resolution does not use the index. Find Usages and rename already use declaration-specific scopes. `AlloySymbolContributor` honours the scope.
- **Library in every project.** [The "Alloy 6.2.0" entry also appears in projects without Alloy files.] → It is small and read-only. It can later be limited to projects that contain `.als` files, through a project-level flag, if users find it noisy.
- **Completion cost.** [Collecting module candidates touches every `.als` file in the project.] → This happens only in `open` paths, on files the platform already has PSI or stubs for. See Decision 4 for the stub-index fallback.
- **Library tests.** [Light test fixtures may not pick up `AdditionalLibraryRootsProvider` changes without re-indexing.] → The provider is static and registered in `plugin.xml`, so light fixtures index it at project setup.
