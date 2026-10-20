package com.serranofp.alloy.settings

import com.intellij.openapi.project.ProjectManager
import com.serranofp.alloy.run.AlloyCommandResults

/**
 * Every analysis builds its options from the saved settings when it starts, so new settings take effect
 * with the next one, and nothing is restarted. Results computed with the old options become outdated,
 * including that of an analysis still running, which goes on with the options it started with.
 */
object AlloySettingsApplier {
    fun settingsChanged() {
        AlloySettings.getInstance().nextGeneration()
        for (project in ProjectManager.getInstance().openProjects) {
            AlloyCommandResults.getInstance(project).refreshHints()
        }
    }
}
