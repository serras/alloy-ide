package com.serranofp.alloy.editor

import com.intellij.lang.ASTNode
import com.intellij.lang.folding.FoldingBuilderEx
import com.intellij.lang.folding.FoldingDescriptor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.util.PsiTreeUtil
import com.serranofp.alloy.psi.AlloyBlock
import com.serranofp.alloy.psi.AlloyOpenDecl
import com.serranofp.alloy.psi.AlloyTypes

class AlloyFoldingBuilder : FoldingBuilderEx(), DumbAware {
    override fun buildFoldRegions(root: PsiElement, document: Document, quick: Boolean): Array<FoldingDescriptor> {
        val file = root as? PsiFile ?: return FoldingDescriptor.EMPTY_ARRAY
        val descriptors = mutableListOf<FoldingDescriptor>()

        // Bodies of top-level paragraphs (nested blocks inside a body are expressions, not bodies)
        for (paragraph in file.children) {
            for (block in paragraph.children.filterIsInstance<AlloyBlock>()) {
                addIfMultiLine(descriptors, block.node, block.textRange, document, "{...}")
            }
        }

        PsiTreeUtil.processElements(file, PsiComment::class.java) { comment ->
            when (comment.tokenType) {
                AlloyTypes.BLOCK_COMMENT -> addIfMultiLine(descriptors, comment.node, comment.textRange, document, "/*...*/")
                AlloyTypes.DOC_COMMENT -> addIfMultiLine(descriptors, comment.node, comment.textRange, document, "/**...*/")
            }
            true
        }

        for (group in consecutiveOpens(file)) {
            if (group.size >= 2) {
                val range = TextRange(group.first().textRange.startOffset, group.last().textRange.endOffset)
                descriptors += FoldingDescriptor(group.first().node, range, null, "open ...")
            }
        }

        return descriptors.toTypedArray()
    }

    /** Groups of `open` declarations separated only by whitespace and comments. */
    private fun consecutiveOpens(file: PsiFile): List<List<AlloyOpenDecl>> {
        val groups = mutableListOf<MutableList<AlloyOpenDecl>>()
        var current: MutableList<AlloyOpenDecl>? = null
        for (child in file.children) {
            when (child) {
                is AlloyOpenDecl -> current?.add(child) ?: mutableListOf(child).also { current = it; groups += it }
                is PsiWhiteSpace, is PsiComment -> Unit
                else -> current = null
            }
        }
        return groups
    }

    private fun addIfMultiLine(
        descriptors: MutableList<FoldingDescriptor>,
        node: ASTNode,
        range: TextRange,
        document: Document,
        placeholder: String,
    ) {
        if (document.getLineNumber(range.startOffset) != document.getLineNumber(range.endOffset)) {
            descriptors += FoldingDescriptor(node, range, null, placeholder)
        }
    }

    /** Required by `FoldingBuilder`, but never asked for: every descriptor above has its own placeholder. */
    override fun getPlaceholderText(node: ASTNode): String = "..."

    override fun isCollapsedByDefault(node: ASTNode): Boolean = false
}
