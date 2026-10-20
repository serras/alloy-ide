package com.serranofp.alloy.run

import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.serranofp.alloy.lsp.AlloyExecutionMessage
import com.serranofp.alloy.lsp.AlloyServerNotifications
import com.serranofp.alloy.lsp.CommandsListResult
import com.serranofp.alloy.settings.AlloySettings
import org.jetbrains.annotations.TestOnly
import java.util.concurrent.CompletableFuture

/**
 * The single Alloy execution of a project. The server silently ignores a second `ExecuteAlloyCommand` while it
 * is busy, and its output carries no run id, so at most one execution exists and all output goes to it.
 */
@Service(Service.Level.PROJECT)
class AlloyRunService(private val project: Project) : Disposable {
    private var active: AlloyCommandExecution? = null
    private var pendingCommands: CompletableFuture<CommandsListResult>? = null

    init {
        project.messageBus.connect(this).subscribe(AlloyServerNotifications.TOPIC, object : AlloyServerNotifications {
            override fun commandsListed(result: CommandsListResult) = this@AlloyRunService.commandsListed(result)

            override fun executionOutput(message: AlloyExecutionMessage) = this@AlloyRunService.executionOutput(message)
        })
    }

    /** Blocks until the server is running, or gives up with null. */
    @set:TestOnly
    var connectionProvider: () -> AlloyServerConnection? = ::awaitConnection

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

    /**
     * Executes [command] of [file], open in [editor], as a cancellable background task. If another execution
     * is running, the editor says so and the results are untouched.
     */
    fun launch(
        file: VirtualFile,
        command: AlloyCommandRef,
        editor: Editor,
        connection: () -> AlloyServerConnection? = connectionProvider,
        launcher: AlloyCommandLauncher = AlloyCommandLauncher(this),
    ): Boolean {
        val results = AlloyCommandResults.getInstance(project)
        val execution = AlloyCommandExecution(
            this,
            results,
            file,
            command,
            results.stamp(editor.document),
            AlloySettings.getInstance().generation,
        )
        if (!startRun(execution)) {
            HintManager.getInstance().showInformationHint(editor, BUSY_MESSAGE)
            return false
        }
        // The file is open in the editor, so the server already analyzes its current text
        ProgressManager.getInstance().run(AlloyCommandTask(project, execution, launcher, connection))
        return true
    }

    /** A server that is still starting (for example, because the file was just opened) is waited for. */
    private fun awaitConnection(): AlloyServerConnection? {
        val deadline = System.currentTimeMillis() + SERVER_START_TIMEOUT_MILLIS
        while (true) {
            LspAlloyServerConnection.find(project)?.let { return it }
            if (System.currentTimeMillis() > deadline) return null
            Thread.sleep(100)
        }
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
        const val SERVER_START_TIMEOUT_MILLIS = 15_000L

        fun getInstance(project: Project): AlloyRunService = project.service()
    }
}
