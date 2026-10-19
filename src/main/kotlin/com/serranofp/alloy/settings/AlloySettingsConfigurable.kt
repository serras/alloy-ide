package com.serranofp.alloy.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent

/** Settings | Languages & Frameworks | Alloy. */
class AlloySettingsConfigurable(
    private val settings: AlloySettings = AlloySettings.getInstance(),
    private val solvers: AlloySolvers = AlloySolvers.getInstance(),
    private val onChanged: () -> Unit = AlloySettingsApplier::settingsChanged,
) : Configurable {
    /** Solver ids; [available] is null while discovery is running. */
    internal val solver = ComboBox<String>()
    private var available: List<AlloySolver>? = null

    internal val skolemDepth = ComboBox(AlloySettingsState.SKOLEM_DEPTHS.toList().toTypedArray())
    internal val preventOverflows = JBCheckBox("Prevent overflows")
    internal val coreMinimization = ComboBox(AlloySettingsState.CORE_MINIMIZATION_LABELS.toTypedArray())
    internal val coreGranularity = ComboBox(AlloySettingsState.CORE_GRANULARITY_LABELS.toTypedArray())
    internal val nonFatalWarnings = JBCheckBox("Allow warnings (non-fatal)")
    internal val recordKodkod = JBCheckBox("Record the Kodkod input/output")
    internal val inferPartialInstance = JBCheckBox("Infer partial instance")

    override fun getDisplayName(): String = "Alloy"

    override fun createComponent(): JComponent {
        solver.renderer = textListCellRenderer { id: String? -> solverLabel(id) }
        val discovery = solvers.load()
        discovery.thenAccept { list ->
            ApplicationManager.getApplication().invokeLater({ solversLoaded(list) }, ModalityState.any())
        }
        discovery.getNow(null)?.let(::solversLoaded)

        return panel {
            row("Solver:") { cell(solver) }
            row("Skolem depth:") { cell(skolemDepth) }
            row { cell(preventOverflows) }
            row("Unsat core minimization:") { cell(coreMinimization) }
            row("Unsat core granularity:") { cell(coreGranularity) }
            row { cell(nonFatalWarnings) }
            row { cell(recordKodkod) }
            row { cell(inferPartialInstance) }
        }.also { reset() }
    }

    /** Fills the solver list, keeping a saved solver that is no longer present so it can be shown as unavailable. */
    internal fun solversLoaded(list: List<AlloySolver>) {
        val selected = solver.selectedItem as String? ?: settings.state.solver
        available = list
        val ids = list.map { it.id }.toMutableList()
        if (selected != null && selected !in ids) ids += selected
        solver.model = DefaultComboBoxModel(ids.toTypedArray())
        solver.selectedItem = selected
    }

    internal fun solverLabel(id: String?): String {
        if (id == null) return ""
        val known = available ?: return "$id (loading solvers...)"
        return known.firstOrNull { it.id == id }?.name ?: "$id (unavailable)"
    }

    override fun isModified(): Boolean = formState() != settings.state

    override fun apply() {
        val changed = isModified
        settings.loadState(formState())
        if (changed) onChanged()
    }

    override fun reset() {
        val state = settings.state
        if ((solver.model as DefaultComboBoxModel<String>).getIndexOf(state.solver) < 0) solver.addItem(state.solver)
        solver.selectedItem = state.solver
        skolemDepth.selectedItem = state.skolemDepth
        preventOverflows.isSelected = state.preventOverflows
        coreMinimization.selectedIndex = state.coreMinimization
        coreGranularity.selectedIndex = state.coreGranularity
        nonFatalWarnings.isSelected = state.nonFatalWarnings
        recordKodkod.isSelected = state.recordKodkod
        inferPartialInstance.isSelected = state.inferPartialInstance
    }

    private fun formState() = AlloySettingsState().also {
        it.solver = solver.selectedItem as String? ?: settings.state.solver
        it.skolemDepth = skolemDepth.selectedItem as Int
        it.preventOverflows = preventOverflows.isSelected
        it.coreMinimization = coreMinimization.selectedIndex
        it.coreGranularity = coreGranularity.selectedIndex
        it.nonFatalWarnings = nonFatalWarnings.isSelected
        it.recordKodkod = recordKodkod.isSelected
        it.inferPartialInstance = inferPartialInstance.isSelected
    }
}
