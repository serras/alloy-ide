package com.serranofp.alloy.run

import com.serranofp.alloy.analysis.AlloyAnalysisEvent

/**
 * The output of one execution, as the hint's tooltip shows it, built from the [AlloyAnalysisEvent]s of an
 * analysis. It keeps the wording of the 6.2.0 language server that the plugin used before, which follows the
 * desktop application's `SimpleReporter`: command, solver line, counts, the result in bold with its time.
 *
 * Events arrive on the analysis thread while the progress indicator reads [progress] from another one,
 * so every member is synchronized and returns a snapshot.
 */
class AlloyRunLog {
    enum class Outcome { InstanceFound, CounterexampleFound, NoInstance, NoCounterexample, None }

    /** A piece of a log line, as one event printed it. */
    data class Span(val text: String, val bold: Boolean = false, val warning: Boolean = false)

    data class Line(val spans: List<Span>) {
        val text: String get() = spans.joinToString("") { it.text }
    }

    /** One span per printed piece; [lines] splits them, since a piece may hold several lines or part of one. */
    private val spans = mutableListOf<Span>()

    /** What the events so far said about the command and the solver, to word the next lines. */
    private var check = false
    private var expects = -1
    private var translatedAt = 0L
    private var solvedAt = 0L
    private var minimizingAt: Long? = null
    private var minimized: AlloyAnalysisEvent.Minimized? = null
    private var solving = SolvingCounts()

    /** What `SimpleReporter.solve` accumulates over the calls for each trace length of a temporal model. */
    private data class SolvingCounts(
        val firstStep: Int = -1,
        val lastStep: Int = -1,
        val configs: Int = 0,
        val primaryVars: Int = 0,
        val totalVars: Int = 0,
        val clauses: Int = 0,
    ) {
        fun add(event: AlloyAnalysisEvent.Solving): SolvingCounts {
            val first = if (firstStep < 0) event.step else firstStep
            return SolvingCounts(
                first, maxOf(lastStep, event.step), if (first == event.step) configs + 1 else configs,
                primaryVars + event.primaryVars, totalVars + event.totalVars, clauses + event.clauses,
            )
        }

        val text: String
            get() = buildString {
                if (configs > 1) append("$configs configs. ")
                if (lastStep > 0) append("$firstStep..$lastStep steps. ")
                if (totalVars >= 0) append("$totalVars vars. ")
                if (primaryVars >= 0) append("$primaryVars primary vars. ")
                if (clauses > 0) append("$clauses clauses. ")
            }
    }

    @get:Synchronized
    var progress: String? = null
        private set

    @get:Synchronized
    var outcome: Outcome = Outcome.None
        private set

    @get:Synchronized
    var unexpected: Boolean = false
        private set

    /** The solving time reported with the outcome. */
    @get:Synchronized
    var timeMillis: Long? = null
        private set

    @get:Synchronized
    var completed: Boolean = false
        private set

    /** How the analysis ended: [COMPLETED], [STOPPED] or [FAILURE]. */
    @get:Synchronized
    var completionText: String? = null
        private set

    @Synchronized
    fun accept(event: AlloyAnalysisEvent) {
        when (event) {
            is AlloyAnalysisEvent.Executing -> {
                check = event.command.check
                expects = event.command.expects
                starting()
                append("Executing \"${event.command}\"\n", bold = true)
                progress = "Executing \"${event.command}\""
            }
            is AlloyAnalysisEvent.Translating -> {
                translatedAt = event.timeMillis
                solving = SolvingCounts()
                val steps = if (event.maxTrace < 1) "" else " Steps=${event.minTrace}..${event.maxTrace}"
                val skolem = if (event.skolemDepth == 0) "" else " SkolemDepth=${event.skolemDepth}"
                val symmetry = if (event.symmetry > 0) "${event.symmetry}" else "OFF"
                append(
                    "   Solver=${event.solver}$steps Bitwidth=${event.bitwidth} MaxSeq=${event.maxSeq}$skolem " +
                        "Symmetry=$symmetry Mode=${event.mode}\n   Generating CNF...\n",
                )
                progress = "Generating CNF..."
            }
            is AlloyAnalysisEvent.Solving -> {
                // Each trace length of a temporal model replaces the counts of the previous one
                if (solvedAt != 0L) repeat(2) { removeLastLine() }
                solving = solving.add(event)
                append("   ${solving.text}${event.timeMillis - translatedAt}ms.\n   Solving...\n")
                solvedAt = event.timeMillis
                progress = "Solving..."
            }
            is AlloyAnalysisEvent.Minimizing -> {
                removeLastLine()
                unsatResult(event.timeMillis - solvedAt, "\n")
                append("   Minimizing the unsat core of ${event.entries} entries...\n")
                minimizingAt = event.timeMillis
                progress = "Minimizing the unsat core of ${event.entries} entries..."
            }
            is AlloyAnalysisEvent.Minimized -> minimized = event
            is AlloyAnalysisEvent.Warning -> {
                // The analyzer warns while it type-checks the model, before it executes the command
                starting()
                spans += Span("Warning ${event.warning.msg.trim()}\n", bold = true, warning = true)
            }
            is AlloyAnalysisEvent.Stopping -> {}
            is AlloyAnalysisEvent.Sat -> {
                val outcome = if (check) "   Counterexample found. Assertion is invalid" else "   Instance found. Predicate is consistent"
                val millis = event.timeMillis - solvedAt
                append("$outcome${expectation(found = true)}. ", bold = true)
                append("${millis}ms.\n\n")
                found(if (check) Outcome.CounterexampleFound else Outcome.InstanceFound, millis, unexpected = expects == 0)
                completed(COMPLETED)
            }
            is AlloyAnalysisEvent.Unsat -> {
                val minimizingAt = minimizingAt
                val millis: Long
                if (minimizingAt == null) {
                    removeLastLine()
                    millis = event.timeMillis - solvedAt
                    unsatResult(millis, "\n\n")
                } else {
                    removeLastLine()
                    millis = minimizingAt - solvedAt
                    val coreMillis = event.timeMillis - minimizingAt
                    val counts = minimized
                    when {
                        event.core.a.isEmpty() && event.core.b.isEmpty() -> append("   No unsat core is available in this case. ${coreMillis}ms.\n\n")
                        counts == null || counts.before <= counts.after ->
                            append("   core contains ${counts?.after ?: event.core.a.size} top-level formulas. ${coreMillis}ms.\n\n")
                        else -> append("   core reduced from ${counts.before} to ${counts.after} top-level formulas. ${coreMillis}ms.\n\n")
                    }
                }
                found(if (check) Outcome.NoCounterexample else Outcome.NoInstance, millis, unexpected = expects == 1)
                completed(COMPLETED)
            }
            is AlloyAnalysisEvent.Failed -> completed(FAILURE)
            is AlloyAnalysisEvent.Stopped -> completed(STOPPED)
        }
    }

