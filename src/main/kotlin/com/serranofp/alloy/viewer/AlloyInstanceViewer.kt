package com.serranofp.alloy.viewer

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.serranofp.alloy.AlloyBundle
import com.serranofp.alloy.analysis.AlloyAnalysis
import com.serranofp.alloy.analysis.AlloyAnalysisEngine
import com.serranofp.alloy.analysis.AlloyAnalysisEvent
import com.serranofp.alloy.editor.AlloyPresentation
import com.serranofp.alloy.run.AlloyCommandRef
import com.serranofp.alloy.run.AlloyCommandResults
import com.serranofp.alloy.run.AlloyCommands
import com.serranofp.alloy.run.AlloyRunService
import com.serranofp.alloy.run.CommandLink
import com.serranofp.alloy.run.CommandResult
import com.serranofp.alloy.run.CommandState
import com.serranofp.alloy.run.Outdated
import edu.mit.csail.sdg.translator.A4Solution
import org.jetbrains.annotations.PropertyKey
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.swing.Icon

/** Where a shown solution came from. */
data class SolutionOrigin(
    val file: VirtualFile,
    val command: AlloyCommandRef,
    /** As written, with its blocks folded: `check noCycles for 3`. */
    val commandText: String,
    val counterexample: Boolean,
)

/**
 * What the tool window shows: one solution of [origin]'s command. [result] is the command's result that was
 * opened; its stamps tell when the session is outdated, also for the solutions enumerated from it, which share
 * its [anchor] too.
 */
class ViewerSession(val origin: SolutionOrigin, val result: CommandResult, val model: AlloySolutionModel, internal val anchor: RangeMarker?) {
    /** Where the command starts now, following edits; null if it was not found. */
    val commandOffset: Int? get() = anchor?.takeIf { it.isValid }?.startOffset
}

private const val BUNDLE = "messages.AlloyBundle"

/** The kinds of new solution that the toolbar asks for (see `A4Solution.fork`). */
enum class SolutionKind(
    @PropertyKey(resourceBundle = BUNDLE) private val labelKey: String,
    @PropertyKey(resourceBundle = BUNDLE) private val descriptionKey: String,
    val icon: Icon,
) {
    // The debugger's icons, by analogy: start over, take another path, drop back to the start, diverge from here
    Config("viewer.new.config", "viewer.new.config.description", AllIcons.Actions.Restart),
    Trace("viewer.new.trace", "viewer.new.trace.description", AllIcons.Actions.TraceOver),
    Init("viewer.new.init", "viewer.new.init.description", AllIcons.Actions.PopFrame),
    Fork("viewer.new.fork", "viewer.new.fork.description", AllIcons.Actions.RunToCursor);

    val label: String get() = AlloyBundle.message(labelKey)
    val description: String get() = AlloyBundle.message(descriptionKey)
}

class AlloyInstanceViewerState : BaseState() {
    var selectedTab by string(AlloyInstanceViewer.VISUALIZATION)
    var evaluatorVisible by property(false)
    var evaluatorProportion by property(AlloyInstanceViewer.EVALUATOR_PROPORTION)
}

/** A file's [session], which each opened result or new solution replaces, and the message of its status line. */
private data class FileSession(val session: ViewerSession, val status: String? = null)

/**
 * The viewers' content: at most one [ViewerSession] per file, which each result opened in that file replaces,
 * kept while a viewer of the file is [attached][attach]. It also asks the analyzer for new solutions of a shown
 * one, as an analysis of the project, so one at a time. Building a session's model is heavy analyzer work (see
 * [AlloySolutionModel]), which is done off the EDT.
 */
