package com.serranofp.alloy.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.platform.lsp.api.LspClientManager
import com.serranofp.alloy.lsp.AlloyLspIntegrationProvider
import com.serranofp.alloy.run.AlloyRunService

/**
 * The server reads the analyzer options only at startup (it has no configuration request), so new
 * settings take effect by restarting it. A run in progress is ended first: it would die with the server.
 */
object AlloySettingsApplier {
    const val INTERRUPTED_MESSAGE = "The analysis was interrupted by a settings change"

    fun settingsChanged() {
        for (project in ProjectManager.getInstance().openProjects) {
            settingsChanged(project) { restartServer(it) }
        }
    }

    fun settingsChanged(project: Project, restart: (Project) -> Unit) {
        AlloyRunService.getInstance(project).activeRun?.fail(INTERRUPTED_MESSAGE)
        restart(project)
    }

    private fun restartServer(project: Project) {
        LspClientManager.getInstance(project).stopAndRestartClientsIfNeeded(AlloyLspIntegrationProvider::class.java)
    }
}
