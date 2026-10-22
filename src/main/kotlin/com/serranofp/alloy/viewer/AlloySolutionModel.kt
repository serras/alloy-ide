package com.serranofp.alloy.viewer

import com.intellij.openapi.diagnostic.thisLogger
import com.serranofp.alloy.AlloyBundle
import edu.mit.csail.sdg.alloy4.Err
import edu.mit.csail.sdg.alloy4viz.AlloyIdeMagic
import edu.mit.csail.sdg.alloy4viz.AlloyInstance
import edu.mit.csail.sdg.alloy4viz.AlloyType
import edu.mit.csail.sdg.alloy4viz.StaticInstanceReader
import edu.mit.csail.sdg.alloy4viz.VizState
import edu.mit.csail.sdg.ast.Func
import edu.mit.csail.sdg.parser.CompModule
import edu.mit.csail.sdg.parser.CompUtil
import edu.mit.csail.sdg.translator.A4Solution
import edu.mit.csail.sdg.translator.A4TupleSet
import kodkod.engine.fol2sat.HigherOrderDeclException
import org.jetbrains.annotations.TestOnly
import java.io.PrintWriter
import java.io.StringReader
import java.io.StringWriter
import java.util.concurrent.ConcurrentHashMap

/**
 * One satisfiable solution as the instance viewer shows it: the state of the trace that is selected, its text
 * and table forms as the analyzer prints them, and the visualizer's theme ([vizState]) for the graph. A first
 * solution gets the magic layout; a solution enumerated from a shown one gets a copy of its [theme], with its
 * projections. Stepping through the trace keeps the theme and projections, and reloads the instance. A trace
 * also shows the [successor] of the selected state, in [nextVizState], a copy of the theme kept in step with it.
 * [module] is the one the analysis parsed, shared by the solutions enumerated from it; null if there is none.
 *
 * Building one writes the whole solution as XML, and a first theme runs the magic layout, so both happen off the
 * EDT (see [warm]), as does the analyzer's work for each state shown (see [prepare]). `A4Solution` is not
 * thread-safe (its Kodkod evaluator keeps an overflow flag and an unsynchronized cache), and the evaluator uses it
 * while the tool window shows it, so every use here locks the solution. Enumerating (`fork`) only reads the
 * solution's translation, so it does not need the lock.
 */
class AlloySolutionModel(val solution: A4Solution, private val theme: VizState? = null, val module: CompModule? = null) {
    /** The solution as the visualizer reads it, written once; each state is parsed from it when first shown. */
    private val xml: String = synchronized(solution) {
        StringWriter().also { solution.writeXML(PrintWriter(it), emptyList<Func>(), null) }.toString()
    }

    private val instances = mutableMapOf<Int, AlloyInstance>()

    /** What the Text and Table tabs show of a state, computed once. */
    private class StateView(val text: String, val diff: TraceDiff?, val table: String, val nextTable: String?)

    private val views = ConcurrentHashMap<Int, StateView>()

    /** Whether the model has mutable relations, so that the solution is a trace (as `VizGUI` decides). */
    val isTemporal: Boolean = solution.maxTrace >= 0

    val traceLength: Int = if (isTemporal) solution.traceLength else 1

    /** The state that follows the last one. */
    val loopState: Int = if (isTemporal) solution.loopState else 0

    /** Written on the EDT, and read by the evaluator's completion off it. */
    @Volatile
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

    /**
     * The second state a trace shows, a copy of [vizState]'s theme with the successor's instance; null for a
     * single state. Projections change both.
     */
    val nextVizState: VizState? by lazy {
        if (hasStates) VizState(vizState).also { it.loadInstance(instance(successor())) } else null
    }

    fun instance(state: Int): AlloyInstance =
        synchronized(instances) { instances.getOrPut(state) { StaticInstanceReader.parseInstance(StringReader(xml), state) } }

    /** Whether there is more than one state to step through. */
    val hasStates: Boolean get() = traceLength > 1

    /** Selects [state], on the EDT, which shows the themes; the analyzer's work is done here unless [prepare] did it. */
    fun goTo(state: Int) {
        require(state in 0 until traceLength) { "No state $state in a trace of $traceLength" }
        this.state = state
        vizState.loadInstance(instance(state))
        nextVizState?.loadInstance(instance(successor(state)))
    }

    /**
     * Does, once, the analyzer's work of showing [state]: parsing its instance and its successor's, and its text,
     * difference and tables. Callable from any thread, so that selecting a state does not do it on the EDT; the
     * themes are left alone, as the EDT may be showing them.
     */
    fun prepare(state: Int) {
        view(state)
        instance(state)
        instance(successor(state))
    }

    /** Whether [prepare] already ran for [state], so that showing it does no analyzer work. */
    fun isPrepared(state: Int): Boolean = views.containsKey(state)

