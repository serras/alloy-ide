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
import com.serranofp.alloy.AlloyBundle
import com.serranofp.alloy.psi.AlloyCommandDecl
import com.serranofp.alloy.psi.AlloyFile
import com.serranofp.alloy.psi.AlloyModuleDecl
import com.serranofp.alloy.psi.AlloyOpenDecl
import com.serranofp.alloy.psi.AlloyPredDecl
import com.serranofp.alloy.psi.AlloyTypes
import java.util.regex.Pattern

/**
 * A `// noinspection <id>[,<id>]` (or `--`) line comment right before a top-level paragraph or command
 * suppresses those inspections inside it, in the format of [SuppressionUtil]. Other comments may stand between
 * the two.
 */
class AlloyInspectionSuppressor : InspectionSuppressor {
    override fun isSuppressedFor(element: PsiElement, toolId: String): Boolean {
        val paragraph = Paragraph.paragraph(element) ?: return false
        return Paragraph.suppressionComments(paragraph).any { comment ->
            val matcher = SUPPRESS_PATTERN.matcher(comment.text)
            matcher.matches() && SuppressionUtil.isInspectionToolIdMentioned(matcher.group(1), toolId)
        }
    }

    override fun getSuppressActions(element: PsiElement?, toolId: String): Array<SuppressQuickFix> {
        val paragraph = element?.let(Paragraph::paragraph) ?: return SuppressQuickFix.EMPTY_ARRAY
        val name = when (paragraph) {
            is AlloyPredDecl -> AlloyBundle.message("inspection.suppress.predicate")
            is AlloyCommandDecl -> AlloyBundle.message("inspection.suppress.command")
            else -> AlloyBundle.message("inspection.suppress.paragraph")
        }
        return arrayOf(SuppressForParagraphFix(toolId, name))
    }

    /** Adds [toolId] to the suppression comment before the paragraph, or writes one on the line before it. */
    private class SuppressForParagraphFix(private val toolId: String, private val name: String) : SuppressQuickFix {
        override fun getName(): String = name

        override fun getFamilyName(): String = AlloyBundle.message("inspection.suppress.family")

        override fun isAvailable(project: Project, context: PsiElement): Boolean = Paragraph.paragraph(context) != null

        override fun isSuppressAll(): Boolean = false

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val paragraph = descriptor.psiElement?.let(Paragraph::paragraph) ?: return
            val file = paragraph.containingFile
            val documents = PsiDocumentManager.getInstance(project)
            val document = documents.getDocument(file) ?: return
            val existing = Paragraph.suppressionComments(paragraph).firstOrNull { SUPPRESS_PATTERN.matcher(it.text).matches() }
            if (existing != null) {
                val matcher = SUPPRESS_PATTERN.matcher(existing.text)
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

        /**
         * The line comments in the run of comments right before [paragraph], each on a line of its own: one at
         * the end of the previous paragraph's line, as in `pred a {…} // noinspection X`, is about that paragraph.
         */
        fun suppressionComments(paragraph: PsiElement): List<PsiComment> =
            generateSequence(paragraph.prevSibling) { it.prevSibling }
                .takeWhile { it is PsiWhiteSpace || it is PsiComment }
                .filterIsInstance<PsiComment>()
                .filter { it.node.elementType == AlloyTypes.LINE_COMMENT && startsLine(it) }
                .toList()

        private fun startsLine(comment: PsiComment): Boolean {
            val before = comment.prevSibling ?: return true
            return before is PsiWhiteSpace && (before.textContains('\n') || before.prevSibling == null)
        }
    }

    private companion object {
        /** [SuppressionUtil.SUPPRESS_IN_LINE_COMMENT_PATTERN], for both kinds of Alloy line comments. */
        val SUPPRESS_PATTERN: Pattern = Pattern.compile("(?://|--)" + SuppressionUtil.COMMON_SUPPRESS_REGEXP + ".*")
    }
}
