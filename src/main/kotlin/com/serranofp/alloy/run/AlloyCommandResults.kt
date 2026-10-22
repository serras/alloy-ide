package com.serranofp.alloy.run

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.hints.declarative.impl.DeclarativeInlayHintsPassFactory
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.psi.PsiDocumentManager
import com.serranofp.alloy.AlloyFileType
import com.serranofp.alloy.settings.AlloySettings
import edu.mit.csail.sdg.parser.CompModule
import edu.mit.csail.sdg.translator.A4Solution
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class CommandState { Running, InstanceFound, CounterexampleFound, NoInstance, NoCounterexample, Stopped, Failed }

/**
 * What the tooltip shows: the solver fallback warning, the analyzer's log, why the execution failed, and the
 * log of the vacuity check's queries.
 */
data class CommandDetails(
    val warnings: List<String> = emptyList(),
    val log: List<AlloyRunLog.Line> = emptyList(),
    val failure: String? = null,
    val vacuityLog: List<AlloyRunLog.Line> = emptyList(),
)

/** What a click on a result opens. */
sealed interface CommandLink {
    /**
     * The instance or counterexample, as the analyzer returned it, and the [module] the analysis parsed (see
     * [com.serranofp.alloy.analysis.AlloyAnalysisEvent.Sat]).
     */
    class Solution(val solution: A4Solution, val module: CompModule? = null) : CommandLink
}

/** Whether the outcome of a command with `expect` matched it; a failed execution does not. */
enum class Expectation { Passed, Failed }

/**
 * What the vacuity check of a check without counterexample found, or that it is still running. A
 * `premiseText` says that the assertion's premise was solved with the facts.
 */
sealed interface Vacuity {
    data object Checking : Vacuity
    data class NotVacuous(val millis: Long, val premiseText: String? = null) : Vacuity
    /** The facts have no instance within the check's scope. */
    data class VacuousFacts(val millis: Long, val coreSize: Int, val premiseText: String? = null) : Vacuity
    /** The facts have instances, but none satisfies the assertion's premise. */
    data class VacuousPremise(val premiseText: String, val millis: Long, val coreSize: Int) : Vacuity
    data class Unknown(val reason: String) : Vacuity
}

/**
 * The latest result of one command. [documentStamp] (see [AlloyCommandResults.stamp]) and [settingsGeneration]
 * are taken when the command is launched, so that an edit during the analysis already makes its result outdated.
 */
data class CommandResult(
    val state: CommandState,
    val summary: String,
    val details: CommandDetails = CommandDetails(),
    val link: CommandLink? = null,
    val documentStamp: Long,
    val settingsGeneration: Long,
    /** Null for a command without `expect`, and while it runs or after it was stopped. */
    val expectation: Expectation? = null,
    val vacuity: Vacuity? = null,
    /** The project modules that the analysis read besides the command's own file: their edits outdate it too. */
    val modules: Set<VirtualFile> = emptySet(),
)

/** Whether the execution of this result still runs: its analysis, or its vacuity check. */
val CommandResult.isRunning: Boolean
    get() = state == CommandState.Running || vacuity == Vacuity.Checking

/** How a result reads as a test: the gutter icon and the test tree both use [verdict]. */
enum class Verdict { Run, Passed, Failed, Vacuous }

fun verdict(result: CommandResult): Verdict = when {
    result.state == CommandState.Running -> Verdict.Run
    result.vacuity is Vacuity.VacuousFacts || result.vacuity is Vacuity.VacuousPremise -> Verdict.Vacuous
    result.expectation == Expectation.Passed -> Verdict.Passed
    result.expectation == Expectation.Failed -> Verdict.Failed
    else -> Verdict.Run
}

enum class Outdated { FileChanged, SettingsChanged }

/**
 * The command results of a project for this IDE session, read by the inlay hints. Whether a result is
 * outdated is computed when it is read: the daemon runs the hints again after every edit anyway.
 *
 * Only the [MAX_SOLUTIONS] latest instances and counterexamples are kept, as each holds the analyzer's
 * solution and its parse of the model: older results keep their outcome and details, but can no longer be
 * opened. The results of a deleted file are dropped.
 */
@Service(Service.Level.PROJECT)
class AlloyCommandResults(private val project: Project) : Disposable {
    private val results = mutableMapOf<VirtualFile, MutableMap<AlloyCommandRef, CommandResult>>()

    /** The results that link to a solution, oldest first. */
    private val linked = LinkedHashSet<Pair<VirtualFile, AlloyCommandRef>>()

    /**
     * The edits of Alloy documents since this service started, and their number at the latest edit of each
     * file. Unlike `Document.modificationStamp`, which undo restores, it only grows, so undoing an edit does
     * not make a result current again. It is kept by file rather than on the document, which the IDE may
     * collect, and with it its counter, once the file is closed.
     */
    private val edits = AtomicLong()
    private val lastEdits = ConcurrentHashMap<VirtualFile, Long>()

