package com.serranofp.alloy.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.util.CheckedDisposable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.serranofp.alloy.AlloyBundle
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

    /** Disposed with the page's UI, which a late end of solver discovery must not fill then. */
    private var ui: CheckedDisposable? = null

    internal val skolemDepth = ComboBox(AlloySettingsState.SKOLEM_DEPTHS.toList().toTypedArray())
    internal val preventOverflows = JBCheckBox(AlloyBundle.message("settings.prevent.overflows"))
    internal val coreMinimization = ComboBox(AlloySettingsState.CORE_MINIMIZATION_LABELS.toTypedArray())
    internal val coreGranularity = ComboBox(AlloySettingsState.CORE_GRANULARITY_LABELS.toTypedArray())
    internal val nonFatalWarnings = JBCheckBox(AlloyBundle.message("settings.non.fatal.warnings"))
    internal val recordKodkod = JBCheckBox(AlloyBundle.message("settings.record.kodkod"))
    internal val inferPartialInstance = JBCheckBox(AlloyBundle.message("settings.infer.partial.instance"))
    internal val checkVacuity = JBCheckBox(AlloyBundle.message("settings.check.vacuity"))

    override fun getDisplayName(): String = "Alloy"

    override fun createComponent(): JComponent {
        solver.renderer = textListCellRenderer { id: String? -> solverLabel(id) }
        val discovery = solvers.load()
        val loaded = discovery.getNow(null)
        if (loaded != null) {
            solversLoaded(loaded)
        } else {
            // The page can be closed before discovery ends
            val page = Disposer.newCheckedDisposable().also { ui = it }
            discovery.thenAccept { list ->
                ApplicationManager.getApplication().invokeLater({ solversLoaded(list) }, ModalityState.any()) { page.isDisposed }
            }
        }

        return panel {
            row(AlloyBundle.message("settings.solver")) { cell(solver) }
            row(AlloyBundle.message("settings.skolem.depth")) { cell(skolemDepth) }
            row { cell(preventOverflows) }
            row(AlloyBundle.message("settings.core.minimization")) { cell(coreMinimization) }
            row(AlloyBundle.message("settings.core.granularity")) { cell(coreGranularity) }
            row { cell(nonFatalWarnings) }
            row { cell(recordKodkod) }
            row { cell(inferPartialInstance) }
            separator()
            row { cell(checkVacuity) }
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
        val known = available ?: return AlloyBundle.message("settings.solver.loading", id)
        return known.firstOrNull { it.id == id }?.name ?: AlloyBundle.message("settings.solver.unavailable", id)
    }

    override fun disposeUIResources() {
        ui?.let(Disposer::dispose)
        ui = null
    }

    override fun isModified(): Boolean = formState() != clamped(settings.state)

    override fun apply() {
        val changed = isModified
        settings.loadState(formState())
        if (changed) onChanged()
    }

    override fun reset() {
        val state = clamped(settings.state)
        val saved = state.solver
        if (saved != null && (solver.model as DefaultComboBoxModel<String>).getIndexOf(saved) < 0) solver.addItem(saved)
        solver.selectedItem = saved
        skolemDepth.selectedItem = state.skolemDepth
        preventOverflows.isSelected = state.preventOverflows
        coreMinimization.selectedIndex = state.coreMinimization
        coreGranularity.selectedIndex = state.coreGranularity
        nonFatalWarnings.isSelected = state.nonFatalWarnings
        recordKodkod.isSelected = state.recordKodkod
        inferPartialInstance.isSelected = state.inferPartialInstance
        checkVacuity.isSelected = state.checkVacuity
    }

    /**
     * [state] with its numbers in the ranges the page offers: a hand-edited or older alloy.xml can hold any
     * number, and the analyzer gets them clamped too. The page shows these values, and is not modified by
     * showing them.
     */
    private fun clamped(state: AlloySettingsState) = AlloySettingsState().also {
        it.copyFrom(state)
        it.skolemDepth = state.skolemDepth.coerceIn(AlloySettingsState.SKOLEM_DEPTHS)
        it.coreMinimization = state.coreMinimization.coerceIn(0, coreMinimization.itemCount - 1)
        it.coreGranularity = state.coreGranularity.coerceIn(0, coreGranularity.itemCount - 1)
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
        it.checkVacuity = checkVacuity.isSelected
    }
}
