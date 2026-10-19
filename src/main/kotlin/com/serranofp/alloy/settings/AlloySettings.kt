package com.serranofp.alloy.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

/** The analyzer options, with the defaults of Alloy 6.2.0. */
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
    /**
     * The options as `A4Preferences` id to value, in its encoding: choices as their integer, booleans as `y`/`n`,
     * and the solver as its `SATFactory` id. A solver that is not [available] is replaced by `sat4j`.
     */
    fun analyzerPreferences(available: () -> Collection<String>): Map<String, String> {
        val state = state
        return mapOf(
            "SatSolver2" to effectiveSolver(available),
            "SkolemDepth3" to state.skolemDepth.coerceIn(AlloySettingsState.SKOLEM_DEPTHS).toString(),
            "NoOverflow" to yesNo(state.preventOverflows),
            "CoreMinimization" to state.coreMinimization.coerceIn(0, 2).toString(),
            "CoreGranularity" to state.coreGranularity.coerceIn(0, 3).toString(),
            "WarningNonfatal" to yesNo(state.nonFatalWarnings),
            "RecordKodkod" to yesNo(state.recordKodkod),
            "InferPartialInstance" to yesNo(state.inferPartialInstance),
        )
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

    private fun yesNo(value: Boolean) = if (value) "y" else "n"

    companion object {
        fun getInstance(): AlloySettings = service()
    }
}
