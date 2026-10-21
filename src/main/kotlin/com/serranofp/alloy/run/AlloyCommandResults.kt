package com.serranofp.alloy.run

import com.intellij.codeInsight.hints.declarative.impl.DeclarativeInlayHintsPassFactory
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.UserDataHolderEx
import com.intellij.openapi.vfs.VirtualFile
import com.serranofp.alloy.AlloyFileType
import com.serranofp.alloy.settings.AlloySettings
import edu.mit.csail.sdg.parser.CompModule
import edu.mit.csail.sdg.translator.A4Solution
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.atomic.AtomicLong

enum class CommandState { Running, InstanceFound, CounterexampleFound, NoInstance, NoCounterexample, Stopped, Failed }

/** What the tooltip shows: the solver fallback warning, the analyzer's log and why the execution failed. */
data class CommandDetails(
    val warnings: List<String> = emptyList(),
    val log: List<AlloyRunLog.Line> = emptyList(),
    val failure: String? = null,
)

/** What a click on a result opens. */
sealed interface CommandLink {
    /**
     * The instance or counterexample, as the analyzer returned it, and the [module] the analysis parsed (see
     * [com.serranofp.alloy.analysis.AlloyAnalysisEvent.Sat]).
     */
    class Solution(val solution: A4Solution, val module: CompModule? = null) : CommandLink
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
)

enum class Outdated { FileChanged, SettingsChanged }

/**
 * The command results of a project for this IDE session, read by the inlay hints. Whether a result is
 * outdated is computed when it is read: the daemon runs the hints again after every edit anyway.
 */
@Service(Service.Level.PROJECT)
class AlloyCommandResults(private val project: Project) : Disposable {
    private val results = mutableMapOf<VirtualFile, MutableMap<AlloyCommandRef, CommandResult>>()

    init {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                edits(event.document).incrementAndGet()
            }
        }, this)
    }

    /**
     * Counts the changes of [document] since this service started. Unlike `Document.modificationStamp`,
     * which undo restores, it only grows, so undoing an edit does not make a result current again.
     */
    fun stamp(document: Document): Long = edits(document).get()

    @Synchronized
    fun get(file: VirtualFile, command: AlloyCommandRef): CommandResult? = results[file]?.get(command)

    @Synchronized
    fun results(file: VirtualFile): Map<AlloyCommandRef, CommandResult> = results[file]?.toMap().orEmpty()

    /** Replaces the result of [command], and makes the editors of [file] show it. */
    fun record(file: VirtualFile, command: AlloyCommandRef, result: CommandResult) {
        synchronized(this) { results.getOrPut(file) { mutableMapOf() }[command] = result }
        refreshHints(file)
    }

    /**
     * Shows the current results (hints and gutter icons) in the editors of [file], or of every Alloy file.
     * Restarting the daemon is not enough: the declarative hints pass skips editors whose PSI did not change
     * since it last ran, unless it is asked to recompute.
     */
    fun refreshHints(file: VirtualFile? = null) {
        ApplicationManager.getApplication().invokeLater({
            val documents = FileDocumentManager.getInstance()
            for (editor in EditorFactory.getInstance().allEditors) {
                if (editor.project != project) continue
                val shown = documents.getFile(editor.document) ?: continue
                if (if (file != null) shown == file else shown.fileType == AlloyFileType) {
                    DeclarativeInlayHintsPassFactory.scheduleRecompute(editor, project)
                }
            }
        }, project.disposed)
    }

    /** Light tests share their project, and with it this service. */
    @TestOnly
    @Synchronized
    fun clear() = results.clear()

    /** Why [result] no longer describes [document], or null if it is current. */
    fun outdated(result: CommandResult, document: Document): Outdated? = when {
        stamp(document) != result.documentStamp -> Outdated.FileChanged
        AlloySettings.getInstance().generation != result.settingsGeneration -> Outdated.SettingsChanged
        else -> null
    }

    fun isOutdated(result: CommandResult, document: Document): Boolean = outdated(result, document) != null

    override fun dispose() {}

    companion object {
        private val EDITS = Key.create<AtomicLong>("alloy.document.edits")

        private fun edits(document: Document): AtomicLong =
            (document as UserDataHolderEx).putUserDataIfAbsent(EDITS, AtomicLong())

        fun getInstance(project: Project): AlloyCommandResults = project.service()
    }
}
