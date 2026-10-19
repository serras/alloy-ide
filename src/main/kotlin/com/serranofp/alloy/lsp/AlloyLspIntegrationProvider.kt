package com.serranofp.alloy.lsp

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.Lsp4jClient
import com.intellij.platform.lsp.api.LspServerListener
import com.intellij.platform.lsp.api.LspServerNotificationsHandler
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.platform.lsp.api.ProjectWideLspClientDescriptor
import com.intellij.platform.lsp.api.customization.LspCodeLensCustomizer
import com.intellij.platform.lsp.api.customization.LspCodeLensDisabled
import com.intellij.platform.lsp.api.customization.LspCompletionCustomizer
import com.intellij.platform.lsp.api.customization.LspCompletionDisabled
import com.intellij.platform.lsp.api.customization.LspCustomization
import com.intellij.platform.lsp.api.customization.LspDocumentLinkCustomizer
import com.intellij.platform.lsp.api.customization.LspDocumentLinkDisabled
import com.intellij.platform.lsp.api.customization.LspFormattingCustomizer
import com.intellij.platform.lsp.api.customization.LspFormattingDisabled
import com.serranofp.alloy.AlloyFileType
import com.serranofp.alloy.run.AlloyRunService
import com.serranofp.alloy.settings.AlloySettings
import com.serranofp.alloy.settings.AlloySolvers
import org.eclipse.lsp4j.services.LanguageServer
import java.nio.file.Path

class AlloyLspIntegrationProvider : LspIntegrationProvider {
    override fun fileOpened(
        project: Project,
        file: VirtualFile,
        clientStarter: LspIntegrationProvider.LspClientStarter,
    ) {
        if (file.fileType == AlloyFileType) {
            clientStarter.ensureClientStarted(AlloyLspClientDescriptor(project))
        }
    }
}

/** One server per project, shared by every Alloy file in it. */
class AlloyLspClientDescriptor(project: Project) : ProjectWideLspClientDescriptor(project, "Alloy") {
    override fun isSupportedFile(file: VirtualFile): Boolean = file.fileType == AlloyFileType

    override fun getLanguageId(file: VirtualFile): String = "alloy"

    override fun createCommandLine(): GeneralCommandLine =
        AlloyServerCommandLine.create(
            javaHome = Path.of(System.getProperty("java.home")),
            serverLib = serverLib(),
            preferences = analyzerPreferences(),
            isMac = SystemInfo.isMac,
        ).withWorkDirectory(project.basePath)

    /** Analyzer options for the server; called off the EDT, so solver discovery may be waited for. */
    internal fun analyzerPreferences(): Map<String, String> =
        AlloySettings.getInstance().analyzerPreferences { AlloySolvers.getInstance().ids() }

    override val lsp4jServerClass: Class<out LanguageServer> = AlloyLanguageServerApi::class.java

    override fun createLsp4jClient(handler: LspServerNotificationsHandler): Lsp4jClient =
        AlloyLsp4jClient(handler) { AlloyServerNotifications.publisher(project) }

    override val lspCustomization: LspCustomization = AlloyLspCustomization

    /** A run in progress can never complete once its server is gone. */
    override val lspServerListener: LspServerListener = object : LspServerListener {
        override fun serverStopped(shutdownNormally: Boolean) {
            AlloyRunService.getInstance(project).serverStopped()
        }
    }

    companion object {
        const val PLUGIN_ID = "com.serranofp.alloy-ide"

        fun serverLib(): Path {
            val plugin = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID))
                ?: throw ExecutionException("Plugin $PLUGIN_ID is not loaded")
            return plugin.pluginPath.resolve("lib").resolve("server")
        }
    }
}

/**
 * The plugin offers its own run UI and keyword completion, so the server's Execute lenses and links, its
 * (unimplemented) completion and formatting are turned off. Diagnostics, hover, navigation, references,
 * rename and symbols keep the platform defaults.
 */
object AlloyLspCustomization : LspCustomization() {
    override val codeLensCustomizer: LspCodeLensCustomizer = LspCodeLensDisabled
    override val documentLinkCustomizer: LspDocumentLinkCustomizer = LspDocumentLinkDisabled
    override val completionCustomizer: LspCompletionCustomizer = LspCompletionDisabled
    override val formattingCustomizer: LspFormattingCustomizer = LspFormattingDisabled
}
