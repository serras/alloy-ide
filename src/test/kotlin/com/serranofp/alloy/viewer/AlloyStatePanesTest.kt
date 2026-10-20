package com.serranofp.alloy.viewer

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import java.awt.Rectangle
import javax.swing.JPanel

class AlloyStatePanesTest : BasePlatformTestCase() {
    private fun panes(vararg captions: String?) = AlloyStatePanes().apply { show(captions.map { it to JPanel() }) }

    private fun AlloyStatePanes.layOut(width: Int, height: Int) {
        setSize(width, height)
        doLayout()
    }

    fun testWideIsARow() {
        val panes = panes("State 0", "State 1")
        panes.layOut(800, 400)
        assertFalse(panes.isColumn)
        val (first, second) = panes.panes.map { it.bounds }
        assertEquals(first.y, second.y)
        assertEquals(400, first.height)
        assertTrue(first.x + first.width <= second.x)
        assertTrue(second.x + second.width <= 800)
        assertTrue(first.width > 380)
    }

    fun testTallIsAColumn() {
        val panes = panes("State 0", "State 1")
        panes.layOut(400, 800)
        assertTrue(panes.isColumn)
        val (first, second) = panes.panes.map { it.bounds }
        assertEquals(first.x, second.x)
        assertEquals(400, first.width)
        assertTrue(first.y + first.height <= second.y)
        assertTrue(second.y + second.height <= 800)
    }

    fun testResizingRearranges() {
        val panes = panes("State 0", "State 1")
        panes.layOut(800, 400)
        assertTrue(panes.panes[1].x > 0)
        panes.layOut(400, 800)
        assertEquals(0, panes.panes[1].x)
        assertTrue(panes.panes[1].y > 0)
    }

    fun testCaptions() {
        val panes = panes("State 2", "State 1 (loop)")
        assertEquals(listOf("State 2", "State 1 (loop)"), panes.captions)
        assertEquals(listOf("State 2", "State 1 (loop)"), UIUtil.findComponentsOfType(panes, JBLabel::class.java).map { it.text })
    }

    fun testASinglePaneTakesTheWholeArea() {
        val panes = panes(null)
        panes.layOut(800, 400)
        assertEquals(Rectangle(0, 0, 800, 400), panes.panes.single().bounds)
        assertEmpty(UIUtil.findComponentsOfType(panes, JBLabel::class.java))
    }
}
