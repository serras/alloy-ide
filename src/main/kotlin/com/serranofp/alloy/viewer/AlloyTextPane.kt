package com.serranofp.alloy.viewer

import com.intellij.openapi.diff.DiffColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.util.ui.UIUtil
import java.awt.Font
import javax.swing.JTextPane
import javax.swing.text.AttributeSet
import javax.swing.text.DefaultStyledDocument
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

/**
 * The Text tab: the analyzer's text of a single state, or the [TraceDiff] of a trace state and its successor, in
 * the editor's font. Added tuples have the editor color scheme's colors for inserted text, removed ones those for
 * deleted text, struck through, and lines that do not change are dimmed.
 */
class AlloyTextPane : JTextPane() {
    init {
        isEditable = false
        font = editorFont()
    }

    /** Shows [text] as it is. */
    fun showText(text: String) {
        font = editorFont()
        this.text = text
        caretPosition = 0
    }

    /** Shows [diff], in a document built aside, so that the pane gets one change and not one per tuple. */
    fun showDiff(diff: TraceDiff) {
        font = editorFont()
        val document = DefaultStyledDocument()
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
        styledDocument = document
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

        /** The editor color scheme's plain font, which the user chose for code, as the analyzer's output is. */
        fun editorFont(): Font = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)
    }
}
