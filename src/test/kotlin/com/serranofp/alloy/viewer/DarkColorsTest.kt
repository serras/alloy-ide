package com.serranofp.alloy.viewer

import edu.mit.csail.sdg.alloy4graph.DotColor
import edu.mit.csail.sdg.alloy4graph.DotPalette
import junit.framework.TestCase
import java.awt.Color
import kotlin.math.abs
import kotlin.math.min

/** The dark conversion of every color the visualizer's themes can give a node or an edge. */
class DarkColorsTest : TestCase() {
    private val colors: List<Pair<String, Color>> =
        DotPalette.entries.flatMap { palette -> DotColor.entries.map { "$it in $palette" to it.getColor(palette) } }.distinctBy { it.second }

    /** The difference of two hues, in degrees; the hue of a gray is not defined, so it is not compared. */
    private fun assertSameHue(name: String, original: Color, converted: Color) {
        val (h, s, _) = DarkColors.hsl(original)
        val (h2, s2, _) = DarkColors.hsl(converted)
        if (s == 0.0 || s2 == 0.0) return
        val difference = abs(h - h2).let { min(it, 360 - it) }
        assertTrue("$name: hue $h became $h2", difference <= 2.0)
    }

    fun testEveryPaletteColorIsChecked() {
        assertTrue(colors.size > 20)
        assertTrue(colors.any { it.second == Color.WHITE } && colors.any { it.second == Color.BLACK })
    }

    fun testFillsKeepTheirHue() {
        for ((name, color) in colors) assertSameHue(name, color, DarkColors.fill(color))
    }

    fun testFillsAreDark() {
        for ((name, color) in colors) {
            val lightness = DarkColors.hsl(DarkColors.fill(color)).third
            assertTrue("$name: lightness $lightness", lightness <= 0.35 + 0.5 / 255)
        }
    }

    fun testFillsAreOrderedByTheirOriginalLightness() {
        val sorted = colors.sortedBy { DarkColors.hsl(it.second).third }
        for ((lighter, darker) in sorted.drop(1).zip(sorted)) {
            if (DarkColors.hsl(lighter.second).third == DarkColors.hsl(darker.second).third) continue
            val l = DarkColors.hsl(DarkColors.fill(lighter.second)).third
            val d = DarkColors.hsl(DarkColors.fill(darker.second)).third
            // Up to the rounding into 8 bits per channel
            assertTrue("${lighter.first} ($l) is lighter than ${darker.first} ($d)", l >= d - 1.0 / 255)
        }
    }

    fun testFillsContrastWithALightLabel() {
        for ((name, color) in colors) {
            val contrast = DarkColors.contrast(DarkColors.fill(color), DarkColors.LABEL)
            assertTrue("$name: contrast $contrast", contrast >= 4.5)
        }
    }

    fun testEdgesKeepTheirHue() {
        for ((name, color) in colors) assertSameHue(name, color, DarkColors.edge(color))
    }

    fun testEdgesContrastWithADarkCanvas() {
        for ((name, color) in colors) {
            val contrast = DarkColors.contrast(DarkColors.edge(color), DarkColors.CANVAS)
            assertTrue("$name: contrast $contrast", contrast >= 3.0)
        }
    }

    fun testDarkEdgesAreLightened() {
        val edge = DarkColors.edge(Color(0, 0, 160))
        assertTrue(DarkColors.hsl(edge).third > DarkColors.hsl(Color(0, 0, 160)).third + 0.29)
    }

    fun testHslRoundTrip() {
        for ((name, color) in colors) {
            val (h, s, l) = DarkColors.hsl(color)
            assertEquals(name, color, DarkColors.color(h, s, l))
        }
    }
}
