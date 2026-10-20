# Design

## Context

See proposal.md for motivation. The current runtime:

```
IDE  --LSP-->  server JVM (server-shim + alloy.lsp, lsp4j 0.4.1)  --WorkerEngine-->  worker JVM
                 semantics, ListAlloyCommands/Execute/Stop/OpenModel     analysis, VizGUI JFrame
```

The plugin side of execution (`com.serranofp.alloy.run`) is built around that protocol:
- `AlloyServerConnection` sends requests.
- `AlloyRunService` keeps one execution per project, because server output carries no run id.
- `AlloyRunLog` parses the text messages back into an outcome, a tooltip log and `XML:`/`CORE:` links.
- `AlloyCommandResults` stores per-command `CommandResult`s keyed by document stamp and settings generation, which drive hints, tooltips and outdated marking.

Results, hints, tooltips, the outdated logic and the settings page do not depend on the server and are kept.

Constraints from the Alloy 6.2.0 JARs, checked in their sources:
- `CompUtil.parseEverything_fromString/_fromFile` throws on the first error. It returns a `CompModule`.
- Semantics are available through four calls on a `CompModule`, and the old LSP was built on them:
  - `module.find(Pos)` returns the expression at a position.
  - `Expr.referenced()` returns the declaration it refers to, with its position.
  - `Clause.explain()` returns hover text.
  - `module.visitExpressionsResilient` visits every expression, which is how references are found.
- `TranslateAlloyToKodkod.execute_command(A4Reporter, sigs, cmd, A4Options)` runs on the calling thread. Its `A4Reporter` callbacks (`parse`, `typecheck`, `translate`, `solve`, `resultSAT/UNSAT`, `minimizing`, `warning` and others) arrive between phases.
- `A4Solution.fork(p)` enumerates further solutions:

  | `p` | Meaning |
  |---|---|
  | -3 | Next solution (`next()`) |
  | -1 | New config |
  | -2 | New trace |
  | 0 | New init |
  | i + 1 | New fork after state i |

  These are the codes `VizGUI` passes to its enumerator. `getTraceLength()` and `getLoopState()` describe traces. `toString(state)` and `format(state)` are exactly what `VizGUI` shows in its Text and Table modes.
- `alloy4viz`:
  - `StaticInstanceReader.parseInstance(Reader, state)` builds an `AlloyInstance` from the solution XML, written with `A4Solution.writeXML(PrintWriter, macros, sourceFiles)`.
  - `new VizState(instance)` holds the theme.
  - `VizState.project/deproject/canProject` handle projection.
  - `new VizGraphPanel(JFrame, List<VizState>, false)` draws the graph.
  - `MagicColor.magic(VizState)` is public. `MagicLayout` is a package-private `final class`.
  - Only `VizGUI` touches `java.util.prefs` (its visualization mode). `alloy4graph`, `VizState` and `VizGraphPanel` don't.
- In the core, `A4Preferences` is only read for enum names, plus a few GUI-only places such as `OurSyntaxWidget`. The command-line path that `execute_command` uses takes all options from `A4Options`.
- Kodkod's SAT4J wrapper throws `TimeoutException`/`AbortedException` when its `ISolver` is told to stop. `ExternalSolver` aborts when its process is destroyed. Java 25 has no `Thread.stop`.

## Goals / Non-Goals

**Goals:**
- A single runtime: the Alloy JARs on the plugin classloader, used from IDE threads.
- Native, PSI-backed semantics that are exact when the model type-checks and name-based otherwise.
- Analyses that can always be stopped and never block the UI thread.
- An in-IDE viewer that reuses `alloy4viz` drawing, not its window.

