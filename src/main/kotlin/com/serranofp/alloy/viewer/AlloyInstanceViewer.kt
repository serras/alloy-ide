package com.serranofp.alloy.viewer

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
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
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiManager
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
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.swing.Icon

/** Where a shown solution came from. */
data class SolutionOrigin(
    val file: VirtualFile,
    val command: AlloyCommandRef,
    /** As written, with its blocks folded: `check noCycles for 3`. */
    val commandText: String,
    /** 1-based. */
    val line: Int,
    val counterexample: Boolean,
)

/**
 * What the tool window shows: one solution of [origin]'s command. [result] is the command's result that was
 * opened; its stamps tell when the session is outdated, also for the solutions enumerated from it.
 */
class ViewerSession(val origin: SolutionOrigin, val result: CommandResult, val model: AlloySolutionModel, private val anchor: RangeMarker?) {
    /** Where the command starts now, following edits. */
    val commandOffset: Int? get() = anchor?.takeIf { it.isValid }?.startOffset
}

/** The kinds of new solution that the toolbar asks for (see `A4Solution.fork`). */
enum class SolutionKind(val label: String, val description: String, val icon: Icon) {
    // The debugger's icons, by analogy: start over, take another path, drop back to the start, diverge from here
    Config("New Config", "Show a new configuration", AllIcons.Actions.Restart),
    Trace("New Trace", "Show a new trace with the same configuration", AllIcons.Actions.TraceOver),
    Init("New Init", "Show a new initial state", AllIcons.Actions.PopFrame),
    Fork("New Fork", "Show a new successor of the current state", AllIcons.Actions.RunToCursor),
}

class AlloyInstanceViewerState : BaseState() {
    var selectedTab by string(AlloyInstanceViewer.VISUALIZATION)
    var evaluatorVisible by property(false)
    var evaluatorProportion by property(AlloyInstanceViewer.EVALUATOR_PROPORTION)
}

/**
 * The "Alloy Instance" tool window's content: at most one [ViewerSession], which each opened result
 * replaces. It also asks the analyzer for new solutions of the shown one, as an analysis of the project.
 */
