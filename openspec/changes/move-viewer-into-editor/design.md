# Design

## Context

The viewer has three layers today (see the archived `in-process-alloy` design, section 10):

```
  AlloyCommandResultClickHandler --open(file, ref, result)--> AlloyInstanceViewer   (project service)
                                                                 session: ViewerSession?   (one)
                                                                 status, enumerating, listeners
                                                                 selectedTab / evaluatorVisible / proportion (workspace state)
                                                                        |
                                                                        v  listeners
                                       AlloyInstanceToolWindowFactory -> AlloyInstancePanel(project, parent)
                                                                           AlloyEvaluatorPanel(project, parent)
```

Only `AlloyInstanceViewer.open()` (it calls `ToolWindowManager...show()`), the factory and `plugin.xml` know about the tool window. `AlloyInstancePanel` is a `JPanel` whose resources hang off a `Disposable` parent. It reads `viewer.session` and `viewer.status` everywhere, and `AlloyEvaluatorPanel` reads `viewer.outdated(session)`. The evaluator's document already gets its model from a lambda (`ALLOY_EVALUATOR_FRAGMENT`), and its watches are already stored per model file (`AlloyEvaluatorState`). Neither needs a global session.

The platform target is 2026.2 (since-build 261). There, `TextEditorWithPreview` has public `setLayout`, `setVerticalSplit` and `getState`/`setState` (which save the layout and the orientation per file), plus `isShowActionsInTabs()` and `getSplitterProportionKey()`, which can be overridden.

## Goals / Non-Goals

**Goals:**
- Hold a session per file in the existing service, so that tests can keep driving `open()` without a real editor.
- Keep `AlloyInstancePanel` as the preview's component, with as few changes as possible to its layout code and its tests of the strip, the panes and the projection.

**Non-Goals:**
- Saving sessions across IDE restarts (an `A4Solution` cannot be serialized cheaply).
- Changing the panel's contents or layout to fit the smaller space. That is left for follow-ups once it has been used.
- A floating or detached viewer. An editor tab can already be moved to its own window.

## Decisions

### 1. Sessions keyed by file in `AlloyInstanceViewer`

`session: ViewerSession?` becomes `sessions: Map<VirtualFile, FileSession>`. A `FileSession` holds the `ViewerSession`, which is replaced on each open or new solution as today, and the status line text. The status belongs to the file whose button produced it. `enumerating` and `enumeration` stay project-wide, because `AlloyRunService.startEnumeration()` allows one analysis per project. Only the New buttons of the file being searched are disabled: another file's buttons stay enabled, and clicking one says in that file's status line that an analysis is already running (spec "Enumeration in another file"). `requestSolution(file, kind)` writes its result back to the file it was forked from, as `finished()` already does by session identity.

The API takes the file: `session(file)`, `status(file)`, `addListener(file, parent, listener)`, `header(session)` and `outdated(session)` as today, `navigateToOrigin(file)`, `isEnabled(file, kind)`, `requestSolution(file, kind)`. Listeners are kept per file, so an open in `queue.als` does not rebuild the panels of `graph.als`. An enumeration's start and end notify every listener, as they take and give back the project's analysis.

*Alternative:* one service instance per file, as a user data holder on the `VirtualFile`. That is harder to clear in light tests, which share a project, and it spreads the enumeration lock.

### 2. Session lifetime by counting previews

Each preview `FileEditor` registers itself with the viewer when it is created (`attach(file, disposable)`) and leaves when it is disposed. When the last preview of a file leaves, the viewer removes that file's session, and with it the `RangeMarker` anchor, which it disposes. This follows the editors' own lifetime, and splits, reopened tabs and project closing need no special cases. A session opened while the file has no preview (tests, or a future caller) stays until a preview attaches and later detaches, or until `clear()`.

*Alternative:* `FileEditorManagerListener.fileClosed` and a check of `isFileOpen`. It is fired per editor window and needs that extra check, and it does not see editors outside the manager.

### 3. `AlloyFileEditorProvider` builds a `TextEditorWithPreview`

The provider is registered as `fileEditorProvider` in `plugin.xml`, with `FileEditorPolicy.HIDE_DEFAULT_EDITOR` and `DumbAware`:
- `accept(project, file)`: an Alloy file that is not under `AlloyPositions.libraryRoot()` (the bundled `models` JAR entry). Bundled modules then get the platform's default text editor, as `HIDE_DEFAULT_EDITOR` only hides it for files we accept.
- `createEditor`: `AlloyEditorWithPreview(TextEditorProvider.getInstance().createEditor(project, file) as TextEditor, AlloyInstancePreview(project, file))`.

`AlloyInstancePreview` is a small `UserDataHolderBase(), FileEditor` whose component is an `AlloyInstancePanel(project, file, this)`. It is disposable, so the panel's listeners, strip, dark theme and evaluator end with it. It attaches to the viewer (decision 2).

