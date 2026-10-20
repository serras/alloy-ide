package com.serranofp.alloy.viewer

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import edu.mit.csail.sdg.alloy4graph.GraphViewer
import java.awt.Color
import java.awt.Graphics2D
import java.awt.image.BufferedImage

/** Labeled nodes and edges between them (the magic layout hides the label of a single atom). */
private const val GRAPH = "sig Node { succ: set Node }\nrun { #Node = 3 and #succ = 3 } for 3"

/** A visualizer graph painted through the dark mapping, compared with the same graph painted as it is. */
class DarkGraphicsTest : BasePlatformTestCase() {
    private val palette = DarkPalette(Color(0x2B, 0x2D, 0x30), Color(0xB0, 0xB0, 0xB0), DarkColors.LABEL, Color(0x4E, 0x51, 0x57))

    private fun graph(): GraphViewer {
        val viewer = AlloySolutionModel(solve(GRAPH)).vizState.getGraph(null, null) as GraphViewer
        viewer.size = viewer.preferredSize
        return viewer
    }

    private fun paint(viewer: GraphViewer, wrap: (Graphics2D) -> Graphics2D): BufferedImage {
        val image = BufferedImage(viewer.width, viewer.height, BufferedImage.TYPE_INT_RGB)
        val graphics = wrap(image.createGraphics())
        viewer.paint(graphics)
        graphics.dispose()
        return image
    }

    private fun BufferedImage.pixels(): List<Int> =
        (0 until height).flatMap { y -> (0 until width).map { x -> getRGB(x, y) or (0xFF shl 24) } }

    fun testWithoutTheMappingTheGraphHasAlloysColors() {
        val pixels = paint(graph()) { it }.pixels()
        assertEquals(Color.WHITE.rgb, pixels.first())
        assertTrue(Color.BLACK.rgb in pixels)
    }

    fun testWithTheMappingNoWhiteRemainsAndLabelsAreLight() {
        val viewer = graph()
        val light = paint(viewer) { it }.pixels()
        val dark = paint(viewer) { DarkGraphics(it, palette) }.pixels()
        assertEquals(palette.canvas.rgb, dark.first())
        assertFalse("A white pixel remains", Color.WHITE.rgb in dark)

        // What was solid black, lines and labels, is now light: the foreground for lines, the label for text
        val black = light.indices.filter { light[it] == Color.BLACK.rgb }
        assertNotEmpty(black)
        for (i in black) assertTrue(Color(dark[i]).toString(), dark[i] == palette.foreground.rgb || dark[i] == palette.label.rgb)
        assertTrue("No label text", black.any { dark[it] == palette.label.rgb })
    }

    fun testCopiesKeepTheMapping() {
        val graphics = DarkGraphics(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics(), palette)
        graphics.color = Color.WHITE
        val copy = graphics.create()
        assertInstanceOf(copy, DarkGraphics::class.java)
        assertEquals(Color.WHITE, copy.color)
        copy.fillRect(0, 0, 1, 1)
        copy.dispose()
        graphics.dispose()
    }

    fun testColorsByRole() {
        assertEquals(palette.canvas, palette.fill(Color.WHITE))
        assertEquals(palette.foreground, palette.draw(Color.BLACK))
        assertEquals(palette.label, palette.text(Color.BLACK))
        assertEquals(palette.label, palette.text(Color.WHITE))
        assertEquals(palette.selection, palette.fill(Color.LIGHT_GRAY))
        val gold = Color(255, 215, 0)
        assertEquals(DarkColors.fill(gold, palette.label), palette.fill(gold))
        assertEquals(DarkColors.edge(gold, palette.canvas), palette.draw(gold))
        assertEquals(DarkColors.edge(gold, palette.canvas), palette.text(gold))
    }
}
