package com.serranofp.alloy.viewer

import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Point
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JPanel
import javax.swing.UIManager
import kotlin.math.roundToInt

/** The strip on the lasso of [LASSO] (states 0, 1 and 2, then back to state 1): painting, clicks, tooltips and scrolling. */
class AlloyTraceStripTest : BasePlatformTestCase() {
    private var dark = false
    private val clicked = mutableListOf<Int>()

    /** The background around the strip, in a dark theme's range. */
    private val surrounding = Color(43, 45, 48)

    private fun strip(traceLength: Int = 3, loopState: Int = 1, selected: Int = 0) =
        AlloyTraceStrip(testRootDisposable, { dark }) { clicked += it }.apply {
            show(traceLength, loopState, selected, if (selected + 1 < traceLength) selected + 1 else loopState)
            size = preferredSize
            JPanel(null).apply { background = surrounding; isOpaque = true; add(scrollPane) }
        }

    /**
     * The strip painted over [UNPAINTED], clipped to its bounds as Swing clips it, so that what it does not paint
     * shows.
     */
    private fun image(strip: AlloyTraceStrip): BufferedImage {
        val image = BufferedImage(strip.width, strip.height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = UNPAINTED
        graphics.fillRect(0, 0, strip.width, strip.height)
        graphics.setClip(0, 0, strip.width, strip.height)
        strip.paint(graphics)
        graphics.dispose()
        return image
    }

    /** The fill of [state]'s circle, sampled under its center, since its number is drawn on the center. */
    private fun fill(strip: AlloyTraceStrip, image: BufferedImage, state: Int): Color {
        val circle = strip.geometry!!.circles[state]
        return Color(image.getRGB(circle.centerX.roundToInt(), (circle.centerY + circle.height * 3 / 8).roundToInt()))
    }

    private fun lightness(color: Color) = DarkColors.hsl(color).third

    private fun center(strip: AlloyTraceStrip, state: Int): Point =
        strip.geometry!!.circles[state].let { Point(it.centerX.roundToInt(), it.centerY.roundToInt()) }

    private fun event(strip: AlloyTraceStrip, id: Int, point: Point) =
        MouseEvent(strip, id, System.currentTimeMillis(), 0, point.x, point.y, 1, false, MouseEvent.BUTTON1)

    fun testLightColorsAreAlloys() {
        val strip = strip()
        val image = image(strip)
        assertEquals(Color.WHITE, fill(strip, image, 0))
        assertEquals(Color.WHITE, fill(strip, image, 1))
        assertEquals(Color(120, 120, 120), fill(strip, image, 2))
    }

    fun testTheBackgroundIsTheSurroundingOne() {
        for (theme in listOf(false, true)) {
            dark = theme
            val strip = strip()
            assertFalse(strip.isOpaque || strip.scrollPane.isOpaque || strip.scrollPane.viewport.isOpaque)
            assertEquals(UNPAINTED, Color(image(strip).getRGB(0, 0)))
        }
    }

    fun testEveryArrowIsDrawn() {
        val strip = strip(traceLength = 5, loopState = 0)
        val image = image(strip)
        for ((left, right) in strip.geometry!!.circles.zipWithNext()) {
            val x = ((left.maxX + right.minX) / 2).roundToInt()
            // The 1 px line is centered between two rows of pixels, so it darkens both
            val pixels = listOf(left.centerY.toInt() - 1, left.centerY.toInt()).map { Color(image.getRGB(x, it)) }
            assertTrue("No arrow at $x: $pixels", pixels.any { lightness(it) < 0.5 })
        }
        val curve = strip.geometry!!.loopCurve
        // The highest point of a curve whose control points are at the top
        val top = Color(image.getRGB(((curve.x1 + curve.x2) / 2).roundToInt(), (curve.y1 / 4).roundToInt()))
        assertTrue("No loop curve: $top", lightness(top) < 0.6)
    }

    fun testNumbersAreCentered() {
        val strip = strip()
        val image = image(strip)
        for (state in 0..1) {
            val circle = strip.geometry!!.circles[state]
            val inner = (circle.width / 2 - 4).toInt()
            val dark = (-inner..inner).flatMap { dx -> (-inner..inner).map { dy -> dx to dy } }.filter { (dx, dy) ->
                lightness(Color(image.getRGB(circle.centerX.toInt() + dx, circle.centerY.toInt() + dy))) < 0.5
            }
            assertTrue(dark.isNotEmpty())
            val middle = (dark.minOf { it.second } + dark.maxOf { it.second }) / 2.0
            assertTrue("State $state's number is centered at $middle", kotlin.math.abs(middle) <= 1.0)
        }
    }

    fun testDarkColorsAreThePalettes() {
        dark = true
        val strip = strip()
        val image = image(strip)
        val highlighted = fill(strip, image, 0)
        val dimmed = fill(strip, image, 2)
        assertTrue("$highlighted is not lighter than $dimmed", lightness(highlighted) > lightness(dimmed))
        assertEquals(surrounding, dimmed)
        assertEquals(Color(DarkPalette.ide(surrounding).selection.rgb), highlighted)
    }

    fun testAThemeChangeTakesTheNewPalette() {
        dark = true
        val strip = strip()
        val circle = strip.geometry!!.circles[0]
        // The top of the outline, which the 2 px stroke covers entirely
        fun outline() = Color(image(strip).getRGB(circle.centerX.roundToInt(), circle.minY.roundToInt()))
        assertEquals(Color(UIUtil.getLabelForeground().rgb), outline())
        val previous = UIManager.get("Label.foreground")
        val changed = Color(200, 100, 50)
        UIManager.put("Label.foreground", changed)
        try {
            // The palette of the first paint is forgotten when the theme changes
            ApplicationManager.getApplication().messageBus.syncPublisher(LafManagerListener.TOPIC).lookAndFeelChanged(LafManager.getInstance())
            assertEquals(changed, outline())
        } finally {
            UIManager.put("Label.foreground", previous)
        }
    }

    fun testTheHighlightingFollowsTheSelection() {
        val strip = strip(selected = 2)
        val image = image(strip)
        assertEquals(Color(120, 120, 120), fill(strip, image, 0))
        assertEquals(Color.WHITE, fill(strip, image, 1))
        assertEquals(Color.WHITE, fill(strip, image, 2))
    }

    fun testClickingACircleSelectsItsState() {
        val strip = strip()
        strip.dispatchEvent(event(strip, MouseEvent.MOUSE_PRESSED, center(strip, 2)))
        assertEquals(listOf(2), clicked)
    }

    fun testOtherButtonsDoNotSelect() {
        val strip = strip()
        val point = center(strip, 2)
        strip.dispatchEvent(MouseEvent(strip, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, point.x, point.y, 1, true, MouseEvent.BUTTON3))
        assertEquals(emptyList<Int>(), clicked)
    }

    /** A click whose mouse moves [drag] pixels between the press and the release, which then makes no `MOUSE_CLICKED`. */
    private fun click(strip: AlloyTraceStrip, point: Point, drag: Int) {
        val released = Point(point.x + drag, point.y)
        strip.dispatchEvent(event(strip, MouseEvent.MOUSE_PRESSED, point))
        if (drag > 0) strip.dispatchEvent(event(strip, MouseEvent.MOUSE_DRAGGED, released))
        strip.dispatchEvent(event(strip, MouseEvent.MOUSE_RELEASED, released))
        if (drag == 0) strip.dispatchEvent(event(strip, MouseEvent.MOUSE_CLICKED, released))
    }

    fun testAClickSelectsOnce() {
        val strip = strip()
        click(strip, center(strip, 2), drag = 0)
        assertEquals(listOf(2), clicked)
    }

    /** The mouse often moves a little while clicking. */
    fun testASlightlyDraggedClickSelects() {
        val strip = strip()
        click(strip, center(strip, 2), drag = 2)
        assertEquals(listOf(2), clicked)
    }

    fun testKeysStepThroughTheTrace() {
        val strip = strip()
        assertTrue(strip.isFocusable)
        strip.press(KeyEvent.VK_RIGHT)
        strip.press(KeyEvent.VK_END)
        // Nothing before the first state
        strip.press(KeyEvent.VK_LEFT)
        strip.press(KeyEvent.VK_HOME)
        assertEquals(listOf(1, 2), clicked)
        // After the last state, the loop target; before it, the state on its left
        val last = strip(selected = 2)
        clicked.clear()
        last.press(KeyEvent.VK_RIGHT)
        last.press(KeyEvent.VK_LEFT)
        last.press(KeyEvent.VK_HOME)
        assertEquals(listOf(1, 1, 0), clicked)
    }

    fun testClickingBetweenCirclesDoesNothing() {
        val strip = strip()
        val (first, second) = strip.geometry!!.circles
        val between = Point(((first.maxX + second.minX) / 2).roundToInt(), first.centerY.roundToInt())
        strip.dispatchEvent(event(strip, MouseEvent.MOUSE_PRESSED, between))
        assertEquals(emptyList<Int>(), clicked)
    }

    fun testTooltips() {
        val strip = strip()
        assertEquals("State 0", strip.getToolTipText(event(strip, MouseEvent.MOUSE_MOVED, center(strip, 0))))
        assertEquals("State 1 (state 2 loops back to it)", strip.getToolTipText(event(strip, MouseEvent.MOUSE_MOVED, center(strip, 1))))
        assertEquals("State 2", strip.getToolTipText(event(strip, MouseEvent.MOUSE_MOVED, center(strip, 2))))
        assertNull(strip.getToolTipText(event(strip, MouseEvent.MOUSE_MOVED, Point(0, 0))))
    }

    fun testSelfLoopTooltip() {
        val strip = strip(traceLength = 2, loopState = 1)
        assertEquals("State 1 (loops back to itself)", strip.getToolTipText(event(strip, MouseEvent.MOUSE_MOVED, center(strip, 1))))
    }

    fun testRevealScrollsToTheHighlightedPair() {
        val strip = AlloyTraceStrip(testRootDisposable, { dark })
        strip.show(30, 0, 0, 1)
        val pane = strip.scrollPane
        pane.setSize(200, strip.preferredSize.height + 20)
        pane.doLayout()
        pane.viewport.doLayout()
        assertTrue(strip.width > 200)

        strip.show(30, 0, 25, 26)
        strip.reveal()
        val visible = pane.viewport.viewRect
        assertTrue("$visible", visible.contains(strip.geometry!!.circles[25].bounds))
        assertTrue("$visible", visible.contains(strip.geometry!!.circles[26].bounds))
    }

    fun testRevealPrefersTheSelectedStateWhenThePairDoesNotFit() {
        val strip = AlloyTraceStrip(testRootDisposable, { dark })
        strip.show(30, 0, 29, 0)
        val pane = strip.scrollPane
        pane.setSize(200, strip.preferredSize.height + 20)
        pane.doLayout()
        pane.viewport.doLayout()

        // The pair is the last state and the loop target, state 0, at the other end
        strip.reveal()
        assertTrue(pane.viewport.viewRect.contains(strip.geometry!!.circles[29].bounds))
    }

    private companion object {
        /** A color the strip never paints. */
        val UNPAINTED = Color(255, 0, 255)
    }
}