`AlloyEditorWithPreview` extends `TextEditorWithPreview(editor, preview, name, Layout.SHOW_EDITOR, false)`:
- `isShowActionsInTabs()` returns `true`, so the layout buttons are in the editor tab and do not float over the preview's header and toolbar.
- `setState(state)`: when the file has no session, it applies the saved state and then forces `Layout.SHOW_EDITOR` (spec "Reopened editor without a result"). This also covers a restart, because sessions are not saved.
- `getSplitterProportionKey()` is shared by all Alloy files, so the divider is the same everywhere.

The editor type id stays `"alloy-editor-with-preview"` from the start, since `getEditorTypeId()` keys the saved state.

### 4. `open()` picks the editor of the hint

`open(file, command, result, editor: Editor? = null)` builds the session as today, then finds the `AlloyEditorWithPreview` that wraps `editor` among `FileEditorManager.getEditors(file)`, or the selected one if `editor` is null. If its layout is `SHOW_EDITOR`, it is switched to `SHOW_EDITOR_AND_PREVIEW`. `SHOW_PREVIEW` and the orientation are left alone. `AlloyCommandResultClickHandler` passes its `Editor`. With no such editor (tests, or the file opened elsewhere), only the session changes.

### 5. Header navigation in the paired editor

`navigateToOrigin(file)` keeps using `OpenFileDescriptor(...).navigate(true)`. `TextEditorWithPreview.navigateTo` moves the caret in its text editor, and the platform switches a `SHOW_PREVIEW` layout to show the text when it navigates. If manual testing shows that the preview-only layout does not switch, the preview calls `setLayout(SHOW_EDITOR_AND_PREVIEW)` on its owner before navigating.

### 6. Panel per file

`AlloyInstancePanel(project, file, parent)` reads `viewer.session(file)` and `viewer.status(file)`, and subscribes with `addListener(file, ...)`. Its document listener compares with its own `file`. The project-wide preferences (`selectedTab`, `evaluatorVisible`, `evaluatorProportion`) stay on the service. The tab and the chosen atoms are per panel as today. The projected signatures were kept on the session's shared `AlloySolutionModel` (its `VizState`), so they move to the panel too: each panel's `AlloyProjectionChooser` holds its own list, starting from the model's `initialProjections` (the magic layout's, or the theme's it was copied from), and the panel applies it to the shared `VizState` with `AlloySolutionModel.project` before it draws, and before it asks for a new solution, whose theme is copied with it. A new solution of the same command keeps each panel's own projection. So two editors of one file can differ (spec "Two editors of one file"). The selected state stays shared. `AlloyEvaluatorPanel` is unchanged apart from taking its sessions from the panel.

### 7. Wording

No string in `AlloyBundle` names the tool window (`viewer.empty` says "appear here"), so the bundle is unchanged apart from the preview's editor name, `viewer.preview.name` ("Instance"). `TOOL_WINDOW_ID` is removed. The Purpose sections of the main `alloy-command-execution` and `alloy-evaluator` specs name the tool window. Deltas cannot change them, so they are edited in place.

## Risks / Trade-offs

- **[The preview is smaller than the tool window]** → Side by side is the default and the divider is remembered. The toolbar line already wraps the strip under the toolbar when it is narrow. Graphs scroll. Any further compaction is a follow-up.
- **[`HIDE_DEFAULT_EDITOR` for every project `.als` file changes how they open]** → The text editor inside is the platform's own `TextEditor`, so highlighting, inlays, the gutter and the structure view come from it. `TextEditorWithPreview` delegates `getStructureViewBuilder`, `getBackgroundHighlighter` and `navigateTo`. Plugin tests that use `myFixture.editor` still get that text editor's `Editor`.
- **[A light test's fixture editor is not created through the provider]** → The provider is tested on its own (`accept`, `createEditor`, the layout policy) with `FileEditorManager.openFile` in a heavier fixture, and the viewer's session tests keep calling `open()` without an editor.
- **[Several split editors of a big trace build one graph each]** → Graphs are built on the EDT per panel, as they were per tool window. This is only paid when two editors of one file both show the preview.
- **[A session left without previews when it was opened before any editor existed]** → It is dropped on `clear()` and when the project closes. The click handler always has an editor, so this only happens in tests.
- **[The in-flight `add-event-timeline` change targets the tool window]** → Its artifacts are reworded in this change. Its "toolbar" is the panel's own toolbar, which stays.

## Migration Plan

Users get no migration. The tool window disappears, and its saved layout is ignored by the platform. The `AlloyInstanceViewer` workspace state keeps its fields, so the selected tab and the Evaluator preferences carry over. Rollback means reverting the change, which re-registers the tool window.
