package com.serranofp.alloy.lsp

import com.intellij.platform.lsp.api.Lsp4jClient
import com.intellij.platform.lsp.api.LspServerNotificationsHandler
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification

/** Handles the standard LSP notifications through the platform, plus the two `alloy/` ones. */
class AlloyLsp4jClient(
    handler: LspServerNotificationsHandler,
    private val notifications: () -> AlloyServerNotifications,
) : Lsp4jClient(handler) {
    @JsonNotification("alloy/commandsListResult")
    fun commandsListResult(result: CommandsListResult) {
        notifications().commandsListed(result)
    }

    @JsonNotification("alloy/showExecutionOutput")
    fun showExecutionOutput(message: AlloyExecutionMessage) {
        notifications().executionOutput(message)
    }
}
