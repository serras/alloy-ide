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
import com.serranofp.alloy.AlloyBundle
import com.serranofp.alloy.settings.AlloySettings

/**
 * The single Alloy execution of a project, the instance viewer's enumeration, or a suite of executions run as
 * tests. At most one runs at a time: an analysis uses the IDE's own memory,
 * and the hints and gutter icons share one running state. Analyses are children of this service, so closing
 * the project stops them.
 */
@Service(Service.Level.PROJECT)
class AlloyRunService(private val project: Project) : Disposable {
    private var active: AlloyCommandExecution? = null

    /** Whether the instance viewer is asking for a new solution, which also takes the project's one analysis. */
    private var enumerating = false

    /** The suite holding the project's analysis: only its own executions may start. */
    private var suite: Suite? = null

    /** A run of several executions (see [startSuite]), passed to [startRun] by each of them. */
    class Suite internal constructor()

    val activeExecution: AlloyCommandExecution?
        @Synchronized get() = active

    /**
     * Registers [execution] as the active one and records it as running, or refuses (returning false, with
     * the results untouched) if another one is still running, or a suite other than [suite] holds the analysis.
     */
    fun startRun(execution: AlloyCommandExecution, suite: Suite? = null): Boolean {
        synchronized(this) {
            if (active != null || enumerating || this.suite !== suite) return false
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
        try {
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            ProgressManager.getInstance().run(AlloyCommandTask(project, execution, launcher, this))
        } catch (e: Throwable) {
            // Without its task nothing would end it, and the project's analysis would stay taken
            execution.terminate()
            throw e
        }
        return true
    }

    /** Takes the project's one analysis for an enumeration, or refuses (false) if another analysis runs. */
    @Synchronized
    fun startEnumeration(): Boolean {
        if (active != null || enumerating || suite != null) return false
        enumerating = true
        return true
    }

    /**
     * Takes the project's one analysis for a suite of executions until [suiteFinished], or refuses (null) if
     * another analysis runs.
     */
    @Synchronized
    fun startSuite(): Suite? {
        if (active != null || enumerating || suite != null) return null
        return Suite().also { suite = it }
    }

    @Synchronized
    fun suiteFinished(suite: Suite) {
        if (this.suite === suite) this.suite = null
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
        val BUSY_MESSAGE: String get() = AlloyBundle.message("run.busy")

        fun getInstance(project: Project): AlloyRunService = project.service()
    }
}
