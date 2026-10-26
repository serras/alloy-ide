# Proposal

## Why

Instances, counterexamples and traces are shown in the *Alloy Instance* tool window, away from the model they come from. That window holds one result for the whole project, so working on two models means that each click on a hint replaces the other model's result. IntelliJ already has a familiar place for "a file and a rendering of it": the split editor with a preview, as Markdown uses. The result of a command belongs next to the file that holds the command.

## What Changes

- **Instance preview in the editor.** Every `.als` file of the project opens in an editor with a preview. The preview holds what the tool window held: the header, the toolbar with the trace path strip, the status line, the Visualization, Text and Table tabs, and the Evaluator panel.
- **BREAKING: the Alloy Instance tool window is removed.** It is not registered any more, and nothing opens or brings it to front.
- **One result per file.** Each `.als` file has its own shown result. Opening a result of a command replaces only the result of that command's file. Other files keep theirs, and two files open side by side can show a result each. When a file is open in several editors, all of them show its result, each with its own projection and selected tab.
- **Layout.** A file opens as editor only. Opening a result switches the editor it was opened from to editor and preview, side by side. The user can switch between editor only, editor and preview, and preview only from the editor tab, and can stack the editor and the preview instead. The platform remembers these choices per file. A file whose editor reopens without a result shows editor only.
- **Result lifetime.** A file's result is dropped when its last editor closes. Its hint keeps the result, so a click shows it again.
- **Bundled library modules** (`util/*`, which have no commands to run) open in a plain text editor, without a preview.
- **Header navigation** moves the caret to the command in the editor next to the preview.
- The panel's preferences stay project-wide: the selected tab, whether the Evaluator is shown and its height. Asking for new solutions still runs one analysis at a time per project.
- `add-event-timeline` is still in progress and its delta spec says "tool window". Its wording is updated to the instance preview as part of this change, so that it is applied on top of this change.

## Capabilities

### New Capabilities
<!-- none -->

### Modified Capabilities
- `alloy-instance-viewer`: the "Instance tool window" requirement is replaced by requirements for the instance preview in the editor, its layout, the result of each file and its lifetime, and library modules. "Each result replaces the previous one" applies per file. The header navigates in the editor next to it. The requirements that name the tool window (outdated result, tabs, new solutions, enumeration, projection, desktop preferences, trace panes, the strip and its placement, scrolling and background, responsiveness) name the instance preview instead, with no other change in behavior.
- `alloy-command-execution`: clicking a result hint shows the result in the instance preview of the hint's editor, instead of the tool window.
- `alloy-evaluator`: the Evaluator panel is part of the instance preview instead of the tool window.

## Impact

- **Code** in `src/main/kotlin/com/serranofp/alloy/viewer/`:
  - `AlloyInstanceViewer` keeps one session per file instead of one per project. Its listeners, header, outdated check, navigation and enumeration take the file. `open()` switches the hint's editor to the split instead of showing the tool window.
  - `AlloyInstancePanel` shows one file's session. `AlloyInstanceToolWindowFactory` is removed.
  - New: a `FileEditorProvider` for Alloy files, which builds a `TextEditorWithPreview` from the platform's text editor and a preview `FileEditor` wrapping the panel.
  - `AlloyEvaluatorPanel` and the evaluator completion follow the session of their file.
- **plugin.xml**: the `toolWindow` extension is replaced by a `fileEditorProvider`. The plugin description says "instance preview".
- **Docs**: README, CHANGELOG, and the instance viewer and command checklists in `DEV.md`.
- **Tests**: the viewer tests that use the project's single panel (`AlloyInstanceViewerTest`, `AlloyInstanceTabsTest`, `AlloyNewSolutionTest`, `AlloyTraceNavigationTest`, `AlloyEvaluatorPanelTest`, `AlloyCommandResultClickHandlerTest`, and `ViewerTestSupport`) work per file. New tests cover the provider, the layout switch, two files with a result each, and the session dropped when its last editor closes.
- **Other changes**: `add-event-timeline`'s delta spec, proposal and design are reworded from "tool window" to "instance preview".
- **Dependencies**: none. `TextEditorWithPreview` and `FileEditorProvider` are part of the platform.
