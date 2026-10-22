package com.serranofp.alloy.viewer

import edu.mit.csail.sdg.alloy4.Err
import edu.mit.csail.sdg.translator.A4Solution
import edu.mit.csail.sdg.translator.A4TupleSet

/**
 * The tuple-level difference between two states of a trace, one line per signature, each followed by its
 * fields, then one per skolem: the lines, labels and order of `A4Solution.toString(state)`. Tuples are compared
 * by their printed form, in the analyzer's order.
 */
class TraceDiff(val lines: List<Line>) {
    enum class Mark { KEPT, ADDED, REMOVED }

    data class Tuple(val text: String, val mark: Mark)

    /**
     * The relation [label] in both states. [tuples] lists those of the first state, kept or removed, and then
     * those only in the second, added. A value that is not a set of tuples (an integer or boolean skolem) is
     * printed without braces.
     */
    data class Line(val label: String, val tuples: List<Tuple>, val braces: Boolean = true) {
        val isChanged: Boolean get() = tuples.any { it.mark != Mark.KEPT }

        /** The line as the analyzer prints it, with the tuples of both states. */
        val text: String get() = "$label=" + tuples.joinToString(", ", if (braces) "{" else "", if (braces) "}" else "") { it.text }
    }

    /** The lines as text, one per line, as the analyzer prints a single state. */
    val text: String get() = lines.joinToString("") { it.text + "\n" }

    companion object {
        /** The difference from state [a] to state [b] of [solution]. */
        fun of(solution: A4Solution, a: Int, b: Int): TraceDiff {
            val lines = mutableListOf<Line>()
            for (sig in solution.allReachableSigs) {
                lines += line(sig.label, solution.eval(sig, a), solution.eval(sig, b))
                for (field in sig.fields) lines += line("${sig.label}<:${field.label}", solution.eval(field, a), solution.eval(field, b))
            }
            for (skolem in solution.allSkolems) {
                val before = evaluate { solution.eval(skolem, a) }
                val after = evaluate { solution.eval(skolem, b) }
                lines += line("skolem ${skolem.label}", before, after)
            }
            return TraceDiff(lines)
        }

        private fun evaluate(eval: () -> Any?): Any? = try {
            eval()
        } catch (e: Err) {
            "<Evaluator error occurred: $e>"
        }

        private fun line(label: String, before: Any?, after: Any?): Line {
            if (before !is A4TupleSet || after !is A4TupleSet) {
                val a = before.toString()
                val b = after.toString()
                val tuples = if (a == b) listOf(Tuple(a, Mark.KEPT)) else listOf(Tuple(a, Mark.REMOVED), Tuple(b, Mark.ADDED))
                return Line(label, tuples, braces = false)
            }
            val a = before.map { it.toString() }
            val b = after.map { it.toString() }
            val inB = b.toSet()
            val inA = a.toSet()
            val tuples = a.map { Tuple(it, if (it in inB) Mark.KEPT else Mark.REMOVED) } + b.filter { it !in inA }.map { Tuple(it, Mark.ADDED) }
            return Line(label, tuples)
        }
    }
}
