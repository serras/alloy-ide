package com.serranofp.alloy.inspections

import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.serranofp.alloy.psi.AlloyTypes

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
     * spans end at the last argument of a call, and start inside the parentheses of `(e)'`. Only bracket tokens
     * count, not those in comments or strings.
     */
    fun balanced(file: PsiFile, range: TextRange, bounds: TextRange): TextRange {
        fun delta(leaf: PsiElement): Int = when (leaf.node.elementType) {
            AlloyTypes.LPAREN, AlloyTypes.LBRACKET -> 1
            AlloyTypes.RPAREN, AlloyTypes.RBRACKET -> -1
            else -> 0
        }
        var start = range.startOffset
        var end = range.endOffset
        var open = 0
        var unopened = 0
        val first = file.findElementAt(start) ?: return range
        var last = first
        for (leaf in generateSequence(first) { PsiTreeUtil.nextLeaf(it) }.takeWhile { it.textRange.startOffset < end }) {
            last = leaf
            when (delta(leaf)) {
                1 -> open++
                -1 -> if (open > 0) open-- else unopened++
            }
        }
        var before = first
        while (unopened > 0) {
            before = PsiTreeUtil.prevLeaf(before)?.takeIf { it.textRange.startOffset >= bounds.startOffset } ?: break
            unopened += -delta(before)
            start = before.textRange.startOffset
        }
        var after = last
        while (open > 0) {
            after = PsiTreeUtil.nextLeaf(after)?.takeIf { it.textRange.endOffset <= bounds.endOffset } ?: break
            open += delta(after)
            end = after.textRange.endOffset
        }
        return TextRange(start, end)
    }
}
