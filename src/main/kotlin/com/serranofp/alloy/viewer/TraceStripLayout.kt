package com.serranofp.alloy.viewer

import com.intellij.ui.scale.JBUIScale
import java.awt.Polygon
import java.awt.Shape
import java.awt.geom.AffineTransform
import java.awt.geom.CubicCurve2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Point2D
import kotlin.math.atan2
import kotlin.math.ceil

/**
 * The geometry of the trace path strip, everything but its painting: a circle per state of a trace of
 * [traceLength] states, left to right, a straight arrow between consecutive states, and a curve above them from
 * the last state back to [loopState]. The [selected] state and its [successor], the two the tabs show, are
 * [highlighted]. Every length is Alloy's, multiplied by [scale].
 */
class TraceStripLayout(
    val traceLength: Int,
    val loopState: Int,
    val selected: Int,
    val successor: Int = if (selected + 1 < traceLength) selected + 1 else loopState,
    private val scale: Float = JBUIScale.scale(1f),
) {
    init {
        require(traceLength > 0 && loopState in 0 until traceLength && selected in 0 until traceLength)
    }

    private fun scaled(length: Int): Double = length * scale.toDouble()

    val transitions: List<Transition> = Transition.lasso(traceLength, loopState)

    val highlighted: Set<Int> = setOf(selected, successor)

    /** The circles, one per state: each state's circle is its index. */
    val circles: List<Ellipse2D> = List(traceLength) { state ->
        val radius = scaled(RADIUS)
        Ellipse2D.Double(scaled(MARGIN + state * SPACING), scaled(CENTER_Y) - radius, 2 * radius, 2 * radius)
    }

    val circleStroke: Float get() = CIRCLE_STROKE * scale

    val arrowStroke: Float get() = ARROW_STROKE * scale

    val height: Int get() = ceil(scaled(HEIGHT)).toInt()

    /** The width that shows every circle, which grows by one spacing per state. */
    val preferredWidth: Int get() = ceil(scaled(traceLength * SPACING)).toInt()

    /**
     * The loop, from the top of the last circle to the top of the loop target's, through control points at the
     * top of the strip [LOOP_REACH] towards each other; for a self-loop, the curve leaves and returns to the same
     * circle.
     */
    val loopCurve: CubicCurve2D = run {
        val last = circles.last()
        val target = circles[loopState]
        val reach = scaled(LOOP_REACH)
        CubicCurve2D.Double(last.centerX, last.minY, last.centerX - reach, 0.0, target.centerX + reach, 0.0, target.centerX, target.minY)
    }

    /** The line or curve of [transition], without its head. */
    fun path(transition: Transition): Shape {
        if (transition.isLoop) return loopCurve
        val from = circles[transition.from]
        val to = circles[transition.to]
        return Line2D.Double(from.maxX, from.centerY, to.minX, to.centerY)
    }

    /** The head of [transition]'s arrow, at its end and turned to the direction it arrives from. */
    fun head(transition: Transition): Shape {
        val (tip, angle) = if (transition.isLoop) {
            loopCurve.p2 to atan2(loopCurve.y2 - loopCurve.ctrlY2, loopCurve.x2 - loopCurve.ctrlX2)
        } else {
            val to = circles[transition.to]
            Point2D.Double(to.minX, to.centerY) to 0.0
        }
        // The polygon points down; turning it by the angle minus a quarter makes it point along the arrow
        val transform = AffineTransform().apply {
            translate(tip.x, tip.y)
            rotate(angle - Math.PI / 2)
            scale(scale.toDouble(), scale.toDouble())
        }
        return transform.createTransformedShape(HEAD)
    }

    /** The state whose circle contains [point], if any. */
    fun stateAt(point: Point2D): Int? = circles.indexOfFirst { it.contains(point) }.takeIf { it >= 0 }

    /**
     * Alloy's constants, unscaled, read from the trace strip of the Alloy 6.2.0 visualizer, which
     * `VizGUI.traceGraph()` builds (the anonymous panel `VizGUI$7`). Alloy centers an unrolled window on the
     * selected state; this strip starts at the left, with half the gap between circles as its margin.
     */
    companion object {
        const val HEIGHT = 50
        const val RADIUS = 12
        const val SPACING = 45
        const val CENTER_Y = 2 + HEIGHT / 2
        const val MARGIN = (SPACING - 2 * RADIUS) / 2

        /** How far the loop's control points reach from the ends of the curve, towards each other. */
        const val LOOP_REACH = 25

        const val CIRCLE_STROKE = 2f
        const val ARROW_STROKE = 1f

        /** The arrow head, pointing down and centered on the end of its arrow, as Alloy places it. */
        private val HEAD = Polygon(intArrayOf(0, -4, 4), intArrayOf(4, -4, -4), 3)
    }
}
