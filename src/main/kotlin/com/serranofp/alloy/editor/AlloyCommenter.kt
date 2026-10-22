package com.serranofp.alloy.editor

import com.intellij.lang.CodeDocumentationAwareCommenter
import com.intellij.psi.PsiComment
import com.intellij.psi.tree.IElementType
import com.serranofp.alloy.psi.AlloyTypes

class AlloyCommenter : CodeDocumentationAwareCommenter {
    override fun getLineCommentPrefix(): String = "//"

    /**
     * Alloy also has `--` line comments: commenting writes `//`, and uncommenting would remove either. The
     * platform's *Comment Line* (2026.2) only reads [getLineCommentPrefix], so `--` lines are not uncommented yet.
     */
    override fun getLineCommentPrefixes(): List<String> = listOf("//", "--")

    override fun getBlockCommentPrefix(): String = "/*"

    override fun getBlockCommentSuffix(): String = "*/"

    override fun getCommentedBlockCommentPrefix(): String? = null

    override fun getCommentedBlockCommentSuffix(): String? = null

    override fun getLineCommentTokenType(): IElementType = AlloyTypes.LINE_COMMENT

    override fun getBlockCommentTokenType(): IElementType = AlloyTypes.BLOCK_COMMENT

    override fun getDocumentationCommentTokenType(): IElementType = AlloyTypes.DOC_COMMENT

    override fun getDocumentationCommentPrefix(): String = "/**"

    override fun getDocumentationCommentLinePrefix(): String = "*"

    override fun getDocumentationCommentSuffix(): String = "*/"

    override fun isDocumentationComment(element: PsiComment): Boolean = element.tokenType == AlloyTypes.DOC_COMMENT
}
