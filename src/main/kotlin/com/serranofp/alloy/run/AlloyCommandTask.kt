package com.serranofp.alloy.run

import com.intellij.openapi.Disposable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.serranofp.alloy.AlloyBundle

/**
 * The status-bar progress of [execution]: launches the command, and follows the analyzer's progress until
 * the execution finishes. Cancelling the task stops the analysis.
 */
class AlloyCommandTask(
    project: Project,
    private val execution: AlloyCommandExecution,
    private val launcher: AlloyCommandLauncher,
    /** Stops the analysis when disposed. */
    private val parent: Disposable,
) : Task.Backgroundable(project, AlloyBundle.message("run.task.title", execution.command.displayName), true) {
    override fun run(indicator: ProgressIndicator) {
        indicator.isIndeterminate = true
        if (!indicator.isCanceled) launcher.launch(execution, parent)
        execution.awaitOrStop(indicator::isCanceled) { execution.progress?.let { indicator.text2 = it } }
    }
}
