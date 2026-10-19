package com.serranofp.alloy.run

import com.intellij.execution.ExecutionException
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.serranofp.alloy.lsp.AlloyExecutionMessage
import com.serranofp.alloy.lsp.AlloyServerNotifications
import com.serranofp.alloy.lsp.CommandsListResult
import java.util.concurrent.CompletableFuture

/**
 * The single Alloy run of a project. The server silently ignores a second `ExecuteAlloyCommand` while it is
 * busy, and its output carries no run id, so at most one run exists and all output goes to it.
 */
@Service(Service.Level.PROJECT)
class AlloyRunService(project: Project) : Disposable {
    private var active: AlloyRunProcessHandler? = null
    private var pendingCommands: CompletableFuture<CommandsListResult>? = null

    init {
        project.messageBus.connect(this).subscribe(AlloyServerNotifications.TOPIC, object : AlloyServerNotifications {
            override fun commandsListed(result: CommandsListResult) = this@AlloyRunService.commandsListed(result)

            override fun executionOutput(message: AlloyExecutionMessage) = this@AlloyRunService.executionOutput(message)
        })
    }

    val activeRun: AlloyRunProcessHandler?
        @Synchronized get() = active

    /** Registers [handler] as the active run, or refuses if another one is still running. */
    @Synchronized
    @Throws(ExecutionException::class)
    fun startRun(handler: AlloyRunProcessHandler) {
        if (active != null) throw ExecutionException(BUSY_MESSAGE)
        active = handler
    }

    @Synchronized
    fun runFinished(handler: AlloyRunProcessHandler) {
        if (active === handler) active = null
    }

    /** Output of the active run; output that arrives with no active run belongs to no one and is dropped. */
    fun executionOutput(message: AlloyExecutionMessage) {
        activeRun?.render(message)
    }

    /** Asks for the commands of a document; the answer is the next `alloy/commandsListResult`. */
    fun requestCommands(connection: AlloyServerConnection, uri: String): CompletableFuture<CommandsListResult> {
        val future = CompletableFuture<CommandsListResult>()
        synchronized(this) {
            pendingCommands?.cancel(false)
            pendingCommands = future
        }
        connection.listCommands(uri)
        return future
    }

    fun commandsListed(result: CommandsListResult) {
        val future = synchronized(this) { pendingCommands.also { pendingCommands = null } }
        future?.complete(result)
    }

    /** The server process stopped: the active run can never complete. */
    fun serverStopped() {
        activeRun?.fail(SERVER_UNAVAILABLE_MESSAGE)
    }

    override fun dispose() {
        activeRun?.terminate(1)
    }

    companion object {
        const val BUSY_MESSAGE = "An Alloy analysis is already running"
        const val SERVER_UNAVAILABLE_MESSAGE = "The Alloy language server is unavailable"

        fun getInstance(project: Project): AlloyRunService = project.service()
    }
}