@Service(Service.Level.PROJECT)
@State(name = "AlloyInstanceViewer", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class AlloyInstanceViewer(private val project: Project) :
    SimplePersistentStateComponent<AlloyInstanceViewerState>(AlloyInstanceViewerState()), Disposable {
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    var session: ViewerSession? = null
        private set

    /** A message for the tool window's status line, such as "No more solutions of that kind". */
    @Volatile
    var status: String? = null
        private set

    /** Whether a new solution is being asked for. */
    @Volatile
    var enumerating: Boolean = false
        private set

    @Volatile
    private var enumeration: AlloyAnalysis? = null

    /** How a new solution is computed; replaced in tests. */
    internal var fork: (A4Solution, Int) -> A4Solution = { solution, p -> solution.fork(p) }

    /** The tab the user last selected, kept across results and IDE sessions. */
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

    /** Calls [listener] on the EDT whenever the session or status changes, until [parent] is disposed. */
    fun addListener(parent: Disposable, listener: () -> Unit) {
        listeners += listener
        Disposer.register(parent) { listeners -= listener }
    }

    private fun changed() {
        ApplicationManager.getApplication().invokeLater({ listeners.forEach { it() } }, project.disposed)
    }

    /** Shows the solution of [result], the latest result of [command] in [file], and brings the tool window up. */
    fun open(file: VirtualFile, command: AlloyCommandRef, result: CommandResult) {
        val link = result.link as? CommandLink.Solution ?: return
        val origin = ReadAction.computeBlocking<SolutionOrigin?, RuntimeException> { origin(file, command, result) } ?: return
        show(ViewerSession(origin, result, AlloySolutionModel(link.solution, module = link.module), anchor(file, command)))
        ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID)?.show()
    }

    private fun show(session: ViewerSession) {
        this.session = session
        status = null
        changed()
    }

    private fun origin(file: VirtualFile, command: AlloyCommandRef, result: CommandResult): SolutionOrigin? {
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return null
        val element = AlloyCommands.find(psiFile, command)
        val document = FileDocumentManager.getInstance().getDocument(file)
        val line = if (element != null && document != null) document.getLineNumber(element.textRange.startOffset) + 1 else 0
        val text = element?.let(AlloyPresentation::text) ?: command.displayName
        return SolutionOrigin(file, command, text, line, result.state == CommandState.CounterexampleFound)
    }

    private fun anchor(file: VirtualFile, command: AlloyCommandRef): RangeMarker? = ReadAction.computeBlocking<RangeMarker?, RuntimeException> {
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return@computeBlocking null
        val element = PsiManager.getInstance(project).findFile(file)?.let { AlloyCommands.find(it, command) } ?: return@computeBlocking null
        document.createRangeMarker(element.textRange.startOffset, element.textRange.startOffset)
    }

    /**
     * Why the session no longer describes the file or the settings, or null if it is current. Callable from any
     * thread: it is asked from UI listeners, such as a click in the trace path strip, which hold no read access.
     */
    fun outdated(session: ViewerSession? = this.session): Outdated? {
        if (session == null) return null
        val document = ReadAction.computeBlocking<Document?, RuntimeException> {
            FileDocumentManager.getInstance().getDocument(session.origin.file)
        } ?: return null
        return AlloyCommandResults.getInstance(project).outdated(session.result, document)
    }

    /** `graph.als: check noCycles for 3 (line 12) · Counterexample`, with an outdated mark when it is. */
    fun header(session: ViewerSession? = this.session): String {
        if (session == null) return ""
        val origin = session.origin
        val kind = if (origin.counterexample) "Counterexample" else "Instance"
        val outdated = if (outdated(session) != null) OUTDATED_SUFFIX else ""
        return "${origin.file.name}: ${origin.commandText} (line ${origin.line}) · $kind$outdated"
    }

    /** Opens the session's file with the caret on its command. */
    fun navigateToOrigin() {
        val session = session ?: return
        OpenFileDescriptor(project, session.origin.file, session.commandOffset ?: 0).navigate(true)
    }

    /** Whether the toolbar can ask for [kind]: only New Config for a static model, never for an outdated result. */
    fun isEnabled(kind: SolutionKind): Boolean {
        val session = session ?: return false
        if (enumerating || outdated(session) != null) return false
        return kind == SolutionKind.Config || session.model.isTemporal
    }

    /**
     * Asks the analyzer for a new solution of [kind] as an analysis of the project, with a cancellable progress
     * indicator. Refuses, saying so in the status line, while another analysis runs. Returns whether it started.
     */
    fun requestSolution(kind: SolutionKind): Boolean {
        val session = session ?: return false
        if (!isEnabled(kind)) return false
        val run = AlloyRunService.getInstance(project)
        if (!run.startEnumeration()) {
            status = AlloyRunService.BUSY_MESSAGE
            changed()
            return false
        }
        enumerating = true
        status = null
        changed()
        val model = session.model
        val p = when (kind) {
            SolutionKind.Config -> if (model.isTemporal) -1 else -3
            SolutionKind.Trace -> -2
            SolutionKind.Init -> 0
            SolutionKind.Fork -> model.state + 1
        }
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Alloy: ${kind.label}", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
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
                    val end = analysis.end!!
                    // The new session follows its command in the document, so it is made on the EDT
                    ApplicationManager.getApplication().invokeAndWait({ finished(session, model.solution, end) }, ModalityState.any())
                } finally {
                    enumeration = null
                    enumerating = false
                    run.enumerationFinished()
                    changed()
                }
            }
        })
        return true
    }

    /** Stops the search for a new solution, as cancelling its progress indicator does; the shown one stays. */
    fun stopEnumeration() {
        enumeration?.stop()
    }

    private fun finished(session: ViewerSession, previous: A4Solution, end: AlloyAnalysisEvent.End) {
        when (end) {
            is AlloyAnalysisEvent.Sat ->
                if (end.solution === previous) status = NO_MORE_SOLUTIONS
                else show(ViewerSession(session.origin, session.result, AlloySolutionModel(end.solution, session.model.vizState, session.model.module), null).withAnchorOf(session))
            // `fork` gives an unsatisfiable solution, or the same one, when there is nothing new
            is AlloyAnalysisEvent.Unsat -> status = NO_MORE_SOLUTIONS
            is AlloyAnalysisEvent.Stopped -> status = STOPPED
            is AlloyAnalysisEvent.Failed -> status = end.failure.message
        }
    }

    private fun ViewerSession.withAnchorOf(previous: ViewerSession): ViewerSession =
        ViewerSession(origin, result, model, previous.commandOffset?.let { offset ->
            FileDocumentManager.getInstance().getDocument(origin.file)?.createRangeMarker(offset, offset)
        })

    /** Light tests share their project, and with it this service. */
    @TestOnly
    fun clear() {
        session = null
        status = null
        fork = { solution, p -> solution.fork(p) }
    }

    override fun dispose() {}

    companion object {
        const val TOOL_WINDOW_ID = "Alloy Instance"

        const val VISUALIZATION = "Visualization"
        const val TEXT = "Text"
        const val TABLE = "Table"

        const val EVALUATOR_PROPORTION = 0.7f

        const val EMPTY_TEXT = "Instances and counterexamples appear here when you click the hint of a command that found one"
        const val OUTDATED_SUFFIX = " (outdated)"
        const val NO_MORE_SOLUTIONS = "No more solutions of that kind"
        const val STOPPED = "Stopped before a new solution was found"
        const val RUN_AGAIN = "The result is outdated: run the command again"

        private const val POLL_MILLIS = 100L

        fun getInstance(project: Project): AlloyInstanceViewer = project.service()
    }
}
