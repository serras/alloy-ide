package com.serranofp.alloy.viewer

import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.DropDownLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.JBUI
import com.serranofp.alloy.AlloyBundle
import edu.mit.csail.sdg.alloy4viz.AlloyType
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The toolbar's projection control: a link naming the signatures the graph is projected over (or "None"), whose
 * popup has a checkbox for each signature that can be projected, and a link that removes every projection.
 * The projection is this chooser's own, so that each viewer of a shared model has its own (see
 * [AlloySolutionModel.project]). [changed] redraws the graph after each change.
 */
class AlloyProjectionChooser(private val changed: () -> Unit) {
    private var model: AlloySolutionModel? = null

    private val chosen = mutableListOf<AlloyType>()

    val link: DropDownLink<String> = DropDownLink(NONE) { createPopup() }

    /** The signatures this chooser's graphs are projected over, in the order they were chosen. */
    val projections: List<AlloyType> get() = chosen.toList()

    /**
     * Shows [model] with the projections it started with, such as those the magic layout chose, or, if [keep], with
     * this chooser's current ones that [model] can still be projected over, as for a new solution of the same command.
     */
    fun show(model: AlloySolutionModel, keep: Boolean = false) {
        val names = (if (keep) chosen else model.initialProjections).map { it.name }
        this.model = model
        chosen.clear()
        // A new solution's signatures are new objects, so they are found by name
        names.mapNotNullTo(chosen) { name -> model.projectableTypes.find { it.name == name } }
        update()
    }

    /** Projects over [type] too, or no longer over it, and redraws. */
    fun setProjected(type: AlloyType, projected: Boolean) {
        if (projected) {
            if (type !in chosen) chosen += type
        } else chosen -= type
        update()
        changed()
    }

    /** "State, Time", or "None". */
    val summary: String
        get() = chosen.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.name } ?: NONE

    private fun update() {
        link.text = summary
        link.selectedItem = summary
    }

    /** The popup's content: [clear] and a checkbox per signature, checked when projected. */
    fun createContent(): JComponent {
        val model = model ?: return JPanel()
        val boxes = model.projectableTypes.map { type ->
            JBCheckBox(type.name, type in chosen).apply {
                addActionListener { setProjected(type, isSelected) }
            }
        }
        val clear = ActionLink(CLEAR_ALL) {
            chosen.clear()
            boxes.forEach { it.isSelected = false }
            update()
            changed()
        }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(6, 8)
            add(clear)
            boxes.forEach(::add)
        }
    }

    private fun createPopup(): JBPopup {
        val content = createContent()
        return JBPopupFactory.getInstance().createComponentPopupBuilder(content, content).setRequestFocus(true).createPopup()
    }

    companion object {
        val NONE: String get() = AlloyBundle.message("viewer.projection.none")
        val CLEAR_ALL: String get() = AlloyBundle.message("viewer.projection.clear.all")
    }
}
