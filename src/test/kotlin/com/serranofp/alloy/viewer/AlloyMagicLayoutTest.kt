package com.serranofp.alloy.viewer

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import edu.mit.csail.sdg.alloy4graph.GraphViewer
import edu.mit.csail.sdg.alloy4viz.AlloyProjection

/** The viewer's use of the visualizer: an instance read from a solution's XML, themed by the magic layout. */
class AlloyMagicLayoutTest : BasePlatformTestCase() {
    private fun vizState(model: String) = AlloySolutionModel(solve(model)).vizState

    fun testStaticInstance() {
        val state = vizState(STATIC)
        assertTrue(state.originalModel.types.any { it.name == "Node" })
        assertEmpty(state.projectedTypes)
    }

    fun testMagicLayoutProjectsAnOrdering() {
        // The magic layout projects over the ordered signature and hides the ordering's helper relations
        assertEquals(listOf("State"), vizState(ORDERING).projectedTypes.map { it.name })
    }

    /**
     * Each state's instance is read from the XML for that state: `Cur` is the atom the analyzer gives it there
     * (`V1$0`, which the visualizer names `V1`, as the only atom of its signature).
     */
    fun testEveryStateOfATrace() {
        val model = AlloySolutionModel(solve(LASSO))
        val cur = model.solution.allReachableSigs.single { it.label == "this/Cur" }
        for (state in 0 until model.traceLength) {
            model.goTo(state)
            val instance = model.vizState.originalInstance
            assertSame(model.instance(state), instance)
            val set = instance.model.sets.single { it.name == "Cur" }
            val expected = model.solution.eval(cur, state).map { it.atom(0).substringBefore('$') }
            assertEquals(listOf("V${state + 1}"), expected)
            assertEquals(expected, instance.set2atoms(set).map { it.toString() })
        }
    }

    fun testGraphIsBuiltWithoutAFrame() {
        assertInstanceOf(vizState(STATIC).getGraph(null, AlloyProjection()), GraphViewer::class.java)
    }
}
