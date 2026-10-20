package com.serranofp.alloy.viewer

import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.DropDownLink
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.JBUI
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The toolbar's projection control: a link naming the signatures the graph is projected over (or "None"), whose
 * popup has a checkbox for each signature that can be projected, and a link that removes every projection.
 * [changed] redraws the graph after each change.
 */
class AlloyProjectionChooser(private val changed: () -> Unit) {
    private var model: AlloySolutionModel? = null

    val link: DropDownLink<String> = DropDownLink(NONE) { createPopup() }

    /** Shows the projections of [model], such as those the magic layout chose. */
    fun show(model: AlloySolutionModel) {
        this.model = model
        update()
    }

    /** "State, Time", or "None". */
    val summary: String
        get() = model?.projections?.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.name } ?: NONE

    private fun update() {
        link.text = summary
        link.selectedItem = summary
    }

    /** The popup's content: [clear] and a checkbox per signature, checked when projected. */
    fun createContent(): JComponent {
        val model = model ?: return JPanel()
        val boxes = model.projectableTypes.map { type ->
            JBCheckBox(type.name, type in model.projections).apply {
                addActionListener {
                    model.setProjected(type, isSelected)
                    update()
                    changed()
                }
            }
        }
        val clear = ActionLink(CLEAR_ALL) {
            model.clearProjections()
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
        const val NONE = "None"
        const val CLEAR_ALL = "Clear all"
    }
}
