package com.serranofp.alloy.run

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project

/**
 * The status-bar progress of [execution]: waits for the server, launches the command, and follows the
 * analyzer's progress until the execution finishes. Cancelling the task stops the analysis.
 */
class AlloyCommandTask(
    project: Project,
    private val execution: AlloyCommandExecution,
    private val launcher: AlloyCommandLauncher,
    /** Blocks until the server is running, or gives up with null. */
    private val connection: () -> AlloyServerConnection?,
) : Task.Backgroundable(project, "Alloy: Run ${execution.command.displayName}", true) {
    override fun run(indicator: ProgressIndicator) {
        indicator.isIndeterminate = true
        val server = connection()
        if (!indicator.isCanceled) {
            launcher.launch(execution, server, server?.uriOf(execution.file), execution.file.name, execution.command)
        }
        var stopRequested = false
        while (!execution.waitFor(POLL_MILLIS)) {
            execution.log.progress?.let { indicator.text2 = it }
            if (indicator.isCanceled && !stopRequested) {
                stopRequested = true
                execution.stop()
            }
        }
    }

    private companion object {
        const val POLL_MILLIS = 100L
    }
}