**Non-Goals:**
- The Alloy evaluator, theme editing or customization panels, the Tree view, the Dot/XML views, or multiple side-by-side state panes (`VizGUI`'s `panes > 1`).
- Saving or loading `.thm` theme files.
- A full Alloy expression grammar. PSI only goes as deep as declarations and references need.
- Completion of user-defined names. This could build on the new references later, but it is not part of this change.

## Decisions

### 1. Dependencies on the plugin classloader

`libs.versions.toml` gets `alloy-core` and `alloy-application` (6.2.0) as `implementation`. They bring `pardinus.core`, `api` and `aQute.libg` with them. The `alloyServer` configuration, the `PrepareSandboxTask` copy, `include("server-shim")` and `server-shim/` are deleted. `plugin.xml` depends on `com.intellij.modules.platform`.

*Alternatives:*
- Keep a worker JVM through `WorkerEngine.run`. It is more robust against OOM, but the user asked for in-process, and it would keep `A4Solution` out of reach for enumeration.
- Shade the JARs. Nothing currently conflicts with the platform: no lsp4j is left, and SAT4J is only in pardinus. Shading can be added later if a conflict appears.

### 2. Model service: one parse per document version

A project service `AlloyModelService` gives each `.als` file a `CachedValue` keyed on its PSI modification count and the counts of the modules it opens. The value is an `AlloyModelResult`:
- either a `CompModule` with the warnings for that text, or
- the `Err`s that stopped parsing.

It is computed with `CompUtil.parseEverything_fromFile`. That call gets a content map of every open document's current text, built under a read action, so unsaved edits and opened project modules are honoured. Library modules (`util/*`) resolve from the JAR, because `CompUtil` already looks them up through `Util.jarPrefix() + "models/"`.

Callers then get the result in one of two ways:
- **Annotator:** runs in `ExternalAnnotator.doAnnotate`, which is off the UI thread, debounced and cancelled for outdated text.
- **References:** call the service inside `ReadAction.nonBlocking`, or use whatever value is already cached, falling back to name-based resolution if the model is not ready yet.

All `Throwable`s from the analyzer except `ProcessCanceledException` are caught and turned into an "unavailable" result. That satisfies "Semantic features never block the editor".

*Alternatives:*
- Re-parse in every feature, as the LSP did. That is slow on large models.
- Keep the last good AST and remap its offsets. That is fragile, and it was rejected in exploration.

### 3. Positions: `Pos` ↔ offsets

`Pos` is 1-based line/column with tabs counted as one column, in the file named by `pos.filename`. An `AlloyPositions` utility converts `Pos` to `TextRange` through the `Document`'s line starts. It maps `pos.filename` back to the `VirtualFile`: either a project file, or a `jar://…!/models/util/x.als` file found through `JarFileSystem`, which is read-only by construction. Unit tests cover multi-line spans, tabs and library files.

### 4. Deeper PSI

`Alloy.bnf` keeps top-level paragraphs as they are today. Inside bodies, it adds:
- `field_decl` (`disj? name (, name)* : …` inside sig braces);
- `param_decl` (`pred/fun [ … ]`);
- `binder` for quantifiers (`all/some/no/one/lone/sum`, with `disj`), `let`, and comprehensions `{ x: e | … }`;
- `enum_member`;
- `ref`, any identifier leaf not in a declaration position.

The rest of an expression stays a flat token sequence with balanced brackets, so error recovery is the same as today: an unbalanced bracket fails only the paragraph. Each binder owns the scope it introduces: the quantifier body, or the rest of the `let`.

Declarations implement `PsiNameIdentifierOwner`. `ref` elements carry a `PsiPolyVariantReference`. A stub index (`AlloyNameIndex`) holds top-level declarations and fields for Go to Symbol and cross-file name resolution.

*Alternative:* custom leaf types with no grammar change. That fails because binders and scopes need composite nodes.

### 5. Reference resolution: exact first, then by name

`AlloyReference.multiResolve` works in two steps:
1. If the file's `AlloyModelResult` is a fresh `CompModule`, convert the reference's offset to a `Pos`, call `module.find(pos)?.referenced()?.pos()`, and map that position to the PSI declaration whose name identifier starts there. This gives a single result.
2. Otherwise, look the name up by scope, innermost first:
   1. enclosing binders and parameters (PSI walk up);
   2. fields of the enclosing sig, including in sig facts;
   3. top-level declarations of this file;
   4. `open`ed modules, matching `alias/name` or an unqualified name, through the stub index and library files;
   5. built-ins (`univ`, `Int`, `none`, `iden`, `seq/Int`), which resolve to nothing and are not errors.

   Every match at the first level that has any matches is returned. More than one gives IntelliJ's chooser.

The result is cached with `ResolveCache`.

Find Usages uses the standard `ReferencesSearch` over Alloy files, scoped by name through the word index. Each hit is classified with a custom `UsageType`:
- **Exact:** resolved through step 1.
- **Unconfirmed:** found by name in a file without a fresh model. These are the spec's "unconfirmed usages" group.

Highlight usages in a file falls out of the references.

### 6. Rename

`RenamePsiElementProcessor` for Alloy declarations does two checks:
- **Refuses the rename** if any file containing a usage, or the declaring file, lacks a fresh `CompModule`. It throws `ConflictsInView`/`IncorrectOperationException` with "Rename needs a model without errors".
- **Rejects library targets**, because their files are not writable.

`handleElementRename` replaces the identifier leaf. Local binders use in-place rename through `PsiNamedElement` with a `LocalSearchScope` (`getUseScope`).

### 7. Documentation, structure, symbols

- **Documentation:** `DocumentationTargetProvider` or `PsiDocumentationTargetProvider`. With a fresh model, it shows `Clause.explain()`, plus the `/** */` comment found on the PSI declaration. Otherwise it shows the declaration's PSI text up to its body, plus the doc comment.
- **Structure:** `PsiStructureViewFactory` over the PSI, with fields nested under sigs. `BreadcrumbsProvider` uses the same element kinds.
- **Go to Symbol:** `ChooseByNameContributorEx` over `AlloyNameIndex`.

### 8. Analysis engine: one managed thread per execution

`AlloyAnalysisEngine`, an application service, owns a dedicated thread per running analysis. It is created with a large stack, `new Thread(group, runnable, name, 64 MB)`, because translation recurses deeply. The desktop app gives its worker a big `-Xss` for the same reason. The thread is not a pooled executor.

An `AlloyAnalysis` handle exposes:
- `start(task)`;
- `stop()`;
- `state`, one of Running, Stopping, Done;
- a `Flow` or listener of typed events (`Step`, `Warning`, `Sat(A4Solution)`, `Unsat(core)`, `Failed(cause)`, `Stopped`).

These events replace `AlloyExecutionMessage` and `AlloyRunLog`'s text parsing. The tooltip lines are produced from the events, using the same wording `SimpleReporter` uses, so the existing tooltip scenarios still hold.

Stop works in three layers:
1. **Reporter checkpoints.** A `StoppableReporter extends A4Reporter` checks a flag in every callback and throws `AlloyStopped`. This covers parse, typecheck, scope, the steps between translation phases, and `minimizing`.
2. **Solver abort.** `A4Options.solver` is the user's choice. Kodkod builds the solver itself, so the engine interrupts the analysis thread and also signals the live solver:
   - **SAT4J:** a wrapping `SATFactory` returns the Kodkod SAT4J wrapper and keeps its `ISolver` so that `expireTimeout()` can be called on it.
   - **External solvers:** the solver process is destroyed.

   If wrapping the factory is not viable for a solver, the fallback is the interrupt plus the reporter flag.
3. **Stopping state.** If neither layer can act immediately (inside a long FOL→boolean translation), the execution shows "Stopping…". It becomes "Stopped before completion" once the thread reaches the next checkpoint. The thread is never abandoned while still holding solver memory.

`OutOfMemoryError` and `StackOverflowError` on the analysis thread are caught at its root. They become `Failed(MemoryExhausted|StackExhausted)` and the solution is dropped. `ProcessCanceledException` is never thrown into Alloy code.

`AlloyRunService` keeps its single-active-execution rule for the busy message and the shared spinner state. Solution enumeration (Decision 10) goes through the same engine and the same rule.

*Alternatives:*
- `WorkerEngine.runLocally`. It has no stop for local runs.
- A pooled executor thread. Its stack is too small for deep translations.

### 9. Command list, unsat core, settings

- **Command lists** come from `CompModule.getAllCommands()`, with each `Command.pos` mapped to a PSI command element. A gutter command with no matching analyzer command is "Command not found", as before.
- **Unsat cores** come from `A4Solution.highLevelCore()`. They are highlighted as warnings through the same mechanism as today: a per-file highlighting pass from the stored result instead of server-published diagnostics.
- **Settings:**
  - `AlloySettings` builds an `A4Options` for each run, including the solver fallback warning.
  - `AlloySolvers` calls `SATFactory.getAllSolvers().filter(isPresent)` in-process, cached for the IDE session.
  - `AlloySettingsApplier` only bumps the settings generation. That marks results outdated and changes nothing else.

  `java.util.prefs` is never touched by the code paths used: no `VizGUI` and no `SimpleGUI`. A test asserts that the desktop node is unchanged after a run and a viewer open, as the shim's test did.

### 10. Instance viewer tool window

`AlloyInstanceToolWindowFactory` registers the "Alloy Instance" tool window, anchored at the bottom. `AlloyInstanceViewer` is a project service that holds at most one `ViewerSession`:
- `origin`: the file, command text, line, and instance or counterexample;
- the `A4Solution`;
- the result stamp, for the outdated marking;
- the current state index;
- the current `VizState`.

Opening a result replaces the session, and the content is rebuilt.

```
+------------------------------------------------------------------+
| graph.als : check noCycles for 3 (line 12) - Counterexample  [o] |  <- header, click = navigate
+------------------------------------------------------------------+
| [New config][New trace][New init][New fork] | |< < State 1 of 3 (loop 1) > | Projection: [none v] |
+------------------------------------------------------------------+
| [Visualization] [Text] [Table]                                   |
|                                                                  |
|   VizGraphPanel / JBTextArea(toString(i)) / JBTextArea(format(i))|
+------------------------------------------------------------------+
```

- **Instance:** the solution is written as XML to a `StringWriter` once per solution, with source files taken from the analyzed content map. `StaticInstanceReader.parseInstance(StringReader, state)` is called for each state that is shown, and the results are cached per state.
- **Magic layout:** a new session runs `MagicLayout.magic` and `MagicColor.magic` on its `VizState`. `MagicLayout` is package-private, so a two-line bridge class `edu.mit.csail.sdg.alloy4viz.AlloyIdeMagic` lives in the plugin. Split packages are allowed on the plugin classloader, which is not modular. When the state changes, the `VizState` is kept and only the instance is reloaded (`loadInstance`), so the theme and projection persist.
- **Graph:** `new VizGraphPanel(ideFrame, listOf(vizState), false)` is placed inside a `JBScrollPane`. Its own projection atom combos at the bottom are kept: they are how the user picks the atom of each projected type, as the spec requires. The toolbar's projection dropdown lists `canProject` types and calls `project`/`deproject`, then `remakeAll`.
- **Text and Table:** read-only `EditorTextField` or `JBTextArea` in a monospaced font with `toString(state)` and `format(state)`.
- **Toolbar:** an `ActionToolbar` with these actions:

  | Action | Calls | Enabled when |
  |---|---|---|
  | New config | `fork(-3)` for static models, `fork(-1)` for temporal ones | always (the only one for static models) |
  | New trace | `fork(-2)` | the model has mutable relations, i.e. the solution XML has variable sigs or fields |
  | New init | `fork(0)` | same as New trace |
  | New fork | `fork(state+1)` | same as New trace |

  `fork` returns this solution unchanged (UNSAT or no `eval`) when nothing new exists. The viewer detects that and shows "No more solutions of that kind" in the tool window's status line. Every enumeration runs as an `AlloyAnalysis` on the engine, with a background progress indicator. While the session is outdated, all four buttons are disabled.
- **Last tab:** the selected tab is remembered in a project-level `@State`.

*Alternatives:*
- Embed `VizGUI` with `makeWindow=false`. It touches `Preferences`, and it brings its own toolbar, menus and dialogs, including `System.exit` paths.
- Draw graphs with `alloy4graph` directly. That duplicates `VizGraphPanel`.

### 11. Gutter Stop menu

`AlloyRunLineMarkerContributor` already returns the spinner while a command runs. Its action becomes an `ActionGroup` popup with a single **Stop** action, which calls `execution.stop()`. Clicking the running hint keeps stopping directly. The new outcome text constant is `"Stopped before completion"`. A "Stopping…" variant is used while in the Stopping state.

## Risks / Trade-offs

- **[A runaway analysis exhausts IDE memory.]** → Catch `OutOfMemoryError` on the analysis thread and drop the solution right away. Keep only one analysis per project. Document in the README that very large scopes are best run in the desktop app. A real OOM can still destabilize the IDE, and that is accepted as the price of running in-process.
- **[Stop cannot interrupt a long translation step.]** → Show the "Stopping…" state. A spike (task 3.1) measures how long the gap is on a large model. If it is too long, add flag checks through a Kodkod `Reporter` (pardinus calls its `translatingToBoolean`/`generatingSAT` hooks at a finer grain than `A4Reporter`).
- **[Native solvers loaded through JNI crash the IDE.]** → No change from today's settings scope. Only SAT4J and external-executable solvers are listed, as `AlloySolvers` already restricts. JNI solvers stay excluded.
- **[`VizGraphPanel`/`alloy4graph` assume a `JFrame` parent and the AWT thread.]** → All viewer UI is built on the EDT, and the IDE frame is passed (`WindowManager.getFrame(project)`). A spike task checks that the panel renders, scrolls and zooms inside a tool window.
- **[Static state in the Alloy libraries is not thread-safe.]** Examples are `A4Solution` caches, `CompUtil` and `Util` statics. → Parsing for semantics can run concurrently with an analysis. Each uses its own `CompModule`, and `CompUtil`/the translator keep no mutable globals that are shared across modules (verify in the spike). Analyses are already serialized per project. Across projects they are serialized globally by the engine, as the old single server per project effectively was for each worker.
- **[The deeper grammar regresses parser error recovery.]** → Body rules are wrapped with `recoverWhile`/`pin` so that a broken binder fails only its own sub-tree. All existing parser, folding and brace tests must still pass unchanged.
- **[Name-based resolution gives wrong targets for overloaded fields.]** → It returns all candidates instead of guessing, and rename never uses it.
- **[A larger plugin distribution.]** About 2 MB of JARs, which is less than today's `lib/server/` with lsp4j and its dependencies.

## Migration Plan

This is a single release with no data migration. Settings keep their `@State` storage and keys. Users who relied on the Alloy visualizer window now get the tool window. The CHANGELOG lists that change and the removed LSP requirement. Rollback means reverting the release, since nothing is persisted in a new format.

## Open Questions

- Whether a solver-handle `SATFactory` wrapper is cleaner than interrupting the thread, for SAT4J specifically. The spike in task 3.1 decides this. Both satisfy the specs.
- The exact Kodkod hook to use for finer-grained translation checkpoints, if the spike shows that one is needed.
