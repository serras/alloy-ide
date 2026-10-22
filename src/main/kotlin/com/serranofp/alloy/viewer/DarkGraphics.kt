package com.serranofp.alloy.viewer

import com.intellij.ui.Graphics2DDelegate
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Paint
import java.awt.Shape
import java.awt.font.GlyphVector
import java.text.AttributedCharacterIterator

/**
 * What the visualizer's colors become under a dark theme, by role: the [canvas] behind the graph, the
 * [foreground] of black lines and outlines, the [label] of node text, and the [selection] surface of chosen nodes
 * and dimmed edges. Other colors go through [DarkColors], as a fill or as an edge.
 */
class DarkPalette(val canvas: Color, val foreground: Color, val label: Color, val selection: Color) {
    private val fills = mutableMapOf<Color, Color>()
    private val edges = mutableMapOf<Color, Color>()

    /** A color used to fill an area: the canvas, a node, a selected node. */
    fun fill(color: Color): Color = when (color.rgb or ALPHA) {
        WHITE -> canvas
        BLACK -> foreground
        LIGHT_GRAY -> selection
        else -> fills.getOrPut(color) { DarkColors.fill(color, label) }
    }

    /** A color used to draw a line: an edge, an outline. */
    fun draw(color: Color): Color = when (color.rgb or ALPHA) {
        WHITE -> canvas
        BLACK -> foreground
        LIGHT_GRAY -> selection
        else -> edges.getOrPut(color) { DarkColors.edge(color, canvas) }
    }

    /** A color used to draw text: a node label in black or white, or an edge label in its edge's color. */
    fun text(color: Color): Color = when (color.rgb or ALPHA) {
        WHITE, BLACK -> label
        else -> draw(color)
    }

    companion object {
        private const val ALPHA = 0xFF shl 24
        private val WHITE = Color.WHITE.rgb
        private val BLACK = Color.BLACK.rgb
        private val LIGHT_GRAY = Color.LIGHT_GRAY.rgb

        /**
         * The current IDE theme's colors on [canvas] (by default, the panel background), where a selection is a
         * surface a little lighter than the canvas.
         */
        fun ide(canvas: Color = UIUtil.getPanelBackground()): DarkPalette {
            val (h, s, l) = DarkColors.hsl(canvas)
            val foreground = UIUtil.getLabelForeground()
            return DarkPalette(canvas, foreground, foreground, DarkColors.color(h, s, (l + 0.15).coerceAtMost(1.0)))
        }
    }
}

/**
 * A `Graphics2D` that draws with the [palette]'s version of every color the visualizer sets. The color is mapped
 * when something is drawn, not when it is set, because the same color is a fill for `fill*`, a line for `draw*`
 * and text for `drawString`. [getColor] and [getBackground] still answer the colors that were set.
 */
class DarkGraphics private constructor(
    graphics: Graphics2D,
    private val palette: DarkPalette,
    /** The color that was set, or null when the paint is not a color, which is drawn as it is. */
    private var current: Color?,
    private var background: Color?,
) : Graphics2DDelegate(graphics) {
    /** Maps the background of [graphics]; a copy made by [create] has its original's mapped background already. */
    constructor(graphics: Graphics2D, palette: DarkPalette) : this(graphics, palette, graphics.color, graphics.background) {
        graphics.background?.let { graphics.background = palette.fill(it) }
    }

    private inline fun with(map: (Color) -> Color, draw: () -> Unit) {
        current?.let { super.setColor(map(it)) }
        draw()
    }

    private inline fun fills(draw: () -> Unit) = with(palette::fill, draw)

    private inline fun lines(draw: () -> Unit) = with(palette::draw, draw)

    private inline fun text(draw: () -> Unit) = with(palette::text, draw)

    override fun create(): Graphics = DarkGraphics(myDelegate.create() as Graphics2D, palette, current, background)

    override fun getColor(): Color? = current ?: super.getColor()

    override fun setColor(color: Color?) {
        current = color
        super.setColor(color)
    }

    override fun setPaint(paint: Paint?) {
        if (paint is Color) return setColor(paint)
        current = null
        super.setPaint(paint)
    }

    override fun getPaint(): Paint? = current ?: super.getPaint()

    override fun getBackground(): Color? = background ?: super.getBackground()

    override fun setBackground(color: Color?) {
        background = color
        super.setBackground(color?.let(palette::fill))
    }

    override fun fill(s: Shape) = fills { super.fill(s) }
    override fun fillArc(x: Int, y: Int, width: Int, height: Int, startAngle: Int, arcAngle: Int) =
        fills { super.fillArc(x, y, width, height, startAngle, arcAngle) }
    override fun fillOval(x: Int, y: Int, width: Int, height: Int) = fills { super.fillOval(x, y, width, height) }
    override fun fillPolygon(xPoints: IntArray, yPoints: IntArray, nPoints: Int) = fills { super.fillPolygon(xPoints, yPoints, nPoints) }
    override fun fillRect(x: Int, y: Int, width: Int, height: Int) = fills { super.fillRect(x, y, width, height) }
    override fun fillRoundRect(x: Int, y: Int, width: Int, height: Int, arcWidth: Int, arcHeight: Int) =
        fills { super.fillRoundRect(x, y, width, height, arcWidth, arcHeight) }

    override fun draw(s: Shape) = lines { super.draw(s) }
    override fun drawArc(x: Int, y: Int, width: Int, height: Int, startAngle: Int, arcAngle: Int) =
        lines { super.drawArc(x, y, width, height, startAngle, arcAngle) }
    override fun drawLine(x1: Int, y1: Int, x2: Int, y2: Int) = lines { super.drawLine(x1, y1, x2, y2) }
    override fun drawOval(x: Int, y: Int, width: Int, height: Int) = lines { super.drawOval(x, y, width, height) }
    override fun drawPolygon(xPoints: IntArray, yPoints: IntArray, nPoints: Int) = lines { super.drawPolygon(xPoints, yPoints, nPoints) }
    override fun drawPolyline(xPoints: IntArray, yPoints: IntArray, nPoints: Int) = lines { super.drawPolyline(xPoints, yPoints, nPoints) }
    override fun drawRoundRect(x: Int, y: Int, width: Int, height: Int, arcWidth: Int, arcHeight: Int) =
        lines { super.drawRoundRect(x, y, width, height, arcWidth, arcHeight) }

    override fun drawString(str: String, x: Int, y: Int) = text { super.drawString(str, x, y) }
    override fun drawString(str: String, x: Float, y: Float) = text { super.drawString(str, x, y) }
    override fun drawString(iterator: AttributedCharacterIterator, x: Int, y: Int) = text { super.drawString(iterator, x, y) }
    override fun drawString(iterator: AttributedCharacterIterator, x: Float, y: Float) = text { super.drawString(iterator, x, y) }
    override fun drawChars(data: CharArray, offset: Int, length: Int, x: Int, y: Int) = text { super.drawChars(data, offset, length, x, y) }
    override fun drawGlyphVector(g: GlyphVector, x: Float, y: Float) = text { super.drawGlyphVector(g, x, y) }
}
