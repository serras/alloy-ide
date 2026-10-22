package com.serranofp.alloy.inspections

import com.intellij.codeInspection.InspectionSuppressor
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.SuppressQuickFix
import com.intellij.codeInspection.SuppressionUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import com.serranofp.alloy.psi.AlloyCommandDecl
import com.serranofp.alloy.psi.AlloyFile
import com.serranofp.alloy.psi.AlloyModuleDecl
import com.serranofp.alloy.psi.AlloyOpenDecl
import com.serranofp.alloy.psi.AlloyPredDecl
import com.serranofp.alloy.psi.AlloyTypes

/**
 * A `// noinspection <id>[,<id>]` line comment right before a top-level paragraph or command suppresses those
 * inspections inside it, in the format of [SuppressionUtil]. Other comments may stand between the two.
 */
class AlloyInspectionSuppressor : InspectionSuppressor {
    override fun isSuppressedFor(element: PsiElement, toolId: String): Boolean {
        val paragraph = Paragraph.paragraph(element) ?: return false
        return Paragraph.suppressionComments(paragraph).any { comment ->
            val matcher = SuppressionUtil.SUPPRESS_IN_LINE_COMMENT_PATTERN.matcher(comment.text)
            matcher.matches() && SuppressionUtil.isInspectionToolIdMentioned(matcher.group(1), toolId)
        }
    }

    override fun getSuppressActions(element: PsiElement?, toolId: String): Array<SuppressQuickFix> {
        val paragraph = element?.let(Paragraph::paragraph) ?: return SuppressQuickFix.EMPTY_ARRAY
        val kind = when (paragraph) {
            is AlloyPredDecl -> "predicate"
            is AlloyCommandDecl -> "command"
            else -> "paragraph"
        }
        return arrayOf(SuppressForParagraphFix(toolId, kind))
    }

    /** Adds [toolId] to the suppression comment before the paragraph, or writes one on the line before it. */
    private class SuppressForParagraphFix(private val toolId: String, private val kind: String) : SuppressQuickFix {
        override fun getFamilyName(): String = "Suppress for $kind"

        override fun isAvailable(project: Project, context: PsiElement): Boolean = Paragraph.paragraph(context) != null

        override fun isSuppressAll(): Boolean = false

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val paragraph = descriptor.psiElement?.let(Paragraph::paragraph) ?: return
            val file = paragraph.containingFile
            val documents = PsiDocumentManager.getInstance(project)
            val document = documents.getDocument(file) ?: return
            val existing = Paragraph.suppressionComments(paragraph).firstOrNull { SuppressionUtil.SUPPRESS_IN_LINE_COMMENT_PATTERN.matcher(it.text).matches() }
            if (existing != null) {
                val matcher = SuppressionUtil.SUPPRESS_IN_LINE_COMMENT_PATTERN.matcher(existing.text)
                matcher.matches()
                document.insertString(existing.textRange.startOffset + matcher.end(1), ",$toolId")
            } else {
                val start = paragraph.textRange.startOffset
                val lineStart = document.getLineStartOffset(document.getLineNumber(start))
                val indent = document.getText(TextRange(lineStart, start)).takeWhile { it == ' ' || it == '\t' }
                document.insertString(start, "// noinspection $toolId\n$indent")
            }
            documents.commitDocument(document)
        }
    }

    object Paragraph {
        /** The top-level paragraph or command around [element]; `module` and `open` declarations are not. */
        fun paragraph(element: PsiElement): PsiElement? {
            val paragraph = PsiTreeUtil.findFirstParent(element, false) { it.parent is PsiFile } ?: return null
            if (paragraph.containingFile !is AlloyFile || paragraph is PsiWhiteSpace || paragraph is PsiComment) return null
            if (paragraph is AlloyModuleDecl || paragraph is AlloyOpenDecl) return null
            return paragraph
        }

        /** The line comments in the run of comments right before [paragraph]. */
        fun suppressionComments(paragraph: PsiElement): List<PsiComment> =
            generateSequence(paragraph.prevSibling) { it.prevSibling }
                .takeWhile { it is PsiWhiteSpace || it is PsiComment }
                .filterIsInstance<PsiComment>()
                .filter { it.node.elementType == AlloyTypes.LINE_COMMENT }
                .toList()
    }
}
