package com.serranofp.alloy.viewer

import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JComponent
import javax.swing.ScrollPaneConstants
import javax.swing.ToolTipManager

/**
 * The trace path strip, as the Alloy visualizer draws it above the graph: a numbered circle per state, arrows
 * between consecutive states and a curve from the last state back to the loop target, with the two shown states
 * highlighted. Clicking a circle calls [onSelect] with its state. It is transparent, over the background around
 * it. Under a light theme it has Alloy's colors; under a dark one, the roles of a [DarkPalette] of that
 * background, taken again whenever the theme changes, until [parent] is disposed. It is shown in [scrollPane],
 * which scrolls horizontally only.
 */
class AlloyTraceStrip(
    parent: Disposable,
    private val isDark: () -> Boolean = { !JBColor.isBright() },
    private val onSelect: (Int) -> Unit = {},
) : JComponent() {
    /** The geometry of the trace shown; null before one is. */
    var geometry: TraceStripLayout? = null
        private set

    private var palette: DarkPalette? = null

    val scrollPane: JBScrollPane =
        JBScrollPane(this, ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
        }

    init {
        isOpaque = false
        font = JBUI.Fonts.label()
        ToolTipManager.sharedInstance().registerComponent(this)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                geometry?.stateAt(e.point)?.let(onSelect)
            }
        })
        ApplicationManager.getApplication().messageBus.connect(parent).subscribe(LafManagerListener.TOPIC, LafManagerListener {
            palette = null
            repaint()
        })
    }

    /** Shows a trace of [traceLength] states that loops back to [loopState], with [selected] and its successor highlighted. */
    fun show(traceLength: Int, loopState: Int, selected: Int) {
        geometry = TraceStripLayout(traceLength, loopState, selected)
        revalidate()
        repaint()
    }

    /**
     * Scrolls to the circles of the highlighted pair, and everything between them; when they do not fit, to the
     * selected state's circle.
     */
    fun reveal() {
        val geometry = geometry ?: return
        // After show(), the strip may not have its new size yet
        scrollPane.validate()
        val pair = geometry.highlighted.map { geometry.circles[it].bounds }.reduce(Rectangle::union)
        val margin = JBUI.scale(TraceStripLayout.MARGIN)
        val target = if (pair.width + 2 * margin <= scrollPane.viewport.width) pair else geometry.circles[geometry.selected].bounds
        scrollRectToVisible(Rectangle(target.x - margin, 0, target.width + 2 * margin, height))
    }

    override fun getPreferredSize(): Dimension {
        val geometry = geometry ?: return Dimension(0, 0)
        return Dimension(geometry.preferredWidth, geometry.height)
    }

    override fun getToolTipText(event: MouseEvent): String? {
        val geometry = geometry ?: return null
        val state = geometry.stateAt(event.point) ?: return null
        if (state != geometry.loopState) return "State $state"
        val last = geometry.traceLength - 1
        return if (last == state) "State $state (loops back to itself)" else "State $state (state $last loops back to it)"
    }

    /** The colors of the strip, by role. */
    private class Colors(
        val highlighted: Color,
        val dimmed: Color,
        val outline: Color,
        val highlightedNumber: Color,
        val dimmedNumber: Color,
    )

    private fun colors(): Colors {
        if (!isDark()) return Colors(Color.WHITE, ALLOY_GREY, Color.BLACK, Color.BLACK, Color.BLACK)
        val background = UIUtil.findNearestOpaque(this)?.background ?: UIUtil.getPanelBackground()
        val palette = palette?.takeIf { it.canvas == background } ?: DarkPalette.ide(background).also { palette = it }
        return Colors(palette.selection, palette.canvas, palette.foreground, palette.label, UIUtil.getContextHelpForeground())
    }

    override fun paintComponent(g: Graphics) {
        val geometry = geometry ?: return
        val colors = colors()
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.font = font
            val clip = g2.clipBounds
            g2.stroke = BasicStroke(geometry.circleStroke)
            for ((state, circle) in geometry.circles.withIndex()) {
                if (clip != null && !circle.intersects(clip)) continue
                val highlighted = state in geometry.highlighted
                g2.color = if (highlighted) colors.highlighted else colors.dimmed
                g2.fill(circle)
                g2.color = colors.outline
                g2.draw(circle)
                // Centered on its glyphs; Alloy's half ascent down puts the font's leading above them, so they sit low
                val number = state.toString()
                val glyphs = g2.font.createGlyphVector(g2.fontRenderContext, number).visualBounds
                g2.color = if (highlighted) colors.highlightedNumber else colors.dimmedNumber
                g2.drawString(number, (circle.centerX - glyphs.centerX).toFloat(), (circle.centerY - glyphs.centerY).toFloat())
            }
            g2.stroke = BasicStroke(geometry.arrowStroke)
            g2.color = colors.outline
            for (transition in geometry.transitions) {
                val path = geometry.path(transition)
                val head = geometry.head(transition)
                // A straight arrow's line has no height, so its bounds never intersect anything; its head's do
                if (clip != null && !path.bounds2D.createUnion(head.bounds2D).intersects(clip)) continue
                g2.draw(path)
                g2.fill(head)
            }
        } finally {
            g2.dispose()
        }
    }

    private companion object {
        /** The fill of the states Alloy does not show. */
        val ALLOY_GREY = Color(120, 120, 120)
    }
}
