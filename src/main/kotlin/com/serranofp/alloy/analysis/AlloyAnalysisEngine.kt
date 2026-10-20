package com.serranofp.alloy.analysis

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import edu.mit.csail.sdg.alloy4.A4Reporter
import edu.mit.csail.sdg.alloy4.Err
import edu.mit.csail.sdg.alloy4.ErrorFatal
import edu.mit.csail.sdg.alloy4.ErrorWarning
import edu.mit.csail.sdg.ast.Command
import edu.mit.csail.sdg.ast.Sig
import edu.mit.csail.sdg.parser.CompUtil
import edu.mit.csail.sdg.translator.A4Options
import edu.mit.csail.sdg.translator.A4Solution
import edu.mit.csail.sdg.translator.TranslateAlloyToKodkod
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs Alloy analyses inside the IDE, each on its own thread with a large stack: translation recurses deeply,
 * which is why the desktop application gives its worker a large `-Xss` too. See [AlloyAnalysis] for stopping.
 */
@Service(Service.Level.APP)
class AlloyAnalysisEngine {
    /**
     * Starts [body] on a new thread. It must pass the reporter and options it gets to the analyzer, so that
     * the analysis reports its steps and can be stopped. [listener] gets every event, one at a time, on the
     * analysis thread or the thread calling [AlloyAnalysis.stop]. Disposing [parent], such as the project,
     * stops the analysis.
     */
    fun start(
        name: String,
        parent: Disposable,
        options: A4Options,
        listener: (AlloyAnalysisEvent) -> Unit,
        body: (A4Reporter, A4Options) -> A4Solution?,
    ): AlloyAnalysis {
        val analysis = AlloyAnalysis(options, listener)
        if (!Disposer.tryRegister(parent, analysis)) {
            analysis.end(AlloyAnalysisEvent.Stopped(System.currentTimeMillis()))
            return analysis
        }
        val thread = Thread(null, { analysis.run(body) }, "Alloy analysis: $name", STACK_SIZE)
        thread.isDaemon = true
        analysis.thread = thread
        thread.start()
        return analysis
    }

    /** Executes [command], as the desktop application does for one command. */
    fun execute(
        name: String,
        parent: Disposable,
        sigs: Iterable<Sig>,
        command: Command,
        options: A4Options,
        listener: (AlloyAnalysisEvent) -> Unit,
    ): AlloyAnalysis = start(name, parent, options, listener) { reporter, stoppable ->
        (reporter as AlloyAnalysis.Reporter).executing(command)
        TranslateAlloyToKodkod.execute_command(reporter, sigs, command, stoppable)
    }

    /**
     * Parses [contents] (filename to text, see `CompUtil.parseEverything_fromFile`) and executes the command
     * at [commandIndex] of the module [filename], as the desktop application does. The parse is the
     * analysis's own, so that it never shares a module with the editor; its warnings are events.
     */
    fun execute(
        name: String,
        parent: Disposable,
        contents: Map<String, String>,
        filename: String,
        commandIndex: Int,
        options: A4Options,
        listener: (AlloyAnalysisEvent) -> Unit,
    ): AlloyAnalysis = start(name, parent, options, listener) { reporter, stoppable ->
        val module = CompUtil.parseEverything_fromFile(reporter, LinkedHashMap(contents), filename)
        val command = module.allCommands[commandIndex]
        (reporter as AlloyAnalysis.Reporter).executing(command)
        TranslateAlloyToKodkod.execute_command(reporter, module.allReachableSigs, command, stoppable)
    }

    companion object {
        const val STACK_SIZE = 64L * 1024 * 1024

        fun getInstance(): AlloyAnalysisEngine = service()
    }
}

/**
 * One running analysis. [stop] sets a flag that the reporter checks in every analyzer callback, and stops
 * the solver if there is one (see [StoppableSatFactory]). Inside a long translation neither can act, so
 * the analysis is [State.Stopping] until the translation ends and the next callback stops it.
 */
class AlloyAnalysis internal constructor(options: A4Options, private val listener: (AlloyAnalysisEvent) -> Unit) : Disposable {
    enum class State { Running, Stopping, Done }

    /** The factory of the analysis that found a solution being enumerated, or a new one. */
    private val factory = (options.solver as? StoppableSatFactory)?.also { it.rearm() } ?: StoppableSatFactory(options.solver)
    private val options = options.dup().also { it.solver = factory }
    private val done = CountDownLatch(1)

    @Volatile
    internal var thread: Thread? = null

    @Volatile
    private var stopRequested = false

    @get:Synchronized
    var state: State = State.Running
        private set

    /** The last event, once the analysis is [State.Done]. */
    @get:Synchronized
    var end: AlloyAnalysisEvent.End? = null
        private set

