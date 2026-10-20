package com.serranofp.alloy.run

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.serranofp.alloy.lsp.AlloyExecutionMessage
import com.serranofp.alloy.lsp.AlloyServerNotifications
import com.serranofp.alloy.lsp.CommandsListResult
import java.util.concurrent.CompletableFuture

/**
 * The single Alloy execution of a project. The server silently ignores a second `ExecuteAlloyCommand` while it
 * is busy, and its output carries no run id, so at most one execution exists and all output goes to it.
 */
@Service(Service.Level.PROJECT)
class AlloyRunService(project: Project) : Disposable {
    private var active: AlloyCommandExecution? = null
    private var pendingCommands: CompletableFuture<CommandsListResult>? = null

    init {
        project.messageBus.connect(this).subscribe(AlloyServerNotifications.TOPIC, object : AlloyServerNotifications {
            override fun commandsListed(result: CommandsListResult) = this@AlloyRunService.commandsListed(result)

            override fun executionOutput(message: AlloyExecutionMessage) = this@AlloyRunService.executionOutput(message)
        })
    }

    val activeExecution: AlloyCommandExecution?
        @Synchronized get() = active

    /**
     * Registers [execution] as the active one and records it as running, or refuses (returning false, with
     * the results untouched) if another one is still running.
     */
    fun startRun(execution: AlloyCommandExecution): Boolean {
        synchronized(this) {
            if (active != null) return false
            active = execution
        }
        execution.started()
        return true
    }

    @Synchronized
    fun runFinished(execution: AlloyCommandExecution) {
        if (active === execution) active = null
    }

    /** Output of the active execution; output that arrives with none active belongs to no one and is dropped. */
    fun executionOutput(message: AlloyExecutionMessage) {
        activeExecution?.render(message)
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

    /** The server process stopped: the active execution can never complete. */
    fun serverStopped() {
        activeExecution?.fail(AlloyCommandExecution.SERVER_UNAVAILABLE, SERVER_UNAVAILABLE_MESSAGE)
    }

    override fun dispose() {
        activeExecution?.terminate()
    }

    companion object {
        const val BUSY_MESSAGE = "An Alloy analysis is already running"
        const val SERVER_UNAVAILABLE_MESSAGE = "The Alloy language server is unavailable"

        fun getInstance(project: Project): AlloyRunService = project.service()
    }
}