    private fun starting() {
        if (spans.isEmpty()) append("Starting the solver...\n\n")
    }

    private fun append(text: String, bold: Boolean = false) {
        spans += Span(text, bold = bold)
    }

    /** "No instance found. Predicate may be inconsistent. 3ms." in bold, but for the time. */
    private fun unsatResult(millis: Long, end: String) {
        val outcome = if (check) "   No counterexample found. Assertion may be valid" else "   No instance found. Predicate may be inconsistent"
        append("$outcome${expectation(found = false)}. ", bold = true)
        append("${millis}ms.$end")
    }

    private fun expectation(found: Boolean): String = when (expects) {
        -1 -> ""
        if (found) 1 else 0 -> ", as expected"
        else -> ", contrary to expectation"
    }

    private fun found(outcome: Outcome, millis: Long, unexpected: Boolean) {
        this.outcome = outcome
        this.unexpected = unexpected
        timeMillis = millis
    }

    private fun completed(text: String) {
        completed = true
        completionText = text
    }

    /**
     * Removes the last line, a progress line that a later step replaces ("Solving...", or the minimizing
     * line). Like the desktop reporter, only that line goes; the statistics before it stay.
     */
    private fun removeLastLine() {
        var skipNewline = spans.lastOrNull()?.text?.endsWith('\n') == true
        while (spans.isNotEmpty()) {
            val last = spans.removeAt(spans.lastIndex)
            val text = if (skipNewline) last.text.dropLast(1) else last.text
            skipNewline = false
            val newline = text.lastIndexOf('\n')
            if (newline >= 0) {
                spans += last.copy(text = text.substring(0, newline + 1))
                return
            }
        }
    }

    /** The log as the console showed it, without the replaced progress lines and the trailing blank lines. */
    val lines: List<Line>
        @Synchronized get() {
            val result = mutableListOf<Line>()
            var current = mutableListOf<Span>()
            for (span in spans) {
                val parts = span.text.split('\n')
                parts.forEachIndexed { index, part ->
                    if (part.isNotEmpty()) current += span.copy(text = part)
                    if (index < parts.lastIndex) {
                        result += Line(current)
                        current = mutableListOf()
                    }
                }
            }
            if (current.isNotEmpty()) result += Line(current)
            return result.dropLastWhile { it.text.isBlank() }
        }

    /** The text of the result hint. */
    val summary: String
        @Synchronized get() {
            val found = when (outcome) {
                Outcome.InstanceFound -> "Instance found"
                Outcome.CounterexampleFound -> "Counterexample found"
                Outcome.NoInstance -> "No instance found"
                Outcome.NoCounterexample -> "No counterexample found"
                Outcome.None -> return when {
                    !completed -> RUNNING
                    completionText == STOPPED -> STOPPED
                    else -> FAILED
                }
            }
            return buildString {
                append(found)
                if (unexpected) append(", contrary to expectation")
                timeMillis?.let { append(" · $it ms") }
            }
        }

    companion object {
        const val RUNNING = "Running…"
        const val STOPPING = "Stopping…"
        const val STOPPED = "Stopped before completion"
        const val FAILED = "Analysis failed"

        /** What [completionText] says when the analysis ended with a result, or with a failure. */
        private const val COMPLETED = ""
        private const val FAILURE = "failure"
    }
}
