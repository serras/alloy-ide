package com.serranofp.alloy.run

import com.intellij.openapi.vfs.VirtualFile
import com.serranofp.alloy.analysis.AlloyAnalysis
import com.serranofp.alloy.analysis.AlloyAnalysisEvent
import com.serranofp.alloy.run.AlloyRunLog.Outcome
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * An execution of an Alloy command: an [AlloyAnalysis] inside the IDE, whose events are recorded in
 * [results] as the command's result, together with the failures found before the analysis starts.
 */
class AlloyCommandExecution(
    private val service: AlloyRunService,
    private val results: AlloyCommandResults,
    val file: VirtualFile,
    val command: AlloyCommandRef,
    /** The document and settings this execution analyzes; see [CommandResult]. */
    private val documentStamp: Long,
    private val settingsGeneration: Long,
) {
    val log = AlloyRunLog()

    private val warnings = mutableListOf<String>()
    private var analysis: AlloyAnalysis? = null
    private var finished = false
    private var stopping = false
    private val done = CountDownLatch(1)

    /** Called by [AlloyRunService.startRun] once this is the active execution. */
    @Synchronized
    fun started() {
        record(CommandState.Running, AlloyRunLog.RUNNING)
    }

    /** Starts the analysis, unless the execution already ended (it was stopped while the model was read). */
    @Synchronized
    fun begin(start: () -> AlloyAnalysis) {
        if (finished) return
        analysis = start()
    }

    /** The events of the analysis, one at a time. */
    @Synchronized
    fun accept(event: AlloyAnalysisEvent) {
        if (finished) return
        log.accept(event)
        when (event) {
            is AlloyAnalysisEvent.Sat -> finish(state(), log.summary, link = CommandLink.Solution(event.solution))
            is AlloyAnalysisEvent.Unsat -> finish(state(), log.summary)
            is AlloyAnalysisEvent.Failed -> finish(CommandState.Failed, AlloyRunLog.FAILED, failure = event.failure.message)
            is AlloyAnalysisEvent.Stopped -> finish(CommandState.Stopped, AlloyRunLog.STOPPED)
            // Until the analyzer reaches a point where it can stop, such as the end of the translation
            is AlloyAnalysisEvent.Stopping -> {
                stopping = true
                record(CommandState.Running, AlloyRunLog.STOPPING)
            }
            else -> record(CommandState.Running, if (stopping) AlloyRunLog.STOPPING else AlloyRunLog.RUNNING)
        }
    }

    private fun state(): CommandState = when (log.outcome) {
        Outcome.InstanceFound -> CommandState.InstanceFound
        Outcome.CounterexampleFound -> CommandState.CounterexampleFound
        Outcome.NoInstance -> CommandState.NoInstance
        Outcome.NoCounterexample -> CommandState.NoCounterexample
        Outcome.None -> CommandState.Failed
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
    fun terminate() {
        val running = synchronized(this) {
            if (finished) return
            finish(CommandState.Stopped, AlloyRunLog.STOPPED)
            analysis
        }
        running?.stop()
    }

    @Synchronized
    fun isFinished(): Boolean = finished

    fun waitFor(timeoutMillis: Long): Boolean = done.await(timeoutMillis, TimeUnit.MILLISECONDS)

    /** Stops the analysis, which then reports that it stopped; before it started, ends the execution. */
    fun stop() {
        // Outside this lock: the analysis delivers its Stopping event to accept() while it holds its own
        val running = synchronized(this) {
            if (finished) return
            analysis
        }
        if (running != null) running.stop() else terminate()
    }

    private fun finish(state: CommandState, summary: String, link: CommandLink? = null, failure: String? = null) {
        finished = true
        record(state, summary, link, failure)
        service.runFinished(this)
        done.countDown()
    }

    private fun record(state: CommandState, summary: String, link: CommandLink? = null, failure: String? = null) {
        val details = CommandDetails(warnings.toList(), log.lines, failure)
        results.record(file, command, CommandResult(state, summary, details, link, documentStamp, settingsGeneration))
    }

    companion object {
        const val MODEL_HAS_ERRORS = "Model has errors"
        const val COMMAND_NOT_FOUND = "Command not found"
    }
}
