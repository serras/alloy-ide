package com.serranofp.alloy.run

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.util.concurrency.AppExecutorUtil
import com.serranofp.alloy.lsp.AlloyExecutionMessage
import com.serranofp.alloy.lsp.AlloyMessageType
import com.serranofp.alloy.run.AlloyRunLog.Outcome
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * An execution of an Alloy command. The analysis runs inside the language server, which streams
 * `alloy/showExecutionOutput` messages that [AlloyRunService] routes here; their interpretation, and the
 * failures found before the analysis starts, are recorded in [results] as the command's result.
 */
class AlloyCommandExecution(
    private val service: AlloyRunService,
    private val results: AlloyCommandResults,
    val file: VirtualFile,
    val command: AlloyCommandRef,
    /** The document and settings this execution analyzes; see [CommandResult]. */
    private val documentStamp: Long,
    private val settingsGeneration: Long,
    private val stopTimeoutMillis: Long = STOP_TIMEOUT_MILLIS,
) {
    /** Set once the server has been found; until then Stop just ends the execution. */
    @Volatile
    var connection: AlloyServerConnection? = null

    val log = AlloyRunLog()

    private val warnings = mutableListOf<String>()
    private var finished = false
    private val done = CountDownLatch(1)

    /** Called by [AlloyRunService.startRun] once this is the active execution. */
    @Synchronized
    fun started() {
        record(CommandState.Running, AlloyRunLog.RUNNING)
    }

    @Synchronized
    fun render(message: AlloyExecutionMessage) {
        if (finished) return
        log.accept(message)
        if (message.type == AlloyMessageType.RunCompleted) {
            val state = when (log.outcome) {
                Outcome.InstanceFound -> CommandState.InstanceFound
                Outcome.CounterexampleFound -> CommandState.CounterexampleFound
                Outcome.NoInstance -> CommandState.NoInstance
                Outcome.NoCounterexample -> CommandState.NoCounterexample
                Outcome.None -> if (log.completionText == AlloyRunLog.STOPPED) CommandState.Stopped else CommandState.Failed
            }
            finish(state, log.summary, link = log.link)
        } else {
            // Running, with the output so far for the tooltip
            record(CommandState.Running, AlloyRunLog.RUNNING)
        }
    }

    /** A warning shown before the analyzer's output, such as the solver fallback. */
    @Synchronized
    fun warn(message: String) {
        warnings += message.trimEnd()
        if (!finished) record(CommandState.Running, AlloyRunLog.RUNNING)
    }

    /** Ends the execution with a short [summary] for the hint and the [details] of the cause. */
    @Synchronized
    fun fail(summary: String, details: String, state: CommandState = CommandState.Failed) {
        if (finished) return
        finish(state, summary, failure = details)
    }

    /** Ends the execution with what the log holds so far, for example when the project closes. */
    @Synchronized
    fun terminate() {
        if (finished) return
        finish(CommandState.Stopped, AlloyRunLog.STOPPED)
    }

    @Synchronized
    fun isFinished(): Boolean = finished

    fun waitFor(timeoutMillis: Long): Boolean = done.await(timeoutMillis, TimeUnit.MILLISECONDS)

    /** Stop: the server aborts the analysis and answers `RunCompleted "Stopped"`, which ends the execution. */
    fun stop() {
        val server = connection
        if (server == null) {
            terminate()
            return
        }
        server.stop()
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            fail(AlloyRunLog.STOPPED, STOP_NOT_CONFIRMED_MESSAGE, CommandState.Stopped)
        }, stopTimeoutMillis, TimeUnit.MILLISECONDS)
    }

    private fun finish(state: CommandState, summary: String, link: String? = null, failure: String? = null) {
        finished = true
        record(state, summary, link, failure)
        service.runFinished(this)
        done.countDown()
    }

    private fun record(state: CommandState, summary: String, link: String? = null, failure: String? = null) {
        val details = CommandDetails(warnings.toList(), log.lines, failure)
        results.record(file, command, CommandResult(state, summary, details, link, documentStamp, settingsGeneration))
    }

    companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L

        const val SERVER_UNAVAILABLE = "Server unavailable"
        const val MODEL_HAS_ERRORS = "Model has errors"
        const val COMMAND_NOT_FOUND = "Command not found"
        const val INTERRUPTED = "Interrupted"

        const val STOP_NOT_CONFIRMED_MESSAGE = "The Alloy language server did not confirm that the analysis stopped"
    }
}
