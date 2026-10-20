package com.serranofp.alloy.run

import com.serranofp.alloy.lsp.CommandsListResultItem
import com.serranofp.alloy.settings.AlloySettings
import com.serranofp.alloy.settings.AlloySolvers
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Starts a command on the server: lists the commands of the file, picks [command] by label (or position),
 * and executes it with the server's own `[uri, index, line, character]`, so that its positions never have
 * to be reproduced from the native PSI.
 */
class AlloyCommandLauncher(
    private val service: AlloyRunService,
    private val listTimeoutMillis: Long = LIST_TIMEOUT_MILLIS,
    /** Set while the saved solver is unavailable and the server runs with `sat4j` instead. */
    private val solverWarning: () -> String? = {
        AlloySettings.getInstance().solverFallbackWarning { AlloySolvers.getInstance().ids() }
    },
) {
    /** Runs on a background thread; every failure ends [execution] with a summary and its cause. */
    fun launch(
        execution: AlloyCommandExecution,
        connection: AlloyServerConnection?,
        uri: String?,
        fileName: String,
        command: AlloyCommandRef,
    ) {
        solverWarning()?.let(execution::warn)
        if (connection == null || uri == null) {
            execution.fail(AlloyCommandExecution.SERVER_UNAVAILABLE, AlloyRunService.SERVER_UNAVAILABLE_MESSAGE)
            return
        }
        execution.connection = connection

        val commands = try {
            service.requestCommands(connection, uri).get(listTimeoutMillis, TimeUnit.MILLISECONDS).commands
        } catch (_: TimeoutException) {
            execution.fail(AlloyRunLog.FAILED, "The Alloy language server did not list the commands of $fileName in time")
            return
        } catch (e: ExecutionException) {
            execution.fail(AlloyRunLog.FAILED, "Could not list the commands of $fileName: ${e.cause?.message ?: e.message}")
            return
        }
        if (execution.isFinished()) return

        if (commands.isEmpty()) {
            // The server lists no commands when the module does not parse or type-check
            execution.fail(AlloyCommandExecution.MODEL_HAS_ERRORS, "$fileName has errors, see the problems reported in the editor")
            return
        }
        val item = select(commands, command)
        val arguments = item?.command?.arguments
        if (arguments == null) {
            execution.fail(AlloyCommandExecution.COMMAND_NOT_FOUND, "Command ${command.displayName} was not found in $fileName")
            return
        }
        connection.execute(arguments)
    }

    companion object {
        const val LIST_TIMEOUT_MILLIS = 10_000L

        fun select(commands: List<CommandsListResultItem>, command: AlloyCommandRef): CommandsListResultItem? =
            if (command.label != null) {
                commands.firstOrNull { item ->
                    AlloyCommands.labelOfServerTitle(item.title)?.let { AlloyCommands.sameLabel(it, command.label) } == true
                }
            } else {
                commands.getOrNull(command.index)
            }
    }
}
