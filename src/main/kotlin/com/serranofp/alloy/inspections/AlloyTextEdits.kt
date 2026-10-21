package com.serranofp.alloy.inspections

import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange

/** Line and indentation helpers for quick-fixes that edit the body of a paragraph. */
object AlloyTextEdits {
    /** The text of [line], without its line separator. */
    fun lineText(document: Document, line: Int): String =
        document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line)))

    /** The leading spaces and tabs of [line]. */
    fun indentOf(document: Document, line: Int): String = lineText(document, line).takeWhile { it == ' ' || it == '\t' }

    /** The indentation of the last non-blank line strictly between the lines of [open] and [close]. */
    fun bodyIndent(document: Document, open: Int, close: Int): String? {
        val lines = (document.getLineNumber(open) + 1 until document.getLineNumber(close)).reversed()
        val line = lines.firstOrNull { lineText(document, it).isNotBlank() } ?: return null
        return indentOf(document, line)
    }

    /**
     * [range] grown to the brackets and parentheses it leaves open or closed, within [bounds]: the analyzer's
     * spans end at the last argument of a call, and start inside the parentheses of `(e)'`.
     */
    fun balanced(document: Document, range: TextRange, bounds: TextRange): TextRange {
        val text = document.charsSequence
        var start = range.startOffset
        var end = range.endOffset
        var open = 0
        var unopened = 0
        for (i in start until end) {
            when (text[i]) {
                '(', '[' -> open++
                ')', ']' -> if (open > 0) open-- else unopened++
            }
        }
        while (unopened > 0 && start > bounds.startOffset) {
            start--
            when (text[start]) {
                '(', '[' -> unopened--
                ')', ']' -> unopened++
            }
        }
        while (open > 0 && end < bounds.endOffset) {
            when (text[end]) {
                '(', '[' -> open++
                ')', ']' -> open--
            }
            end++
        }
        return TextRange(start, end)
    }
}