@Service(Service.Level.PROJECT)
@State(name = "AlloyInstanceViewer", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class AlloyInstanceViewer(private val project: Project) :
    SimplePersistentStateComponent<AlloyInstanceViewerState>(AlloyInstanceViewerState()), Disposable {
    private val sessions = ConcurrentHashMap<VirtualFile, FileSession>()
    private val listeners = ConcurrentHashMap<VirtualFile, CopyOnWriteArrayList<() -> Unit>>()

    /** The viewers of each file, while they are not disposed. */
    private val attached = HashMap<VirtualFile, MutableSet<Disposable>>()

    /** The file whose new solution is being asked for, if any. */
    @Volatile
    private var enumeratingFile: VirtualFile? = null

    /** Whether a new solution is being asked for, of any file. */
    val enumerating: Boolean get() = enumeratingFile != null

    @Volatile
    private var enumeration: AlloyAnalysis? = null

    /** How a new solution is computed; replaced in tests. */
    internal var fork: (A4Solution, Int) -> A4Solution = { solution, p -> solution.fork(p) }

    /** How the search for a new solution is started; replaced in tests. */
    internal var schedule: (Task.Backgroundable) -> Unit = { ProgressManager.getInstance().run(it) }

    /** The id of the tab the user last selected, kept across results and IDE sessions. */
    var selectedTab: String
        get() = state.selectedTab ?: VISUALIZATION
        set(value) {
            state.selectedTab = value
        }

    /** Whether the Evaluator panel is shown, kept across results and IDE sessions. */
    var evaluatorVisible: Boolean
        get() = state.evaluatorVisible
        set(value) {
            state.evaluatorVisible = value
        }

    /** The part of the tool window's height above the Evaluator panel. */
    var evaluatorProportion: Float
        get() = state.evaluatorProportion
        set(value) {
            state.evaluatorProportion = value
        }

    /** The solution shown for [file], if any. */
    fun session(file: VirtualFile): ViewerSession? = sessions[file]?.session

    /** A message for [file]'s status line, such as "No more solutions of that kind". */
    fun status(file: VirtualFile): String? = sessions[file]?.status

    /**
     * Calls [listener] on the EDT whenever [file]'s session or status changes, or an enumeration starts or ends,
     * until [parent] is disposed.
     */
    fun addListener(file: VirtualFile, parent: Disposable, listener: () -> Unit) {
        val fileListeners = listeners.computeIfAbsent(file) { CopyOnWriteArrayList() }
        fileListeners += listener
        Disposer.register(parent) { fileListeners -= listener }
    }

    /**
     * Keeps [file]'s session while [viewer] is not disposed. Once the last viewer attached to [file] is disposed,
     * its session is dropped, with its command's anchor.
     */
    fun attach(file: VirtualFile, viewer: Disposable) {
        synchronized(attached) { attached.getOrPut(file) { mutableSetOf() } += viewer }
        Disposer.register(viewer) { detach(file, viewer) }
    }

    private fun detach(file: VirtualFile, viewer: Disposable) {
        synchronized(attached) {
            val viewers = attached[file] ?: return
            viewers -= viewer
            if (viewers.isNotEmpty()) return
            attached -= file
        }
        sessions.remove(file)?.session?.anchor?.dispose()
        changed(file)
    }

    /** Tells [file]'s listeners, or every file's when it is null. */
    private fun changed(file: VirtualFile? = null) {
        ApplicationManager.getApplication().invokeLater({
            val toTell = if (file == null) listeners.values.toList() else listOfNotNull(listeners[file])
            toTell.forEach { fileListeners -> fileListeners.forEach { it() } }
        }, project.disposed)
    }

    private fun setStatus(file: VirtualFile, status: String?) {
        sessions.computeIfPresent(file) { _, shown -> shown.copy(status = status) }
        changed(file)
    }

    /**
     * Shows the solution of [result], the latest result of [command] in [file], in [file]'s viewers, and shows the preview
     * of [editor], or of [file]'s selected editor, if it shows only the text. The model is built under a modal progress, which keeps the IDE painting; the command is then found in the
     * committed PSI, as its offsets are the document's only then.
     */
    @RequiresEdt
    fun open(file: VirtualFile, command: AlloyCommandRef, result: CommandResult, editor: Editor? = null) {
        val link = result.link as? CommandLink.Solution ?: return
        val model = ProgressManager.getInstance().runProcessWithProgressSynchronously<AlloySolutionModel, RuntimeException>(
            { AlloySolutionModel(link.solution, module = link.module).also(AlloySolutionModel::warm) },
            AlloyBundle.message("viewer.opening"), false, project,
        )
        FileDocumentManager.getInstance().getDocument(file)?.let(PsiDocumentManager.getInstance(project)::commitDocument)
        val session = ReadAction.computeBlocking<ViewerSession?, RuntimeException> { session(file, command, result, model) } ?: return
        show(session)
        revealPreview(file, editor)
    }

    /** Shows the preview beside the text, in the editor of [file] that wraps [editor], or the selected one; a preview only stays so. */
    private fun revealPreview(file: VirtualFile, editor: Editor?) {
        val manager = FileEditorManager.getInstance(project)
        val target = if (editor == null) manager.getSelectedEditor(file) as? AlloyEditorWithPreview
        else manager.getAllEditors(file).filterIsInstance<AlloyEditorWithPreview>().find { it.editor == editor }
        if (target?.getLayout() == TextEditorWithPreview.Layout.SHOW_EDITOR) target.setLayout(TextEditorWithPreview.Layout.SHOW_EDITOR_AND_PREVIEW)
    }

    private fun show(session: ViewerSession) {
        val file = session.origin.file
        sessions[file] = FileSession(session)
        changed(file)
    }

    /** The session of [model], anchored at its command when the command is found once, for its text and offset. */
    private fun session(file: VirtualFile, command: AlloyCommandRef, result: CommandResult, model: AlloySolutionModel): ViewerSession? {
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return null
        val element = AlloyCommands.find(psiFile, command)
        val document = FileDocumentManager.getInstance().getDocument(file)
        val start = element?.textRange?.startOffset
        val anchor = if (start != null && document != null) document.createRangeMarker(start, start) else null
        val text = element?.let(AlloyPresentation::text) ?: command.displayName
        val origin = SolutionOrigin(file, command, text, result.state == CommandState.CounterexampleFound)
        return ViewerSession(origin, result, model, anchor)
    }

    private fun document(session: ViewerSession): Document? =
        ReadAction.computeBlocking<Document?, RuntimeException> { FileDocumentManager.getInstance().getDocument(session.origin.file) }

    /**
     * Why the session no longer describes the file or the settings, or null if it is current. Callable from any
     * thread: it is asked from UI listeners, such as a click in the trace path strip, which hold no read access.
     */
    fun outdated(session: ViewerSession?): Outdated? {
        if (session == null) return null
        val document = document(session) ?: return null
        return AlloyCommandResults.getInstance(project).outdated(session.result, document)
    }

    /**
     * `graph.als: check noCycles for 3 (line 12) · Counterexample`, with an outdated mark when it is. The line is
     * where the command is now, as the header navigates there; without the command, there is none.
     */
    fun header(session: ViewerSession?): String {
        if (session == null) return ""
        val origin = session.origin
        val kind = AlloyBundle.message(if (origin.counterexample) "viewer.counterexample" else "viewer.instance")
        val outdated = if (outdated(session) != null) AlloyBundle.message("result.outdated.suffix") else ""
        val offset = session.commandOffset
        val document = document(session)
        return if (offset == null || document == null) AlloyBundle.message("viewer.header.no.line", origin.file.name, origin.commandText, kind) + outdated
        else AlloyBundle.message("viewer.header", origin.file.name, origin.commandText, (document.getLineNumber(offset) + 1).toString(), kind) + outdated
    }

    /** Opens [file] with the caret on the command of its session, showing the text of its editor if only its preview shows. */
    fun navigateToOrigin(file: VirtualFile) {
        val session = session(file) ?: return
        val editor = FileEditorManager.getInstance(project).getSelectedEditor(file) as? AlloyEditorWithPreview
        if (editor?.getLayout() == TextEditorWithPreview.Layout.SHOW_PREVIEW) editor.setLayout(TextEditorWithPreview.Layout.SHOW_EDITOR_AND_PREVIEW)
        OpenFileDescriptor(project, session.origin.file, session.commandOffset ?: 0).navigate(true)
    }

    /**
     * Whether [file]'s toolbar can ask for [kind]: only New Config for a static model, never for an outdated result,
     * nor while a new solution of [file] is being asked for. Another file's search leaves it enabled: asking then
     * says, in [file]'s status line, that an analysis is already running.
     */
    fun isEnabled(file: VirtualFile, kind: SolutionKind): Boolean {
        val session = session(file) ?: return false
        return enumeratingFile != file && canAsk(session, kind)
    }

    private fun canAsk(session: ViewerSession, kind: SolutionKind): Boolean =
        outdated(session) == null && (kind == SolutionKind.Config || session.model.isTemporal)

    /**
     * Asks the analyzer for a new solution of [kind] of [file]'s session as an analysis of the project, with a
     * cancellable progress indicator. Refuses, saying so in [file]'s status line, while another analysis runs, also
     * another file's new solution. Returns whether it started.
     * The new model is built in the background task too. The project's analysis is given back in `onFinished`,
     * which the platform calls even when the task is cancelled before it runs.
     */
    @RequiresEdt
    fun requestSolution(file: VirtualFile, kind: SolutionKind): Boolean {
        val session = session(file) ?: return false
        if (!canAsk(session, kind)) return false
        val run = AlloyRunService.getInstance(project)
        if (!run.startEnumeration()) {
            setStatus(file, AlloyRunService.BUSY_MESSAGE)
            return false
        }
        enumeratingFile = file
        sessions.computeIfPresent(file) { _, shown -> shown.copy(status = null) }
        // Its buttons are disabled while it searches; every file's toolbar is told, as the project's analysis is taken
        changed()
        val model = session.model
        // Read here, as only the EDT changes it; the new model copies it on the EDT
        val theme = model.vizState
        val p = when (kind) {
            SolutionKind.Config -> if (model.isTemporal) -1 else -3
            SolutionKind.Trace -> -2
            SolutionKind.Init -> 0
            SolutionKind.Fork -> model.state + 1
        }
        schedule(object : Task.Backgroundable(project, AlloyBundle.message("viewer.enumeration.title", kind.label), true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val analysis = AlloyAnalysisEngine.getInstance().start(kind.label, this@AlloyInstanceViewer, model.solution.opt, {}) { _, _ ->
                    fork(model.solution, p)
                }
                enumeration = analysis
                var stopRequested = false
                while (!analysis.await(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                    if (indicator.isCanceled && !stopRequested) {
                        stopRequested = true
                        analysis.stop()
                    }
                }
                finished(session, analysis.end!!) { AlloySolutionModel(it, theme, model.module).also(AlloySolutionModel::warm) }
            }

            override fun onFinished() {
                enumeration = null
                enumeratingFile = null
                run.enumerationFinished()
                changed()
            }
        })
        return true
    }

    /** Stops the search for a new solution, as cancelling its progress indicator does; the shown one stays. */
    @TestOnly
    fun stopEnumeration() {
        enumeration?.stop()
    }

    /**
     * Shows the solution found from [session], built by [build], which shares its command's anchor, or says why
     * there is none, in the file of [session]. If that file shows another session by now, or none, nothing changes.
     */
    private fun finished(session: ViewerSession, end: AlloyAnalysisEvent.End, build: (A4Solution) -> AlloySolutionModel) {
        val file = session.origin.file
        if (session(file) !== session) return
        when (end) {
            is AlloyAnalysisEvent.Sat ->
                if (end.solution === session.model.solution) setStatus(file, NO_MORE_SOLUTIONS)
                else show(ViewerSession(session.origin, session.result, build(end.solution), session.anchor))
            // `fork` gives an unsatisfiable solution, or the same one, when there is nothing new
            is AlloyAnalysisEvent.Unsat -> setStatus(file, NO_MORE_SOLUTIONS)
            is AlloyAnalysisEvent.Stopped -> setStatus(file, STOPPED)
            is AlloyAnalysisEvent.Failed -> setStatus(file, end.failure.message)
        }
    }

    /** Light tests share their project, and with it this service. */
    @TestOnly
    fun clear() {
        sessions.values.forEach { it.session.anchor?.dispose() }
        sessions.clear()
        synchronized(attached) { attached.clear() }
        fork = { solution, p -> solution.fork(p) }
        schedule = { ProgressManager.getInstance().run(it) }
    }

    override fun dispose() {}

    companion object {
        /** The ids of the tabs, as [selectedTab] keeps them; their titles are in the bundle. */
        const val VISUALIZATION = "Visualization"
        const val TEXT = "Text"
        const val TABLE = "Table"

        const val EVALUATOR_PROPORTION = 0.7f

        val EMPTY_TEXT: String get() = AlloyBundle.message("viewer.empty")
        val NO_MORE_SOLUTIONS: String get() = AlloyBundle.message("viewer.no.more.solutions")
        val STOPPED: String get() = AlloyBundle.message("viewer.stopped")
        val RUN_AGAIN: String get() = AlloyBundle.message("viewer.run.again")

        private const val POLL_MILLIS = 100L

        fun getInstance(project: Project): AlloyInstanceViewer = project.service()
    }
}
