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
 * Paints a visualizer graph through [DarkGraphics] while [isDark], and as it is otherwise. The palette is taken
 * from the IDE theme when first needed, and again after the theme changes.
 */
class DarkGraphLayerUI(private val isDark: () -> Boolean = { !JBColor.isBright() }) : LayerUI<JComponent>() {
    private var palette: DarkPalette? = null

    override fun paint(g: Graphics, c: JComponent) {
        if (!isDark()) return super.paint(g, c)
        val dark = DarkGraphics(g.create() as Graphics2D, palette ?: DarkPalette.ide().also { palette = it })
        try {
            super.paint(dark, c)
        } finally {
            dark.dispose()
        }
    }

    /** Forgets the palette, after the IDE theme changed. */
    fun themeChanged() {
        palette = null
    }

    /** Takes the new palette and calls [repaint] whenever the IDE theme changes, until [parent] is disposed. */
    fun followTheme(parent: Disposable, repaint: () -> Unit) {
        ApplicationManager.getApplication().messageBus.connect(parent).subscribe(LafManagerListener.TOPIC, LafManagerListener {
            themeChanged()
            repaint()
        })
    }

    companion object {
        /** [view] in a layer that follows the IDE theme, repainted when it changes, until [parent] is disposed. */
        fun wrap(view: JComponent, parent: Disposable, ui: DarkGraphLayerUI = DarkGraphLayerUI()): JLayer<JComponent> {
            val layer = JLayer(view, ui)
            ui.followTheme(parent, layer::repaint)
            return layer
        }
    }
}
