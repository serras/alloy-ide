package com.serranofp.alloy.viewer

import com.intellij.ide.ui.LafManager
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.ui.UIUtil
import com.serranofp.alloy.run.waitUntil
import edu.mit.csail.sdg.alloy4graph.GraphViewer
import java.awt.Color
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import javax.swing.JLayer
import javax.swing.JPanel
import javax.swing.UIManager

/** The graph's layer, painting with the dark mapping or without it, and following theme changes. */
class DarkGraphLayerTest : AlloyViewerTestCase() {
    private var dark = false

    private fun layer(): JLayer<*> {
        val graph = AlloySolutionModel(solve(STATIC)).vizState.getGraph(null, null) as GraphViewer
        val theme = DarkGraphTheme { dark }
        return theme.layer(graph).apply {
            theme.followTheme(testRootDisposable, ::repaint)
            size = graph.preferredSize
            doLayout()
        }
    }

    /** A `LayerUI` listens to the layers it is installed on: the panes of one tool window must not share one. */
    fun testEachLayerHasItsOwnUI() {
        val theme = DarkGraphTheme { dark }
        val layers = List(3) { theme.layer(JPanel()) }
        assertEquals(3, layers.map { it.ui }.toSet().size)
        for (layer in layers) assertEquals(listOf(layer), layer.ui.propertyChangeListeners.toList())
    }

    /** Stepping through a trace makes new graphs: the panes kept by the layers' UIs are only those shown. */
    fun testThePanelKeepsNoRemovedGraph() {
        val panel = panelFor(LASSO)
        val strip = UIUtil.findComponentOfType(panel, AlloyTraceStrip::class.java)!!
        val model = viewer.session!!.model
        // Right ten times: states 1, 2, then the loop, each shown with new graphs
        repeat(10) { step ->
            strip.press(KeyEvent.VK_RIGHT)
            val state = 1 + step % 2
            waitUntil("state $state was not shown") { model.state == state && strip.geometry!!.selected == state }
        }
        val shown = UIUtil.findComponentsOfType(panel, JLayer::class.java)
        assertEquals(2, shown.size)
        val listened = shown.flatMap { layer -> layer.ui.propertyChangeListeners.toList() }
        assertSameElements(listened, shown)
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
