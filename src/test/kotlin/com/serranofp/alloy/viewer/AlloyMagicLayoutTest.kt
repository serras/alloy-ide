package com.serranofp.alloy.viewer

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import edu.mit.csail.sdg.alloy4graph.GraphViewer
import edu.mit.csail.sdg.alloy4viz.AlloyIdeMagic
import edu.mit.csail.sdg.alloy4viz.AlloyProjection
import edu.mit.csail.sdg.alloy4viz.StaticInstanceReader
import edu.mit.csail.sdg.alloy4viz.VizState
import edu.mit.csail.sdg.ast.Func
import java.io.PrintWriter
import java.io.StringReader
import java.io.StringWriter

/** The viewer's use of the visualizer: an instance read from a solution's XML, themed by the magic layout. */
class AlloyMagicLayoutTest : BasePlatformTestCase() {
    private fun vizState(model: String): VizState {
        val xml = StringWriter().also { solve(model).writeXML(PrintWriter(it), emptyList<Func>(), null) }.toString()
        return VizState(StaticInstanceReader.parseInstance(StringReader(xml), 0)).also(AlloyIdeMagic::magic)
    }

    fun testStaticInstance() {
        val state = vizState(STATIC)
        assertTrue(state.originalModel.types.any { it.name == "Node" })
        assertEmpty(state.projectedTypes)
    }

    fun testMagicLayoutProjectsAnOrdering() {
        // The magic layout projects over the ordered signature and hides the ordering's helper relations
        assertEquals(listOf("State"), vizState(ORDERING).projectedTypes.map { it.name })
    }

    fun testEveryStateOfATrace() {
        val model = AlloySolutionModel(solve(LASSO))
        for (state in 0 until model.traceLength) model.goTo(state)
        assertEquals(2, model.state)
    }

    fun testGraphIsBuiltWithoutAFrame() {
        assertInstanceOf(vizState(STATIC).getGraph(null, AlloyProjection()), GraphViewer::class.java)
    }
}
