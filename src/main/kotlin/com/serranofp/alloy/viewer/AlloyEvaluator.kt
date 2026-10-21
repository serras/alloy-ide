package com.serranofp.alloy.viewer

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.concurrency.annotations.RequiresEdt

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
 * current when it was submitted; [invalidate] starts a new one, and the results of older generations are
 * dropped when they reach the EDT. A Kodkod evaluation cannot be interrupted, so a superseded one runs to
 * completion and is discarded.
 */
class AlloyEvaluatorRunner(parent: Disposable) : Disposable {
    private val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("Alloy evaluator", 1)

    /** Only read and written on the EDT. */
    private var generation = 0L

    @Volatile
    private var disposed = false

    /** How an expression is evaluated at a state; replaced in tests. */
    internal var evaluate: (AlloySolutionModel, String, Int) -> EvaluationResult = { model, text, state -> model.evaluate(text, state) }

    init {
        Disposer.register(parent, this)
    }

    /** Supersedes every request submitted so far: their results will not be delivered. */
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
        val submitted = generation
        val state = model.state
        executor.execute {
            if (disposed) return@execute
            val result = evaluate(model, text, state)
            ApplicationManager.getApplication().invokeLater({
                if (generation == submitted) deliver(result)
            }, ModalityState.any()) { disposed }
        }
    }

    override fun dispose() {
        disposed = true
    }
}
