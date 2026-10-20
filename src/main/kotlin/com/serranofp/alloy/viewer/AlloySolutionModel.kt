package com.serranofp.alloy.viewer

import com.intellij.openapi.diagnostic.thisLogger
import edu.mit.csail.sdg.alloy4viz.AlloyIdeMagic
import edu.mit.csail.sdg.alloy4viz.AlloyInstance
import edu.mit.csail.sdg.alloy4viz.AlloyType
import edu.mit.csail.sdg.alloy4viz.StaticInstanceReader
import edu.mit.csail.sdg.alloy4viz.VizState
import edu.mit.csail.sdg.ast.Func
import edu.mit.csail.sdg.translator.A4Solution
import java.io.PrintWriter
import java.io.StringReader
import java.io.StringWriter

/**
 * One satisfiable solution as the instance viewer shows it: the state of the trace that is selected, its text
 * and table forms as the analyzer prints them, and the visualizer's theme ([vizState]) for the graph. A first
 * solution gets the magic layout; a solution enumerated from a shown one gets a copy of its [theme], with its
 * projections. Stepping through the trace keeps the theme and projections, and reloads the instance.
 */
class AlloySolutionModel(val solution: A4Solution, private val theme: VizState? = null) {
    /** The solution as the visualizer reads it, written once; each state is parsed from it when first shown. */
    private val xml: String = StringWriter().also { solution.writeXML(PrintWriter(it), emptyList<Func>(), null) }.toString()

    private val instances = mutableMapOf<Int, AlloyInstance>()

    /** Whether the model has mutable relations, so that the solution is a trace (as `VizGUI` decides). */
    val isTemporal: Boolean = solution.maxTrace >= 0

    val traceLength: Int = if (isTemporal) solution.traceLength else 1

    /** The state that follows the last one. */
    val loopState: Int = if (isTemporal) solution.loopState else 0

    var state: Int = 0
        private set

    val vizState: VizState by lazy {
        if (theme != null) return@lazy VizState(theme).also { it.loadInstance(instance(0)) }
        VizState(instance(0)).also {
            try {
                AlloyIdeMagic.magic(it)
            } catch (e: Exception) {
                // As in the visualizer, a theme it cannot infer leaves the default one
                thisLogger().warn("The magic layout failed", e)
            }
        }
    }

    fun instance(state: Int): AlloyInstance =
        synchronized(instances) { instances.getOrPut(state) { StaticInstanceReader.parseInstance(StringReader(xml), state) } }

    /** Whether there is more than one state to step through. */
    val hasStates: Boolean get() = traceLength > 1

    fun goTo(state: Int) {
        require(state in 0 until traceLength) { "No state $state in a trace of $traceLength" }
        this.state = state
        vizState.loadInstance(instance(state))
    }

    /** The next state, which after the last one is the loop target. */
    fun next() = goTo(if (state + 1 < traceLength) state + 1 else loopState)

    fun previous() {
        if (state > 0) goTo(state - 1)
    }

    val stepperText: String get() = "State $state of $traceLength"

    val loopText: String get() = "Loops back to state $loopState"

    /** The Text tab: `toString(state)`, as the visualizer's Text mode shows it. */
    val text: String get() = solution.toString(state)

    /** The Table tab: `format(state)`, as the visualizer's Table mode shows it. */
    val table: String get() = solution.format(state)

    /** The signatures the visualization can be projected over, including those projected now. */
    val projectableTypes: List<AlloyType>
        get() = vizState.originalModel.types.filter { it in vizState.projectedTypes || vizState.canProject(it) }

    /** The signatures the visualization is projected over, in the visualizer's order; the magic layout may choose some. */
    val projections: List<AlloyType> get() = vizState.projectedTypes.toList()

    /** Projects over [type] too, or no longer over it. */
    fun setProjected(type: AlloyType, projected: Boolean) {
        if (projected) vizState.project(type) else vizState.deproject(type)
    }

    fun clearProjections() = vizState.deprojectAll()
}
