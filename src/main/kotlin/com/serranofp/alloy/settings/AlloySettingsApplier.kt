package com.serranofp.alloy.settings

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.platform.lsp.api.LspClientManager
import com.serranofp.alloy.lsp.AlloyLspIntegrationProvider
import com.serranofp.alloy.run.AlloyCommandExecution
import com.serranofp.alloy.run.AlloyRunService

/**
 * The server reads the analyzer options only at startup (it has no configuration request), so new
 * settings take effect by restarting it. A run in progress is ended first: it would die with the server.
 * Existing results become outdated: they were computed with the old options.
 */
object AlloySettingsApplier {
    const val INTERRUPTED_MESSAGE = "The analysis was interrupted by a settings change"

    fun settingsChanged() {
        for (project in ProjectManager.getInstance().openProjects) {
            settingsChanged(project) { restartServer(it) }
        }
    }

    fun settingsChanged(project: Project, restart: (Project) -> Unit) {
        AlloyRunService.getInstance(project).activeExecution?.fail(AlloyCommandExecution.INTERRUPTED, INTERRUPTED_MESSAGE)
        AlloySettings.getInstance().nextGeneration()
        ApplicationManager.getApplication().invokeLater({
            DaemonCodeAnalyzer.getInstance(project).restart("Alloy analyzer settings changed")
        }, project.disposed)
        restart(project)
    }

    private fun restartServer(project: Project) {
        LspClientManager.getInstance(project).stopAndRestartClientsIfNeeded(AlloyLspIntegrationProvider::class.java)
    }
}
