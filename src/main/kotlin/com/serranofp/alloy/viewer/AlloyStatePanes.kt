package com.serranofp.alloy.viewer

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * One pane per shown state, each under its caption: a single state, or a trace state and its successor. Two
 * panes are stacked in a column when there is more height than width, and placed in a row otherwise; the
 * arrangement follows every resize.
 */
class AlloyStatePanes : JPanel(null) {
    /** The panes, in order, each with its caption (if any) above its content. */
    var panes: List<JComponent> = emptyList()
        private set

    var captions: List<String?> = emptyList()
        private set

    /** Shows [contents], the captions first; a null caption leaves its pane without one. */
    fun show(contents: List<Pair<String?, JComponent>>) {
        removeAll()
        captions = contents.map { it.first }
        panes = contents.map { (caption, content) ->
            JPanel(BorderLayout()).apply {
                if (caption != null) add(JBLabel(caption).apply { foreground = UIUtil.getContextHelpForeground(); border = JBUI.Borders.empty(2, 4) }, BorderLayout.NORTH)
                add(content, BorderLayout.CENTER)
            }
        }
        panes.forEach(::add)
        revalidate()
        repaint()
    }

    /** Whether the panes are stacked in a column, for the current size. */
    val isColumn: Boolean get() = height > width

    override fun doLayout() {
        if (panes.isEmpty()) return
        val gap = if (panes.size > 1) JBUI.scale(GAP) else 0
        val count = panes.size
        if (isColumn) {
            val each = (height - gap * (count - 1)) / count
            panes.forEachIndexed { i, pane -> pane.setBounds(0, i * (each + gap), width, each) }
        } else {
            val each = (width - gap * (count - 1)) / count
            panes.forEachIndexed { i, pane -> pane.setBounds(i * (each + gap), 0, each, height) }
        }
    }

    private companion object {
        const val GAP = 4
    }
}
