package com.serranofp.alloy.analysis

import edu.mit.csail.sdg.alloy4.Err
import edu.mit.csail.sdg.alloy4.ErrorWarning
import edu.mit.csail.sdg.alloy4.Pair
import edu.mit.csail.sdg.alloy4.Pos
import edu.mit.csail.sdg.ast.Command
import edu.mit.csail.sdg.parser.CompModule
import edu.mit.csail.sdg.translator.A4Solution

/**
 * What an [AlloyAnalysis] reports, in order: [Step]s and [Warning]s while it runs, possibly [Stopping], and
 * exactly one [End]. [timeMillis] is when it happened, in [System.currentTimeMillis] time.
 */
sealed interface AlloyAnalysisEvent {
    val timeMillis: Long

    /** A phase of the analyzer, with what its `A4Reporter` callback carries. */
    sealed interface Step : AlloyAnalysisEvent

    data class Executing(val command: Command, override val timeMillis: Long) : Step

    data class Translating(
        val solver: String,
        val bitwidth: Int,
        val maxSeq: Int,
        val minTrace: Int,
        val maxTrace: Int,
        val skolemDepth: Int,
        val symmetry: Int,
        val mode: String,
        override val timeMillis: Long,
    ) : Step

    /** Called once per trace length in temporal models; the counts are those of this call only. */
    data class Solving(val step: Int, val primaryVars: Int, val totalVars: Int, val clauses: Int, override val timeMillis: Long) : Step

    /** No solution was found, and the unsat core of [entries] entries is being minimized. */
    data class Minimizing(val entries: Int, override val timeMillis: Long) : Step

    /** The unsat core went from [before] to [after] top-level formulas. */
    data class Minimized(val before: Int, val after: Int, override val timeMillis: Long) : Step

    data class Warning(val warning: ErrorWarning, override val timeMillis: Long) : AlloyAnalysisEvent

    /** Stop was requested, and the analyzer has not reached a point where it can stop yet. */
    data class Stopping(override val timeMillis: Long) : AlloyAnalysisEvent

    sealed interface End : AlloyAnalysisEvent

    /**
     * [module] is the one the analysis parsed, where the solution's names are declared; null when the analysis
     * was given its sigs rather than a model to parse. [timeMillis] is when the analyzer reported the result,
     * before the solution was handed back.
     */
    data class Sat(val solution: A4Solution, val module: CompModule?, override val timeMillis: Long) : End

    /** [core] is `A4Solution.highLevelCore()`: empty when no core is available. */
    data class Unsat(val solution: A4Solution, val core: Pair<Set<Pos>, Set<Pos>>, override val timeMillis: Long) : End

    data class Failed(val failure: AlloyFailure, override val timeMillis: Long) : End

    data class Stopped(override val timeMillis: Long) : End
}

/** Why an analysis ended without a result. */
sealed interface AlloyFailure {
    val message: String

    data object MemoryExhausted : AlloyFailure {
        override val message = "The analysis ran out of memory"
    }

    data object StackExhausted : AlloyFailure {
        override val message = "The analysis ran out of stack space"
    }

    /** An error the analyzer reports about the model, such as a higher-order quantifier it cannot skolemize. */
    data class AnalyzerError(val error: Err) : AlloyFailure {
        override val message: String get() = error.msg
    }

    data class UnexpectedException(val cause: Throwable) : AlloyFailure {
        override val message: String get() = cause.message ?: cause.toString()
    }
}
