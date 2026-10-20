package com.serranofp.alloy.viewer

import com.intellij.openapi.diff.DiffColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.util.ui.UIUtil
import java.awt.Font
import javax.swing.JTextPane
import javax.swing.text.AttributeSet
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

/**
 * The Text tab: the analyzer's text of a single state, or the [TraceDiff] of a trace state and its successor.
 * Added tuples have the editor color scheme's colors for inserted text, removed ones those for deleted text,
 * struck through, and lines that do not change are dimmed.
 */
class AlloyTextPane : JTextPane() {
    init {
        isEditable = false
        font = Font(Font.MONOSPACED, Font.PLAIN, UIUtil.getLabelFont().size)
    }

    /** Shows [text] as it is. */
    fun showText(text: String) {
        this.text = text
        caretPosition = 0
    }

    fun showDiff(diff: TraceDiff) {
        text = ""
        val document = styledDocument
        val plain = SimpleAttributeSet()
        val dimmed = SimpleAttributeSet().apply { StyleConstants.setForeground(this, UIUtil.getContextHelpForeground()) }
        val added = attributes(DiffColors.DIFF_INSERTED)
        val removed = attributes(DiffColors.DIFF_DELETED).apply { StyleConstants.setStrikeThrough(this, true) }
        fun append(text: String, attributes: AttributeSet) = document.insertString(document.length, text, attributes)
        for (line in diff.lines) {
            if (!line.isChanged) {
                append(line.text + "\n", dimmed)
                continue
            }
            append("${line.label}=" + if (line.braces) "{" else "", plain)
            line.tuples.forEachIndexed { i, tuple ->
                if (i > 0) append(", ", plain)
                append(tuple.text, when (tuple.mark) {
                    TraceDiff.Mark.KEPT -> plain
                    TraceDiff.Mark.ADDED -> added
                    TraceDiff.Mark.REMOVED -> removed
                })
            }
            append((if (line.braces) "}" else "") + "\n", plain)
        }
        caretPosition = 0
    }

    companion object {
        /** The current editor color scheme's colors for [key], as text attributes. */
        fun attributes(key: TextAttributesKey): SimpleAttributeSet {
            val scheme = EditorColorsManager.getInstance().globalScheme.getAttributes(key)
            return SimpleAttributeSet().apply {
                scheme?.backgroundColor?.let { StyleConstants.setBackground(this, it) }
                scheme?.foregroundColor?.let { StyleConstants.setForeground(this, it) }
            }
        }
    }
}
