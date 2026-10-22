package com.serranofp.alloy.viewer

import junit.framework.TestCase
import java.awt.geom.Point2D

/** The strip's geometry, on the lasso of [LASSO]: states 0, 1 and 2, then back to state 1. */
class TraceStripLayoutTest : TestCase() {
    /** The lasso of [LASSO], whose successor the model gives: after the last state, the loop target. */
    private fun lasso(selected: Int) = TraceStripLayout(3, 1, selected, if (selected < 2) selected + 1 else 1, scale = 1f)

    private fun assertNear(expected: Double, actual: Double) = assertEquals(expected, actual, 1e-9)

    fun testCirclesLeftToRight() {
        val circles = lasso(0).circles
        assertEquals(3, circles.size)
        circles.zipWithNext().forEach { (left, right) -> assertTrue(left.maxX < right.minX) }
        circles.forEach { assertNear(2.0 * TraceStripLayout.RADIUS, it.width) }
        assertNear(TraceStripLayout.SPACING.toDouble(), circles[1].centerX - circles[0].centerX)
        assertTrue(circles.all { it.minY >= 0 && it.maxY <= lasso(0).height })
    }

    fun testHighlightedPair() {
        assertEquals(setOf(0, 1), lasso(0).highlighted)
        assertEquals(setOf(2, 1), lasso(2).highlighted)
    }

    fun testStraightArrowsJoinConsecutiveCircles() {
        val layout = lasso(0)
        assertEquals(listOf(Transition(0, 1, false), Transition(1, 2, false), Transition(2, 1, true)), layout.transitions)
        val line = layout.path(layout.transitions[0]).bounds2D
        assertNear(layout.circles[0].maxX, line.minX)
        assertNear(layout.circles[1].minX, line.maxX)
        assertTrue(layout.head(layout.transitions[0]).bounds2D.intersects(layout.circles[1].bounds2D))
    }

    fun testLoopCurveTouchesTheTops() {
        val layout = lasso(0)
        val curve = layout.loopCurve
        assertSame(curve, layout.path(layout.transitions.last()))
        val last = layout.circles[2]
        val target = layout.circles[1]
        assertNear(last.centerX, curve.x1)
        assertNear(last.minY, curve.y1)
        assertNear(target.centerX, curve.x2)
        assertNear(target.minY, curve.y2)
        // Above the circles, and within the strip
        assertTrue(curve.bounds2D.minY >= 0 && curve.bounds2D.maxY <= last.minY)
        assertTrue(layout.head(layout.transitions.last()).bounds2D.contains(curve.p2))
    }

    fun testSelfLoopLeavesAndReturnsToTheSameCircle() {
        val layout = TraceStripLayout(2, 1, 1, 1, scale = 1f)
        assertEquals(setOf(1), layout.highlighted)
        val curve = layout.loopCurve
        val circle = layout.circles[1]
        assertNear(circle.centerX, curve.x1)
        assertNear(circle.centerX, curve.x2)
        assertNear(circle.minY, curve.y1)
        assertNear(circle.minY, curve.y2)
    }

    fun testStateAt() {
        val layout = lasso(0)
        for ((state, circle) in layout.circles.withIndex()) assertEquals(state, layout.stateAt(Point2D.Double(circle.centerX, circle.centerY)))
        val between = (layout.circles[0].maxX + layout.circles[1].minX) / 2
        assertNull(layout.stateAt(Point2D.Double(between, layout.circles[0].centerY)))
        assertNull(layout.stateAt(Point2D.Double(layout.circles[0].centerX, 1.0)))
    }

    fun testPreferredWidthIsLinear() {
        val widths = (1..5).map { TraceStripLayout(it, 0, 0, minOf(1, it - 1), scale = 1f).preferredWidth }
        assertEquals(1, widths.zipWithNext { a, b -> b - a }.distinct().size)
        assertEquals(TraceStripLayout.SPACING, widths[1] - widths[0])
        val long = TraceStripLayout(5, 0, 0, 1, scale = 1f)
        assertTrue(long.circles.last().maxX <= long.preferredWidth)
    }

    fun testScale() {
        val layout = TraceStripLayout(3, 1, 0, 1, scale = 2f)
        assertEquals(2 * TraceStripLayout.HEIGHT, layout.height)
        assertEquals(2 * lasso(0).preferredWidth, layout.preferredWidth)
        assertNear(4.0 * TraceStripLayout.RADIUS, layout.circles[0].width)
    }
}