    /** The refreshes waiting for the EDT, by file or [ALL_FILES]: each reads the results when it runs. */
    private val pendingRefreshes = ConcurrentHashMap.newKeySet<Any>()

    init {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                if (file.fileType == AlloyFileType) lastEdits[file] = edits.incrementAndGet()
            }
        }, this)
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val deleted = events.filterIsInstance<VFileDeleteEvent>().map { it.file }
                if (deleted.isNotEmpty()) forget { file -> deleted.any { VfsUtilCore.isAncestor(it, file, false) } }
            }
        })
    }

    /**
     * The edit count when an analysis of [document] starts. It is the same for every document: a result is
     * outdated once its file, or a module that the analysis read, is edited after it (see [outdated]).
     */
    fun stamp(@Suppress("UNUSED_PARAMETER") document: Document): Long = edits.get()

    @Synchronized
    fun get(file: VirtualFile, command: AlloyCommandRef): CommandResult? = results[file]?.get(command)

    @Synchronized
    fun results(file: VirtualFile): Map<AlloyCommandRef, CommandResult> = results[file]?.toMap().orEmpty()

    /** Replaces the result of [command], and makes the editors of [file] show it, unless [file] was deleted. */
    fun record(file: VirtualFile, command: AlloyCommandRef, result: CommandResult) {
        val unlinked = mutableSetOf<VirtualFile>()
        synchronized(this) {
            if (!file.isValid) return
            results.getOrPut(file) { mutableMapOf() }[command] = result
            linked.remove(file to command)
            if (result.link != null) linked += file to command
            while (linked.size > MAX_SOLUTIONS) {
                val (oldFile, oldCommand) = linked.first().also(linked::remove)
                results[oldFile]?.computeIfPresent(oldCommand) { _, old -> old.copy(link = null) }
                unlinked += oldFile
            }
        }
        (unlinked + file).forEach(::refreshHints)
    }

    private fun forget(deleted: (VirtualFile) -> Boolean) {
        synchronized(this) {
            results.keys.removeIf(deleted)
            linked.removeIf { deleted(it.first) }
        }
        lastEdits.keys.removeIf(deleted)
    }

    /**
     * Shows the current results (hints and gutter icons) in the editors of [file], or of every Alloy file.
     * Analysis events come in bursts, so a refresh that still waits for the EDT also serves the later ones.
     */
    fun refreshHints(file: VirtualFile? = null) {
        val key: Any = file ?: ALL_FILES
        if (!pendingRefreshes.add(key)) return
        ApplicationManager.getApplication().invokeLater({
            pendingRefreshes.remove(key)
            val documents = FileDocumentManager.getInstance()
            for (editor in EditorFactory.getInstance().allEditors) {
                if (editor.project != project) continue
                val shown = documents.getFile(editor.document) ?: continue
                if (if (file != null) shown == file else shown.fileType == AlloyFileType) recomputeHints(editor)
            }
        }, project.disposed)
    }

    /**
     * Restarting the daemon is not enough: the declarative hints pass skips editors whose PSI did not change
     * since it last ran, unless it is asked to recompute. That request is internal API, so if an IDE lacks
     * it the daemon is restarted on the file instead, and the hints follow at the latest with the next edit.
     */
    private fun recomputeHints(editor: Editor) {
        try {
            DeclarativeInlayHintsPassFactory.scheduleRecompute(editor, project)
        } catch (_: LinkageError) {
            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return
            DaemonCodeAnalyzer.getInstance(project).restart(psiFile, "Alloy command results changed")
        }
    }

    /** Light tests share their project, and with it this service. */
    @TestOnly
    @Synchronized
    fun clear() {
        results.clear()
        linked.clear()
    }

    /** Why [result], of the file of [document], no longer describes it, or null if it is current. */
    fun outdated(result: CommandResult, document: Document): Outdated? {
        val files = result.modules + listOfNotNull(FileDocumentManager.getInstance().getFile(document))
        return when {
            files.any { (lastEdits[it] ?: 0) > result.documentStamp } -> Outdated.FileChanged
            AlloySettings.getInstance().generation != result.settingsGeneration -> Outdated.SettingsChanged
            else -> null
        }
    }

    fun isOutdated(result: CommandResult, document: Document): Boolean = outdated(result, document) != null

    override fun dispose() {}

    companion object {
        /** How many results keep the instance or counterexample that they found (see [AlloyCommandResults]). */
        const val MAX_SOLUTIONS = 10

        private val ALL_FILES = Any()

        fun getInstance(project: Project): AlloyCommandResults = project.service()
    }
}
