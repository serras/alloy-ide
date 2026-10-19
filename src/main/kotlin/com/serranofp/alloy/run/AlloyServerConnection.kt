package com.serranofp.alloy.run

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspServerState
import com.serranofp.alloy.lsp.AlloyLanguageServerApi
import com.serranofp.alloy.lsp.AlloyLspIntegrationProvider
import com.serranofp.alloy.lsp.executeAlloyCommand
import com.serranofp.alloy.lsp.listAlloyCommands
import com.serranofp.alloy.lsp.openModel
import com.serranofp.alloy.lsp.stopExecution

/** The custom requests a run needs; answers arrive as notifications through [AlloyRunService]. */
interface AlloyServerConnection {
    fun uriOf(file: VirtualFile): String

    fun listCommands(uri: String)

    fun execute(arguments: List<Any?>)

    fun stop()

    fun openModel(link: String)
}

class LspAlloyServerConnection(private val client: LspClient) : AlloyServerConnection {
    override fun uriOf(file: VirtualFile): String = client.getDocumentIdentifier(file).uri

    override fun listCommands(uri: String) = send { it.listAlloyCommands(uri) }

    override fun execute(arguments: List<Any?>) = send { it.executeAlloyCommand(arguments) }

    override fun stop() = send { it.stopExecution() }

    override fun openModel(link: String) = send { it.openModel(link) }

    /** The requests answer immediately with nothing, so there is nothing to wait for. */
    private fun send(request: (AlloyLanguageServerApi) -> Unit) {
        client.sendNotification { request(it as AlloyLanguageServerApi) }
    }

    companion object {
        fun find(project: Project): AlloyServerConnection? =
            LspClientManager.getInstance(project).getClients(AlloyLspIntegrationProvider::class.java)
                .firstOrNull { it.state == LspServerState.Running }
                ?.let(::LspAlloyServerConnection)

        fun isStarting(project: Project): Boolean =
            LspClientManager.getInstance(project).getClients(AlloyLspIntegrationProvider::class.java)
                .any { it.state == LspServerState.Initializing }
    }
}
