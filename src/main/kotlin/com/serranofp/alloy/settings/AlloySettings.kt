package com.serranofp.alloy.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import edu.mit.csail.sdg.translator.A4Options
import kodkod.engine.satlab.SATFactory
import java.util.concurrent.atomic.AtomicLong

/** The analyzer options, with the defaults of Alloy 6.2.0, and the plugin's own [checkVacuity]. */
class AlloySettingsState : BaseState() {
    var solver by string(DEFAULT_SOLVER)
    var skolemDepth by property(1)
    var preventOverflows by property(true)

    /** 0 = Slow, 1 = Medium, 2 = Fast. */
    var coreMinimization by property(2)

    /** 0 = Top-level, 1 = Flatten once, 2 = Flatten twice, 3 = Expand quantifiers. */
    var coreGranularity by property(0)
    var nonFatalWarnings by property(false)
    var recordKodkod by property(false)
    var inferPartialInstance by property(false)

    /** Whether a check without counterexample goes on to check that it did not pass vacuously. */
    var checkVacuity by property(true)

    companion object {
        const val DEFAULT_SOLVER = "sat4j"
        val SKOLEM_DEPTHS = 0..4
        val CORE_MINIMIZATION_LABELS = listOf("Slow", "Medium", "Fast")
        val CORE_GRANULARITY_LABELS = listOf("Top-level", "Flatten once", "Flatten twice", "Expand quantifiers")
    }
}

/** Application-wide: every project uses the same analyzer options. */
@Service(Service.Level.APP)
@State(name = "AlloySettings", storages = [Storage("alloy.xml")])
class AlloySettings : SimplePersistentStateComponent<AlloySettingsState>(AlloySettingsState()) {
    private val generationCounter = AtomicLong()

    /**
     * Counts the applied changes in this session (it is not persisted): a command result recorded under an
     * older generation is outdated.
     */
    val generation: Long
        get() = generationCounter.get()

    fun nextGeneration() {
        generationCounter.incrementAndGet()
    }

    /**
     * The analyzer options of a run, as the desktop application sets them (`SimpleGUI.doRun`) from its own
     * preferences. A solver that is not [available] is replaced by `sat4j`.
     */
    fun analyzerOptions(available: () -> Collection<String>): A4Options {
        val state = state
        return A4Options().apply {
            solver = AlloySolvers.factory(effectiveSolver(available)) ?: SATFactory.DEFAULT
            skolemDepth = state.skolemDepth.coerceIn(AlloySettingsState.SKOLEM_DEPTHS)
            noOverflow = state.preventOverflows
            coreMinimization = state.coreMinimization.coerceIn(0, 2)
            coreGranularity = state.coreGranularity.coerceIn(0, 3)
            recordKodkod = state.recordKodkod
            inferPartialInstance = state.inferPartialInstance
        }
    }

    /** The saved solver, or `sat4j` when it is not available. `sat4j` is always available. */
    fun effectiveSolver(available: () -> Collection<String>): String {
        val solver = state.solver ?: AlloySettingsState.DEFAULT_SOLVER
        if (solver == AlloySettingsState.DEFAULT_SOLVER) return solver
        return if (solver in available()) solver else AlloySettingsState.DEFAULT_SOLVER
    }

    /** The warning a run prints while the saved solver is replaced by `sat4j`, if it is. */
    fun solverFallbackWarning(available: () -> Collection<String>): String? {
        val solver = state.solver ?: return null
        if (effectiveSolver(available) == solver) return null
        return "The solver $solver is not available, using ${AlloySettingsState.DEFAULT_SOLVER} instead\n"
    }

    companion object {
        fun getInstance(): AlloySettings = service()
    }
}
