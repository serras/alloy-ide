package com.serranofp.alloy.viewer

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import org.jetbrains.annotations.TestOnly

/** How two state panes are arranged: side by side, or stacked in a column. */
enum class PaneLayout {
    ROW,
    COLUMN;

    val toggled: PaneLayout get() = if (this == ROW) COLUMN else ROW
}

/**
 * One pane per shown state, each under its caption: a single state, or a trace state and its successor. Two
 * panes are placed in a row or stacked in a column, as [paneLayout] says, whatever the size.
 */
class AlloyStatePanes : JPanel(null) {
    /** The panes, in order, each with its caption (if any) above its content. */
    var panes: List<JComponent> = emptyList()
        private set

    /** The captions of the panes, as [show] got them. */
    @get:TestOnly
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

    /** How the panes are arranged; setting it lays them out again. */
    var paneLayout: PaneLayout = PaneLayout.ROW
        set(value) {
            if (field == value) return
            field = value
            revalidate()
            repaint()
        }

    /** Whether the panes are stacked in a column. */
    val isColumn: Boolean get() = paneLayout == PaneLayout.COLUMN

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
