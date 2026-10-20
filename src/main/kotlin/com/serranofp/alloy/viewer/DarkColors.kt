package com.serranofp.alloy.viewer

import java.awt.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The dark versions of the visualizer's colors, after the role-based rules of the PaletaColor Pro converter: in
 * HSL, keeping the hue. A node [fill] becomes a muted dark surface, and an [edge] (with its label) is lightened
 * to stand out on a dark canvas. Both then make sure of their contrast, with the light [LABEL] drawn on a fill
 * and with the dark [CANVAS] under an edge.
 */
object DarkColors {
    /** A light label, as a dark theme's foreground; node labels are drawn in such a color. */
    val LABEL = Color(0xDF, 0xE1, 0xE5)

    /** A dark canvas, the lightest of the usual dark panel backgrounds (Darcula's). */
    val CANVAS = Color(0x3C, 0x3F, 0x41)

    const val FILL_CONTRAST = 4.5
    const val EDGE_CONTRAST = 3.0

    /**
     * A muted dark surface: the lightness goes into the band [0.20, 0.35], where lighter colors stay lighter, and
     * the saturation is muted. A color still too bright for [label] (yellows and greens) is muted further, which
     * keeps both its hue and its lightness.
     */
    fun fill(color: Color, label: Color = LABEL): Color {
        val (h, s, l) = hsl(color)
        val lightness = 0.20 + 0.15 * l
        var saturation = 0.85 * s
        var fill = color(h, saturation, lightness, color.alpha)
        while (contrast(fill, label) < FILL_CONTRAST && saturation > 0.0) {
            saturation = max(0.0, saturation - 0.01)
            fill = color(h, saturation, lightness, color.alpha)
        }
        return fill
    }

    /**
     * The converter's "primary" rule: dark colors are lightened most, light ones darkened a little, and very
     * saturated ones muted. A color still too dark on [canvas] (blues and reds) is lightened further.
     */
    fun edge(color: Color, canvas: Color = CANVAS): Color {
        val (h, s, l) = hsl(color)
        var lightness = when {
            l < 0.35 -> l + 0.30
            l > 0.65 -> l - 0.10
            else -> l + 0.12
        }
        val saturation = if (s > 0.8) 0.85 * s else s
        var edge = color(h, saturation, lightness, color.alpha)
        while (contrast(edge, canvas) < EDGE_CONTRAST && lightness < 1.0) {
            lightness = min(1.0, lightness + 0.01)
            edge = color(h, saturation, lightness, color.alpha)
        }
        return edge
    }

    /** Hue in degrees, saturation and lightness, as HSL defines them. */
    fun hsl(color: Color): Triple<Double, Double, Double> {
        val r = color.red / 255.0
        val g = color.green / 255.0
        val b = color.blue / 255.0
        val max = max(r, max(g, b))
        val min = min(r, min(g, b))
        val l = (max + min) / 2
        val d = max - min
        if (d == 0.0) return Triple(0.0, 0.0, l)
        val s = d / (1 - abs(2 * l - 1))
        val h = when (max) {
            r -> 60 * (((g - b) / d).mod(6.0))
            g -> 60 * ((b - r) / d + 2)
            else -> 60 * ((r - g) / d + 4)
        }
        return Triple(h, s, l)
    }

    fun color(h: Double, s: Double, l: Double, alpha: Int = 255): Color {
        val c = (1 - abs(2 * l - 1)) * s
        val x = c * (1 - abs((h / 60).mod(2.0) - 1))
        val m = l - c / 2
        val (r, g, b) = when ((h / 60).toInt().coerceIn(0, 5)) {
            0 -> Triple(c, x, 0.0)
            1 -> Triple(x, c, 0.0)
            2 -> Triple(0.0, c, x)
            3 -> Triple(0.0, x, c)
            4 -> Triple(x, 0.0, c)
            else -> Triple(c, 0.0, x)
        }
        fun channel(v: Double) = ((v + m) * 255).roundToInt().coerceIn(0, 255)
        return Color(channel(r), channel(g), channel(b), alpha)
    }

    /** The WCAG contrast ratio of two colors, from 1 to 21. */
    fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** The WCAG relative luminance. */
    fun luminance(color: Color): Double {
        fun linear(c: Int) = (c / 255.0).let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)
    }
}
