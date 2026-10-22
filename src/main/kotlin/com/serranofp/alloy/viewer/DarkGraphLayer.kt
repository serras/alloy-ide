package com.serranofp.alloy.viewer

import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.JBColor
import java.awt.Graphics
import java.awt.Graphics2D
import javax.swing.JComponent
import javax.swing.JLayer
import javax.swing.plaf.LayerUI

/**
 * The dark mapping of the visualizer graphs of one tool window: whether it applies ([isDark]), and the palette,
 * taken from the IDE theme when first needed and again after the theme changes. Each graph gets its own
 * [DarkGraphLayerUI] from [layer]: a `LayerUI` listens to every layer it is installed on until it is replaced
 * there, so one shared by all graphs would keep every graph ever shown alive.
 */
class DarkGraphTheme(private val isDark: () -> Boolean = { !JBColor.isBright() }) {
    private var palette: DarkPalette? = null

    /** The palette to paint with, or null to paint as it is. */
    internal fun palette(): DarkPalette? = if (!isDark()) null else palette ?: DarkPalette.ide().also { palette = it }

    /** [view] in a layer of its own that paints through this mapping. */
    fun layer(view: JComponent): JLayer<JComponent> = JLayer(view, DarkGraphLayerUI(this))

    /** Takes the new palette and calls [repaint] whenever the IDE theme changes, until [parent] is disposed. */
    fun followTheme(parent: Disposable, repaint: () -> Unit) {
        ApplicationManager.getApplication().messageBus.connect(parent).subscribe(LafManagerListener.TOPIC, LafManagerListener {
            palette = null
            repaint()
        })
    }
}

/** Paints one visualizer graph through [DarkGraphics] while its [theme] is dark, and as it is otherwise. */
class DarkGraphLayerUI(private val theme: DarkGraphTheme) : LayerUI<JComponent>() {
    override fun paint(g: Graphics, c: JComponent) {
        val palette = theme.palette() ?: return super.paint(g, c)
        val dark = DarkGraphics(g.create() as Graphics2D, palette)
        try {
            super.paint(dark, c)
        } finally {
            dark.dispose()
        }
    }
}
