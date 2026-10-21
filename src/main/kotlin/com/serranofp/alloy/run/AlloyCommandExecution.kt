package com.serranofp.alloy.run

import com.intellij.openapi.vfs.VirtualFile
import com.serranofp.alloy.analysis.AlloyAnalysis
import com.serranofp.alloy.analysis.AlloyAnalysisEvent
import com.serranofp.alloy.run.AlloyRunLog.Outcome
import edu.mit.csail.sdg.ast.Command
import edu.mit.csail.sdg.ast.Sig
import edu.mit.csail.sdg.parser.CompModule
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * An execution of an Alloy command: an [AlloyAnalysis] inside the IDE, whose events are recorded in
 * [results] as the command's result, together with the failures found before the analysis starts.
 *
 * A check without counterexample may go on with its vacuity check (see [AlloyVacuityQueries]): one or two
 * follow-up analyses of the same execution, which keeps the project's analysis until they end.
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
    /** The command's `expect` (-1 without one): from its text at launch, then from the analyzer's command. */
    private var expects = -1
    private val done = CountDownLatch(1)

    /** Starts the analyses of the vacuity check, or null when it is turned off. */
    private var followUp: FollowUp? = null
    private var executing: AlloyAnalysisEvent.Executing? = null

    /** The check's own state, once its vacuity check started. */
    private var checked: CommandState? = null
    private var vacuity: Vacuity? = null
    private var queries: AlloyVacuityQueries? = null

    /** The log of each vacuity query, and the number of the one running (events of earlier ones are ignored). */
    private val vacuityLogs = mutableListOf<AlloyRunLog>()

    /** Starts a follow-up analysis of [command] on [sigs], with the options and parent of the check's. */
    fun interface FollowUp {
        fun start(sigs: Iterable<Sig>, command: Command, listener: (AlloyAnalysisEvent) -> Unit): AlloyAnalysis
    }

    /** Called by [AlloyRunService.startRun] once this is the active execution. */
    @Synchronized
    fun started() {
        record(CommandState.Running, AlloyRunLog.RUNNING)
    }

    /**
     * Starts the analysis, unless the execution already ended (it was stopped while the model was read).
     * With [followUp], a check without counterexample goes on with its vacuity check.
     */
    @Synchronized
    fun begin(start: () -> AlloyAnalysis, followUp: FollowUp? = null) {
        if (finished) return
        this.followUp = followUp
        analysis = start()
    }

    /** The events of the analysis, one at a time. */
    @Synchronized
    fun accept(event: AlloyAnalysisEvent) {
        if (finished || checked != null) return
        log.accept(event)
        if (event is AlloyAnalysisEvent.Executing) {
            expects = event.command.expects
            executing = event
        }
        when (event) {
            is AlloyAnalysisEvent.Sat -> finish(state(), log.summary, link = CommandLink.Solution(event.solution, event.module))
            is AlloyAnalysisEvent.Unsat -> if (!startVacuityCheck()) finish(state(), log.summary)
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

    /**
     * After a check without counterexample, records it with [Vacuity.Checking] and solves its first vacuity
     * query; false if there is none to solve (turned off, a stop was requested, or no parsed module).
     */
    private fun startVacuityCheck(): Boolean {
        val followUp = followUp ?: return false
        val executing = executing ?: return false
        val module = executing.module ?: return false
        if (log.outcome != Outcome.NoCounterexample || stopping) return false
        val queries = AlloyVacuityQueries.of(executing.command, module)
        this.queries = queries
        checked = state()
        vacuity = Vacuity.Checking
        record(CommandState.NoCounterexample, log.summary(vacuity))
        solve(followUp, module.allReachableSigs, queries.withPremise ?: queries.factsOnly)
        return true
    }

    private fun solve(followUp: FollowUp, sigs: Iterable<Sig>, query: Command) {
        val queryLog = AlloyRunLog()
        vacuityLogs += queryLog
        val number = vacuityLogs.size
        val started = followUp.start(sigs, query) { acceptVacuity(number, it) }
        // The follow-up may already have ended, for example if the project closed
        if (!finished) analysis = started
    }

    /** The events of the vacuity query [number]. */
    @Synchronized
    private fun acceptVacuity(number: Int, event: AlloyAnalysisEvent) {
        if (finished || number != vacuityLogs.size) return
        val queryLog = vacuityLogs.last()
        queryLog.accept(event)
        val queries = queries!!
        val state = checked!!
        val premiseQuery = queries.withPremise != null && number == 1
        when (event) {
            is AlloyAnalysisEvent.Sat -> {
                vacuity = if (premiseQuery || queries.withPremise == null) {
                    Vacuity.NotVacuous(queryLog.timeMillis ?: 0, queries.premise?.toString())
                } else {
                    Vacuity.VacuousPremise(queries.premise.toString(), vacuityLogs[0].timeMillis ?: 0, vacuityLogs[0].coreSize ?: 0)
                }
                finish(state, log.summary(vacuity))
            }
            is AlloyAnalysisEvent.Unsat -> if (premiseQuery) {
                record(CommandState.NoCounterexample, log.summary(vacuity))
                solve(followUp!!, executing!!.module!!.allReachableSigs, queries.factsOnly)
            } else {
                vacuity = Vacuity.VacuousFacts(queryLog.timeMillis ?: 0, queryLog.coreSize ?: 0, queries.premise?.toString())
                finish(state, log.summary(vacuity))
            }
            is AlloyAnalysisEvent.Failed -> {
                vacuity = Vacuity.Unknown(event.failure.message)
                finish(state, log.summary(vacuity))
            }
            is AlloyAnalysisEvent.Stopped -> {
                vacuity = Vacuity.Unknown(VACUITY_STOPPED)
                finish(state, log.summary(vacuity))
            }
            else -> record(CommandState.NoCounterexample, log.summary(vacuity))
        }
    }

    private fun state(): CommandState = when (log.outcome) {
        Outcome.InstanceFound -> CommandState.InstanceFound
        Outcome.CounterexampleFound -> CommandState.CounterexampleFound
        Outcome.NoInstance -> CommandState.NoInstance
        Outcome.NoCounterexample -> CommandState.NoCounterexample
        Outcome.None -> CommandState.Failed
    }

    /** The `expect` written in the command, read before the model is analyzed, so that a failure also has one. */
    @Synchronized
    fun expect(expects: Int) {
        this.expects = expects
    }

    private fun expectation(state: CommandState): Expectation? {
        if (expects < 0) return null
        val found = when (state) {
            CommandState.InstanceFound, CommandState.CounterexampleFound -> true
            CommandState.NoInstance, CommandState.NoCounterexample -> false
            CommandState.Failed -> return Expectation.Failed
            CommandState.Running, CommandState.Stopped -> return null
        }
        return if (found == (expects == 1)) Expectation.Passed else Expectation.Failed
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
            val checked = checked
            if (checked == null) {
                finish(CommandState.Stopped, AlloyRunLog.STOPPED)
            } else {
                vacuity = Vacuity.Unknown(VACUITY_STOPPED)
                finish(checked, log.summary(vacuity))
            }
            analysis
        }
        running?.stop()
    }

    @Synchronized
    fun isFinished(): Boolean = finished

    /** The latest step, for the progress indicator: the analyzer's, or that vacuity is being checked. */
    val progress: String?
        @Synchronized get() = if (vacuity == Vacuity.Checking) AlloyRunLog.CHECKING_VACUITY else log.progress

    fun waitFor(timeoutMillis: Long): Boolean = done.await(timeoutMillis, TimeUnit.MILLISECONDS)

    /**
     * Stops the analysis, or the vacuity check's, which then reports that it stopped; before it started, ends
     * the execution.
     */
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
        val details = CommandDetails(warnings.toList(), log.lines, failure, vacuityLogs.flatMap { it.lines })
        results.record(
            file, command, CommandResult(state, summary, details, link, documentStamp, settingsGeneration, expectation(state), vacuity),
        )
    }

    companion object {
        const val MODEL_HAS_ERRORS = "Model has errors"
        const val COMMAND_NOT_FOUND = "Command not found"
        const val VACUITY_STOPPED = "The vacuity check was stopped"
    }
}
