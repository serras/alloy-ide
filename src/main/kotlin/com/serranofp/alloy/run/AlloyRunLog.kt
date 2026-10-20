package com.serranofp.alloy.run

import com.serranofp.alloy.lsp.AlloyExecutionMessage
import com.serranofp.alloy.lsp.AlloyMessageType

/**
 * Interprets the `alloy/showExecutionOutput` messages of one execution. The protocol carries no structured
 * outcome, so the outcome is recognized from the texts of the 6.2.0 server
 * (`AlloyTextDocumentService.solverCallbackMsgToAlloyMsg`).
 *
 * Messages arrive on the server's thread while the progress indicator reads [progress] from another one,
 * so every member is synchronized and returns a snapshot.
 */
class AlloyRunLog {
    enum class Outcome { InstanceFound, CounterexampleFound, NoInstance, NoCounterexample, None }

    /** A piece of a log line, as one message printed it. */
    data class Span(val text: String, val bold: Boolean = false, val warning: Boolean = false)

    data class Line(val spans: List<Span>) {
        val text: String get() = spans.joinToString("") { it.text }
    }

    /** One span per message; [lines] splits them, since a message may hold several lines or part of one. */
    private val spans = mutableListOf<Span>()
    private var outcomeSeen = false

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

    /** What `OpenModel` opens for this result: `XML: <file>` or `CORE: <file>`. */
    @get:Synchronized
    var link: String? = null
        private set

    @get:Synchronized
    var completed: Boolean = false
        private set

    /** The text of `RunCompleted`: empty, `Stopped` or `failure`. */
    @get:Synchronized
    var completionText: String? = null
        private set

    @Synchronized
    fun accept(message: AlloyExecutionMessage) {
        val text = message.message.orEmpty()
        val type = message.type
        if (message.replaceLast) removeLastLine()
        if (text.isNotEmpty() && type != AlloyMessageType.RunCompleted) {
            spans += Span(text, bold = message.bold, warning = type == AlloyMessageType.Warning)
        }
        when (type) {
            AlloyMessageType.RunInProgress -> text.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }?.let { progress = it }
            AlloyMessageType.RunResult -> result(text, message.link)
            AlloyMessageType.RunCompleted -> {
                completed = true
                completionText = text
            }
            else -> {}
        }
    }

    /**
     * The server sets replaceLast on the message that replaces a progress line: "Writing the XML file..."
     * (no newline), "Solving..." or the "No counterexample found ... 3ms." line printed while minimizing.
     * Like the desktop reporter, only that line is removed; the statistics before it stay.
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

    private fun result(text: String, link: String?) {
        val found = when {
            "No counterexample found" in text -> Outcome.NoCounterexample
            "No instance found" in text -> Outcome.NoInstance
            link?.startsWith(XML_PREFIX) == true && "Counterexample found" in text -> Outcome.CounterexampleFound
            link?.startsWith(XML_PREFIX) == true && "Instance found" in text -> Outcome.InstanceFound
            else -> null
        }
        if (found != null) {
            outcome = found
            outcomeSeen = true
            unexpected = "contrary to expectation" in text
        }
        if (link != null) {
            when {
                link.startsWith(XML_PREFIX) || link.startsWith(CORE_PREFIX) -> this.link = link
                // The server sends the core as a bare path, but its OpenModel only opens "CORE: <file>"
                OTHER_PREFIXES.none(link::startsWith) -> this.link = CORE_PREFIX + link
            }
        }
        // The time is in the outcome message itself (with core minimization) or in the one after it
        if (outcomeSeen && timeMillis == null) {
            TIME.find(text)?.let { timeMillis = it.groupValues[1].toLong() }
        }
    }

    /** The log as the console showed it, without the replaced messages and the trailing blank lines. */
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
        const val STOPPED = "Stopped"
        const val FAILED = "Analysis failed"

        const val XML_PREFIX = "XML: "
        const val CORE_PREFIX = "CORE: "
        private val OTHER_PREFIXES = listOf("POS: ", "CNF: ")
        private val TIME = Regex("""(\d+)ms\.""")
    }
}