    /**
     * The work of showing a new solution, off the EDT: [prepare] its first state and, for a first solution, run the
     * magic layout. A [theme] is copied on the EDT when first shown, as the EDT may still change it, which is cheap.
     */
    fun warm() {
        prepare(0)
        if (theme == null) nextVizState ?: vizState
    }

    private fun view(state: Int): StateView = views.getOrPut(state) {
        synchronized(solution) {
            val next = successor(state)
            StateView(
                solution.toString(state),
                if (hasStates) TraceDiff.of(solution, state, next) else null,
                solution.format(state),
                if (hasStates) solution.format(next) else null,
            )
        }
    }

    /** The transitions of the lasso, the loop last; none for a static result. The strip has its own, with geometry. */
    @get:TestOnly
    val transitions: List<Transition> get() = if (isTemporal) Transition.lasso(traceLength, loopState) else emptyList()

    /** The state after [state], which after the last one is the loop target. */
    fun successor(state: Int = this.state): Int = if (state + 1 < traceLength) state + 1 else loopState

    /** As the Right key in the trace path strip does, through [goTo]. */
    @TestOnly
    fun next() = goTo(successor())

    /** As the Left key in the trace path strip does, through [goTo]. */
    @TestOnly
    fun previous() {
        if (state > 0) goTo(state - 1)
    }

    /** The caption of the selected state's pane. */
    val caption: String get() = AlloyBundle.message("viewer.state", state.toString())

    /** The caption of the successor's pane, which names the loop target as such; null for a single state. */
    val nextCaption: String?
        get() {
            if (!hasStates) return null
            val next = successor()
            return AlloyBundle.message(if (state + 1 < traceLength) "viewer.state" else "viewer.state.loop", next.toString())
        }

    /** The Text tab: `toString(state)`, as the visualizer's Text mode shows it. */
    val text: String get() = view(state).text

    /** The Text tab of a trace: the difference from the selected state to its successor; null for a single state. */
    val diff: TraceDiff? get() = view(state).diff

    /** The Table tab: `format(state)`, as the visualizer's Table mode shows it. */
    val table: String get() = view(state).table

    /** The successor's table, for the second pane of the Table tab; null for a single state. */
    val nextTable: String? get() = view(state).nextTable

    /** The signatures the visualization can be projected over, including those projected now. */
    val projectableTypes: List<AlloyType>
        get() = vizState.originalModel.types.filter { it in vizState.projectedTypes || vizState.canProject(it) }

    /** The signatures the visualization is projected over, in the visualizer's order; the magic layout may choose some. */
    val projections: List<AlloyType> get() = vizState.projectedTypes.toList()

    /** Projects over [type] too, or no longer over it. */
    fun setProjected(type: AlloyType, projected: Boolean) {
        for (viz in listOfNotNull(vizState, nextVizState)) if (projected) viz.project(type) else viz.deproject(type)
    }

    fun clearProjections() = listOfNotNull(vizState, nextVizState).forEach { it.deprojectAll() }

    /**
     * Evaluates [text] at [state], as the desktop evaluator does: the solution's atoms and skolems become names
     * of the [module], the text is parsed in it, and the solution evaluates the expression. The module's names
     * are those of the analysis, even when the file was edited since. Solutions enumerated from one another
     * share their module, and each evaluation sets its names, so evaluations of one module run one at a time.
     */
    fun evaluate(text: String, state: Int = this.state): EvaluationResult {
        val module = module ?: return EvaluationResult.Error(EVALUATION_UNAVAILABLE)
        // Always the module first, so that no two threads wait for each other's lock
        return synchronized(module) {
            synchronized(solution) {
                try {
                    module.clearGlobals()
                    for (atom in solution.allAtoms) module.addGlobal(atom.label, atom)
                    for (skolem in solution.allSkolems) module.addGlobal(skolem.label, skolem)
                    when (val value = solution.eval(CompUtil.parseOneExpression_fromString(module, text), state)) {
                        is A4TupleSet -> EvaluationResult.Tuples(value.map { it.toString() })
                        is Boolean -> EvaluationResult.Bool(value)
                        else -> EvaluationResult.IntValue(value.toString())
                    }
                } catch (_: HigherOrderDeclException) {
                    EvaluationResult.Error(HIGHER_ORDER)
                } catch (e: Err) {
                    EvaluationResult.Error(e.msg)
                }
            }
        }
    }

    companion object {
        val EVALUATION_UNAVAILABLE: String get() = AlloyBundle.message("viewer.evaluator.unavailable")

        /** The desktop evaluator's message. */
        val HIGHER_ORDER: String get() = AlloyBundle.message("viewer.evaluator.higher.order")
    }
}
