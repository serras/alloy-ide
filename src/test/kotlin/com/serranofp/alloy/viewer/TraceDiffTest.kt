package com.serranofp.alloy.viewer

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import edu.mit.csail.sdg.translator.A4Solution

/** A field that changes once: `N1->N1` in state 0, then `N1->N2` from state 1 on, which loops to itself. */
private const val FIELD = "abstract sig N { var f: set N }\none sig N1, N2 extends N {}\n" +
    "fact { f = N1->N1 and after always f = N1->N2 }\nrun {} for 2 but 1..2 steps"

class TraceDiffTest : BasePlatformTestCase() {
    private fun TraceDiff.line(label: String) = lines.single { it.label == label }

    private fun TraceDiff.Line.marked(mark: TraceDiff.Mark) = tuples.filter { it.mark == mark }.map { it.text }
    private val TraceDiff.Line.kept get() = marked(TraceDiff.Mark.KEPT)
    private val TraceDiff.Line.added get() = marked(TraceDiff.Mark.ADDED)
    private val TraceDiff.Line.removed get() = marked(TraceDiff.Mark.REMOVED)

    fun testLinesFollowTheAnalyzersText() {
        for (model in listOf(LASSO, FIELD)) {
            val solution = solve(model)
            for (state in 0 until solution.traceLength) {
                // Against itself, a diff is the analyzer's text for that state
                assertEquals(solution.toString(state), TraceDiff.of(solution, state, state).text)
                val labels = solution.toString(state).lines().filter { it.isNotEmpty() }.map { it.substringBefore("=") }
                assertEquals(labels, TraceDiff.of(solution, state, solution.successorOf(state)).lines.map { it.label })
            }
        }
    }

    private fun A4Solution.successorOf(state: Int) = if (state + 1 < traceLength) state + 1 else loopState

    fun testAddedAndRemovedTuplesOfASignature() {
        val diff = TraceDiff.of(solve(LASSO), 0, 1)
        val cur = diff.line("this/Cur")
        assertEquals(listOf("V1$0"), cur.removed)
        assertEquals(listOf("V2$0"), cur.added)
        assertEmpty(cur.kept)
        assertTrue(cur.isChanged)
        assertEquals("this/Cur={V1$0, V2$0}", cur.text)
    }

    fun testAddedAndRemovedTuplesOfAField() {
        val solution = solve(FIELD)
        assertEquals(2, solution.traceLength)
        val f = TraceDiff.of(solution, 0, 1).line("this/N<:f")
        assertEquals(listOf("N1$0->N1$0"), f.removed)
        assertEquals(listOf("N1$0->N2$0"), f.added)
        // From the last state to the loop target, itself, nothing changes
        val looped = TraceDiff.of(solution, 1, solution.loopState).line("this/N<:f")
        assertFalse(looped.isChanged)
        assertEquals(listOf("N1$0->N2$0"), looped.kept)
    }

    fun testUnchangedRelationsHaveNoMarks() {
        val diff = TraceDiff.of(solve(LASSO), 0, 1)
        val v1 = diff.line("this/V1")
        assertFalse(v1.isChanged)
        assertEquals(listOf("V1$0"), v1.kept)
        assertTrue(v1.tuples.all { it.mark == TraceDiff.Mark.KEPT })
    }

    fun testTheDiffFromTheLastStateGoesToTheLoopTarget() {
        val model = AlloySolutionModel(solve(LASSO))
        model.goTo(2)
        val cur = model.diff!!.line("this/Cur")
        // State 2 has V3, the loop target (state 1) has V2
        assertEquals(listOf("V3$0"), cur.removed)
        assertEquals(listOf("V2$0"), cur.added)
        assertNull(AlloySolutionModel(solve(STATIC)).diff)
    }
}
