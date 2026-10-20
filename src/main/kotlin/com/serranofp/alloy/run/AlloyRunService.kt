package com.serranofp.alloy.run

import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.serranofp.alloy.settings.AlloySettings

/**
 * The single Alloy execution of a project, or the instance viewer's enumeration. At most one runs at a time: an analysis uses the IDE's own memory,
 * and the hints and gutter icons share one running state. Analyses are children of this service, so closing
 * the project stops them.
 */
@Service(Service.Level.PROJECT)
class AlloyRunService(private val project: Project) : Disposable {
    private var active: AlloyCommandExecution? = null

    /** Whether the instance viewer is asking for a new solution, which also takes the project's one analysis. */
    private var enumerating = false

    val activeExecution: AlloyCommandExecution?
        @Synchronized get() = active

    /**
     * Registers [execution] as the active one and records it as running, or refuses (returning false, with
     * the results untouched) if another one is still running.
     */
    fun startRun(execution: AlloyCommandExecution): Boolean {
        synchronized(this) {
            if (active != null || enumerating) return false
            active = execution
        }
        execution.started()
        return true
    }

    /**
     * Executes [command] of [file], open in [editor], as a cancellable background task, on the current text of
     * the file and of the modules it opens, saved or not. If another execution is running, the editor says so
     * and the results are untouched.
     */
    fun launch(file: VirtualFile, command: AlloyCommandRef, editor: Editor, launcher: AlloyCommandLauncher = AlloyCommandLauncher(project)): Boolean {
        val results = AlloyCommandResults.getInstance(project)
        val execution = AlloyCommandExecution(
            this,
            results,
            file,
            command,
            results.stamp(editor.document),
            AlloySettings.getInstance().generation,
        )
        if (!startRun(execution)) {
            HintManager.getInstance().showInformationHint(editor, BUSY_MESSAGE)
            return false
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        ProgressManager.getInstance().run(AlloyCommandTask(project, execution, launcher, this))
        return true
    }

    /** Takes the project's one analysis for an enumeration, or refuses (false) if another analysis runs. */
    @Synchronized
    fun startEnumeration(): Boolean {
        if (active != null || enumerating) return false
        enumerating = true
        return true
    }

    @Synchronized
    fun enumerationFinished() {
        enumerating = false
    }

    @Synchronized
    fun runFinished(execution: AlloyCommandExecution) {
        if (active === execution) active = null
    }

    override fun dispose() {
        activeExecution?.terminate()
    }

    companion object {
        const val BUSY_MESSAGE = "An Alloy analysis is already running"

        fun getInstance(project: Project): AlloyRunService = project.service()
    }
}
