# Tasks

## 1. Sessions per file

- [x] 1.1 Replace `AlloyInstanceViewer.session` with sessions keyed by `VirtualFile`, each with its own status line (design §1). Add `session(file)`, `status(file)` and `addListener(file, parent, listener)`, and give `navigateToOrigin`, `isEnabled` and `requestSolution` a file. Keep `enumerating` and `enumeration` project-wide, notifying every file's listeners when an enumeration starts or ends. `clear()` empties every session and disposes their anchors. Update `ViewerTestSupport.open()` to return `viewer.session(file)!!`. Verify that the existing `AlloyInstanceViewerTest` and `AlloyNewSolutionTest` pass after updating their calls, and add cases to `AlloyInstanceViewerTest`:
  - opening `run show` in `graph.als` and then `run enqueue` in `queue.als` leaves both sessions, each with its own header;
  - opening a second result in `graph.als` replaces only `graph.als`'s session;
  - a listener of `queue.als` is not called when `graph.als` opens a result.
- [x] 1.2 Add `attach(file, disposable)`: when the last attached disposable of a file is disposed, its session is removed and its `RangeMarker` disposed (design §2). Verify with new `AlloyInstanceViewerTest` cases:
  - with two attached disposables, disposing one keeps the session and disposing both removes it;
  - a session opened with nothing attached survives until `clear()`.
- [x] 1.3 Make the "busy" and "no more solutions" statuses belong to the file that asked. Verify in `AlloyNewSolutionTest`:
  - with a New trace of `graph.als` running, `requestSolution(queue.als, Config)` returns false and sets only `queue.als`'s status to the busy message;
  - the trace found is shown in `graph.als`'s session.

## 2. Panel per file

- [x] 2.1 Give `AlloyInstancePanel` a `file` (design §6): it reads `session(file)` and `status(file)`, subscribes with `addListener(file, ...)`, compares its document listener with its own file, and passes the file to its header link and New solution actions. Remove `AlloyInstanceToolWindowFactory` and `TOOL_WINDOW_ID`. Update `ViewerTestSupport.panel()`/`panelFor()` to take the file. Verify that `AlloyInstanceTabsTest`, `AlloyTraceNavigationTest`, `AlloyProjectionTest`, `AlloyEvaluatorPanelTest` and `DarkGraphLayerTest` pass with the updated support. Add a case where two panels of one file show the same session, and projecting one leaves the other unprojected (spec "Two editors of one file").
- [x] 2.2 Check that the Evaluator panel evaluates in its own file's session. Verify with a new `AlloyEvaluatorPanelTest` case: the panels of `graph.als` and `queue.als` evaluate `#univ` in their own results (spec "Two files with a result").

## 3. Editor with preview

- [x] 3.1 Add `AlloyInstancePreview`, a disposable `FileEditor` whose component is `AlloyInstancePanel(project, file, this)`, and which calls `viewer.attach(file, this)` (design §3). Add `AlloyEditorWithPreview`, extending `TextEditorWithPreview` with `Layout.SHOW_EDITOR`, side by side, `isShowActionsInTabs() = true`, a shared splitter proportion key, and the `setState` override that forces `SHOW_EDITOR` when the file has no session. Add the bundle key `viewer.preview.name`. Verify with a new `AlloyEditorWithPreviewTest`:
  - a new editor's layout is `SHOW_EDITOR` and it is not a vertical split;
  - `setState` with a saved `SHOW_EDITOR_AND_PREVIEW` gives `SHOW_EDITOR` without a session, and keeps it with one;
  - disposing the editor detaches the preview, which removes the file's session.
- [x] 3.2 Add `AlloyFileEditorProvider` (`HIDE_DEFAULT_EDITOR`, `DumbAware`, editor type id `alloy-editor-with-preview`). It accepts Alloy files outside `AlloyPositions.libraryRoot()`. Replace the `toolWindow` extension in `plugin.xml` with `fileEditorProvider`. Verify with new `AlloyFileEditorProviderTest` cases:
  - a project `.als` file is accepted, a bundled `util/ordering.als` and a non-Alloy file are not;
  - `FileEditorManager.openFile` on a project `.als` file gives one `AlloyEditorWithPreview`, and on `util/ordering.als` a plain `TextEditor`.
  
  Also run the whole `./gradlew test` to check that editor-based tests (highlighting, inlays, completion, intentions) still pass with the wrapped editor.
- [x] 3.3 Give `open()` an optional `Editor`, and make it switch the matching `AlloyEditorWithPreview` (or the selected one) from `SHOW_EDITOR` to `SHOW_EDITOR_AND_PREVIEW`, leaving `SHOW_PREVIEW` alone (design §4). Pass the editor from `AlloyCommandResultClickHandler`. Verify with new cases in `AlloyEditorWithPreviewTest` and `AlloyCommandResultClickHandlerTest`:
  - clicking the instance hint in an editor showing the text only switches it to editor and preview;
  - in preview only, it stays preview only;
  - with two editors of the file, only the clicked one changes layout.
- [x] 3.4 Check header navigation from each layout (design §5). Verify with a test that `navigateToOrigin(file)` moves the caret of the text editor to the command. If the preview-only layout does not show the text after navigating, add the explicit `setLayout(SHOW_EDITOR_AND_PREVIEW)` and test it as well.

## 4. Specs, docs and the in-flight change

- [x] 4.1 Reword the Purpose sections of `openspec/specs/alloy-command-execution/spec.md` and `openspec/specs/alloy-evaluator/spec.md` from "the Alloy Instance tool window" to "the instance preview of the editor". Verify that `openspec validate --specs --strict` passes.
- [x] 4.2 Update `README.md` (the "Running commands", "Alloy Instance tool window" and "Evaluator" bullets, renaming the second to "Instance preview"), `CHANGELOG.md` (an *Unreleased* entry for the move, with the tool window's removal marked as breaking), and the plugin description in `plugin.xml`. Verify that `grep -n "Alloy Instance" README.md src/main/resources/META-INF/plugin.xml` finds nothing.
- [x] 4.3 Update `DEV.md`'s "Inline command results" and "Instance viewer" checklists. Replace the tool window items, using the preview instead of the tool window for resizing and narrowing. Add items for:
  - opening a file as editor only, and the switch to editor and preview on the first hint;
  - the layout buttons in the editor tab, and stacking;
  - two files with a result each;
  - two split editors of one file;
  - reopening a file after closing it, and after a restart;
  - `util/ordering.als` opening without a preview;
  - header navigation from preview only.
  
  Verify by re-reading the checklists against the delta specs' scenarios.
- [x] 4.4 Reword the artifacts of `openspec/changes/add-event-timeline` (proposal, design, tasks, and both delta specs) from "tool window" to "instance preview". Its delta's MODIFIED requirements copy text that this change rewords, so update them to match this change's wording. Verify that `openspec validate add-event-timeline --strict` passes, and that `grep -rn "tool window" openspec/changes/add-event-timeline` finds only references to other tool windows.

## 5. Integration

- [x] 5.1 Run `./gradlew test` and `openspec validate move-viewer-into-editor --strict`, and verify that both pass. Then tick the manual items of task 4.3 once the automated tests pass, and leave them in the `DEV.md` checklists for `runIde`.

## Workflow follow-up

- Archive this change before applying `add-event-timeline`, so that its deltas apply on top of the reworded requirements.
