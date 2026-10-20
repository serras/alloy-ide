package com.serranofp.alloy.viewer

import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import edu.mit.csail.sdg.alloy4graph.GraphViewer
import java.awt.Color
import java.awt.image.BufferedImage
import javax.swing.JLayer
import javax.swing.UIManager

/** The graph's layer, painting with the dark mapping or without it, and following theme changes. */
class DarkGraphLayerTest : BasePlatformTestCase() {
    private var dark = false

    private fun layer(): JLayer<*> {
        val graph = AlloySolutionModel(solve(STATIC)).vizState.getGraph(null, null) as GraphViewer
        return DarkGraphLayerUI.wrap(graph, testRootDisposable, DarkGraphLayerUI { dark }).apply {
            size = graph.preferredSize
            doLayout()
        }
    }

    private fun canvas(layer: JLayer<*>): Int {
        val image = BufferedImage(layer.width, layer.height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        layer.paint(graphics)
        graphics.dispose()
        return image.getRGB(0, 0) or (0xFF shl 24)
    }

    fun testTheCanvasFollowsTheMapping() {
        val background = UIUtil.getPanelBackground().rgb or (0xFF shl 24)
        assertFalse("The panel background of the tests is white", background == Color.WHITE.rgb)
        val layer = layer()
        assertEquals(Color.WHITE.rgb, canvas(layer))
        dark = true
        assertEquals(background, canvas(layer))
        dark = false
        assertEquals(Color.WHITE.rgb, canvas(layer))
    }

    fun testAThemeChangeRepaintsWithTheNewColors() {
        dark = true
        val layer = layer()
        canvas(layer)
        // The panel background is a JBColor, which follows the theme; this is its color now
        val changed = Color(UIUtil.getPanelBackground().darker().rgb)
        // The palette of the first paint is forgotten when the theme changes
        UIManagerOverride(changed).use {
            ApplicationManager.getApplication().messageBus.syncPublisher(LafManagerListener.TOPIC).lookAndFeelChanged(LafManager.getInstance())
            assertEquals(changed.rgb, canvas(layer))
        }
    }

    /** The panel background set to [color] while it is used. */
    private class UIManagerOverride(color: Color) : AutoCloseable {
        private val previous = UIManager.get("Panel.background")

        init {
            UIManager.put("Panel.background", color)
        }

        override fun close() {
            UIManager.put("Panel.background", previous)
        }
    }
}