    fun stop() {
        synchronized(this) {
            if (state != State.Running) return
            stopRequested = true
            state = State.Stopping
            listener(AlloyAnalysisEvent.Stopping(System.currentTimeMillis()))
        }
        factory.stop()
        thread?.interrupt()
    }

    /** Waits for the analysis to end; true if it did. */
    fun await(timeout: Long, unit: TimeUnit): Boolean = done.await(timeout, unit)

    override fun dispose() = stop()

    internal fun run(body: (A4Reporter, A4Options) -> A4Solution?) {
        val reporter = Reporter()
        val end = try {
            val solution = body(reporter, options)
            when {
                solution == null -> failed(IllegalStateException("The analyzer returned no solution"))
                solution.satisfiable() -> AlloyAnalysisEvent.Sat(solution, reporter.resultTime ?: System.currentTimeMillis())
                else -> AlloyAnalysisEvent.Unsat(solution, solution.highLevelCore(), reporter.resultTime ?: System.currentTimeMillis())
            }
        } catch (e: Throwable) {
            if (stopRequested) AlloyAnalysisEvent.Stopped(System.currentTimeMillis()) else failed(e)
        }
        end(end)
        Disposer.dispose(this)
    }

    internal fun end(event: AlloyAnalysisEvent.End) {
        synchronized(this) {
            if (state == State.Done) return
            state = State.Done
            end = event
            listener(event)
        }
        done.countDown()
    }

    private fun failed(e: Throwable): AlloyAnalysisEvent.Failed {
        val causes = generateSequence(e) { it.cause.takeIf { cause -> cause !== it } }.toList()
        val failure = when {
            causes.any { it is OutOfMemoryError } -> AlloyFailure.MemoryExhausted
            causes.any { it is StackOverflowError } -> AlloyFailure.StackExhausted
            // How execute_command and CompUtil wrap the exceptions they do not expect
            e is ErrorFatal && e.cause != null -> AlloyFailure.UnexpectedException(e.cause!!)
            e is Err -> AlloyFailure.AnalyzerError(e)
            else -> AlloyFailure.UnexpectedException(e)
        }
        return AlloyAnalysisEvent.Failed(failure, System.currentTimeMillis())
    }

    private fun emit(event: AlloyAnalysisEvent) {
        synchronized(this) {
            if (state == State.Done) return
            listener(event)
        }
    }

    /** Turns the analyzer's callbacks into events, and is where a requested stop takes effect. */
    internal inner class Reporter : A4Reporter() {
        @Volatile
        var resultTime: Long? = null

        fun executing(command: Command) {
            checkpoint()
            emit(AlloyAnalysisEvent.Executing(command, now()))
        }

        private fun checkpoint() {
            if (stopRequested) throw AlloyStopped()
        }

        private fun now() = System.currentTimeMillis()

        override fun debug(msg: String?) = checkpoint()
        override fun parse(msg: String?) = checkpoint()
        override fun typecheck(msg: String?) = checkpoint()
        override fun scope(msg: String?) = checkpoint()
        override fun bound(msg: String?) = checkpoint()

        override fun warning(msg: ErrorWarning) {
            checkpoint()
            emit(AlloyAnalysisEvent.Warning(msg, now()))
        }

        override fun translate(solver: String, bitwidth: Int, maxseq: Int, mintrace: Int, maxtrace: Int, skolemDepth: Int, symmetry: Int, strat: String) {
            checkpoint()
            emit(AlloyAnalysisEvent.Translating(solver, bitwidth, maxseq, mintrace, maxtrace, skolemDepth, symmetry, strat, now()))
        }

        override fun solve(plength: Int, primaryVars: Int, totalVars: Int, clauses: Int) {
            checkpoint()
            emit(AlloyAnalysisEvent.Solving(plength, primaryVars, totalVars, clauses, now()))
        }

        override fun minimizing(command: Any?, before: Int) {
            checkpoint()
            emit(AlloyAnalysisEvent.Minimizing(before, now()))
        }

        override fun minimized(command: Any?, before: Int, after: Int) {
            checkpoint()
            emit(AlloyAnalysisEvent.Minimized(before, after, now()))
        }

        override fun resultSAT(command: Any?, solvingTime: Long, solution: Any?) {
            resultTime = now()
            checkpoint()
        }

        override fun resultUNSAT(command: Any?, solvingTime: Long, solution: Any?) {
            resultTime = now()
            checkpoint()
        }
    }
}

/** Thrown from the reporter when a stop was requested; the analyzer wraps it in an `ErrorFatal`. */
private class AlloyStopped : RuntimeException("The analysis was stopped")
