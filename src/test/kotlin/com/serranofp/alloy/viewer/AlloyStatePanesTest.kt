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

    private fun AlloyStatePanes.assertRow(width: Int, height: Int) {
        assertFalse(isColumn)
        val (first, second) = panes.map { it.bounds }
        assertEquals(first.y, second.y)
        assertEquals(height, first.height)
        assertTrue(first.x + first.width <= second.x)
        assertTrue(second.x + second.width <= width)
        assertTrue(first.width > width / 2 - 20)
    }

    private fun AlloyStatePanes.assertColumn(width: Int, height: Int) {
        assertTrue(isColumn)
        val (first, second) = panes.map { it.bounds }
        assertEquals(first.x, second.x)
        assertEquals(width, first.width)
        assertTrue(first.y + first.height <= second.y)
        assertTrue(second.y + second.height <= height)
        assertTrue(first.height > height / 2 - 20)
    }

    fun testARowAtEverySize() {
        val panes = panes("State 0", "State 1")
        assertEquals(PaneLayout.ROW, panes.paneLayout)
        panes.layOut(800, 400)
        panes.assertRow(800, 400)
        panes.layOut(400, 800)
        panes.assertRow(400, 800)
    }

    fun testAColumnAtEverySize() {
        val panes = panes("State 0", "State 1").apply { paneLayout = PaneLayout.COLUMN }
        panes.layOut(800, 400)
        panes.assertColumn(800, 400)
        panes.layOut(400, 800)
        panes.assertColumn(400, 800)
    }

    fun testSettingTheLayoutRearranges() {
        val panes = panes("State 0", "State 1")
        panes.layOut(800, 400)
        panes.paneLayout = PaneLayout.COLUMN
        panes.doLayout()
        panes.assertColumn(800, 400)
        panes.paneLayout = PaneLayout.ROW
        panes.doLayout()
        panes.assertRow(800, 400)
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
