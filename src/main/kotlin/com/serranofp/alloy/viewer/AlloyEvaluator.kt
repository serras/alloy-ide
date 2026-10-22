package com.serranofp.alloy.viewer

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.serranofp.alloy.AlloyBundle
import com.serranofp.alloy.analysis.AlloyAnalysisEngine
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** The value of an expression evaluated in a solution, as the desktop evaluator returns it, or why there is none. */
sealed interface EvaluationResult {
    /** A set of tuples, each printed as the analyzer prints it (`Node$0->Node$1`), in the analyzer's order. */
    data class Tuples(val tuples: List<String>) : EvaluationResult

    data class Bool(val value: Boolean) : EvaluationResult

    /** An integer as the analyzer prints it, with ` (OF)` when it overflowed. */
    data class IntValue(val value: String) : EvaluationResult

    data class Error(val message: String) : EvaluationResult
}

/**
 * Runs the evaluations of one tool window off the EDT, one at a time. Each request belongs to the generation
 * current when it was submitted; [invalidate] starts a new one. A request of an older generation is skipped when
 * its turn comes, and its result is dropped if it was already running: a Kodkod evaluation cannot be interrupted,
 * so a superseded one runs to completion and is discarded. Every request delivers a result, an error if the
 * evaluation threw, so that no row says it is evaluating forever.
 *
 * Evaluating recurses over the expression, as translating does, so the thread has the analysis engine's large stack.
 */
class AlloyEvaluatorRunner(parent: Disposable) : Disposable {
    private val executor = ThreadPoolExecutor(1, 1, IDLE_SECONDS, TimeUnit.SECONDS, LinkedBlockingQueue()) { task ->
        Thread(null, task, "Alloy evaluator", AlloyAnalysisEngine.STACK_SIZE).apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }

    /** Written on the EDT, read by the evaluator thread to skip superseded requests. */
    @Volatile
    private var generation = 0L

    @Volatile
    private var disposed = false

    /** How an expression is evaluated at a state; replaced in tests. */
    internal var evaluate: (AlloySolutionModel, String, Int) -> EvaluationResult = { model, text, state -> model.evaluate(text, state) }

    init {
        Disposer.register(parent, this)
    }

    /** Supersedes every request submitted so far: those still waiting are skipped, and no result is delivered. */
    @RequiresEdt
    fun invalidate() {
        generation++
    }

    /**
     * Evaluates [text] in [model] at its selected state, and calls [deliver] on the EDT with the result,
     * unless [invalidate] was called meanwhile.
     */
    @RequiresEdt
    fun submit(model: AlloySolutionModel, text: String, deliver: (EvaluationResult) -> Unit) {
        // The executor no longer takes requests
        if (disposed) return
        val submitted = generation
        val state = model.state
        executor.execute {
            if (disposed || generation != submitted) return@execute
            val result = try {
                evaluate(model, text, state)
            } catch (e: Throwable) {
                if (e is ControlFlowException) throw e
                // Such as a stack overflow on a huge expression; the analyzer's own errors are results already
                thisLogger().warn("Evaluating $text failed", e)
                EvaluationResult.Error(AlloyBundle.message("viewer.evaluator.failed", e.message ?: e.javaClass.simpleName))
            }
            ApplicationManager.getApplication().invokeLater({
                if (generation == submitted) deliver(result)
            }, ModalityState.any()) { disposed }
        }
    }

    override fun dispose() {
        disposed = true
        executor.shutdown()
    }

    private companion object {
        /** How long the thread waits for a new request before it ends; the next request starts another one. */
        const val IDLE_SECONDS = 30L
    }
}
