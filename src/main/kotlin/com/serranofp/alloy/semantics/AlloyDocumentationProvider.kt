package com.serranofp.alloy.semantics

import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.serranofp.alloy.lexer.AlloyTokenSets
import com.serranofp.alloy.psi.AlloyBlock
import com.serranofp.alloy.psi.AlloyFieldDecl
import com.serranofp.alloy.psi.AlloyNamedDeclaration
import com.serranofp.alloy.psi.AlloySigDecl
import com.serranofp.alloy.psi.AlloySigName
import com.serranofp.alloy.psi.AlloyTypes
import com.serranofp.alloy.psi.declarationNodes

/**
 * Hover and Quick Documentation for a declaration: how the analyzer explains it when the model type-checks,
 * or else its source up to its body, followed by its `/** ... */` comment if it has one.
 */
class AlloyDocumentationProvider : AbstractDocumentationProvider() {
    override fun generateDoc(element: PsiElement?, originalElement: PsiElement?): String? {
        val declaration = element as? AlloyNamedDeclaration ?: return null
        val definition = originalElement?.let { explanation(it, declaration) } ?: source(declaration)
        return buildString {
            append(DocumentationMarkup.DEFINITION_START)
            append(StringUtil.escapeXmlEntities(definition))
            append(DocumentationMarkup.DEFINITION_END)
            docComment(declaration)?.let {
                append(DocumentationMarkup.CONTENT_START)
                append(StringUtil.escapeXmlEntities(it).replace("\n", "<br>"))
                append(DocumentationMarkup.CONTENT_END)
            }
        }
    }

    /**
     * The declaration as written, without its body: the header of a paragraph or signature, or a field,
     * parameter or binder with its bound (`a, b: set A` for `b` gives `b: set A`).
     */
    fun source(declaration: AlloyNamedDeclaration): String {
        val paragraph = if (declaration is AlloySigName) declaration.parent as AlloySigDecl else declaration
        if (paragraph.parent is PsiFile) {
            val body = paragraph.children.firstOrNull { it is AlloyBlock }
            val end = body?.textRange?.startOffset ?: paragraph.textRange.endOffset
            return normalize(paragraph.containingFile.text.substring(paragraph.textRange.startOffset, end))
        }
        return normalize(declarationNodes(declaration).joinToString("") { it.text })
    }

    private fun normalize(text: String) = text.trim().replace(Regex("\\s+"), " ")

    /**
     * The text of the `/** ... */` comment right before the declaration's paragraph, or before its group of
     * fields: `/** doc */ var a, b: A` documents `a` and `b`.
     */
    fun docComment(declaration: AlloyNamedDeclaration): String? {
        val owner = if (declaration is AlloySigName) declaration.parent else declaration
        var previous = PsiTreeUtil.skipWhitespacesBackward(owner)
        if (owner is AlloyFieldDecl) {
            while (previous is AlloyFieldDecl || previous?.node?.elementType in AlloyTokenSets.FIELD_GROUP) {
                previous = PsiTreeUtil.skipWhitespacesBackward(previous!!)
            }
        }
        val comment = previous as? PsiComment ?: return null
        if (comment.tokenType != AlloyTypes.DOC_COMMENT) return null
        return comment.text.removePrefix("/**").removeSuffix("*/")
            .lines().joinToString("\n") { it.trim().removePrefix("*").trim() }
            .trim()
    }

    override fun generateHoverDoc(element: PsiElement, originalElement: PsiElement?): String? = generateDoc(element, originalElement)

    /**
     * The analyzer's explanation of the use at [original], the element under the caret or mouse, if it refers to
     * [declaration]: when completion shows the documentation of its items, [original] is the use being typed.
     */
    private fun explanation(original: PsiElement, declaration: AlloyNamedDeclaration): String? {
        val reference = generateSequence(original) { it.parent.takeUnless { parent -> parent is PsiFile } }
            .firstNotNullOfOrNull { it.reference as? AlloyReference } ?: return null
        val resolution = reference.exactResolution()?.takeIf { it.declaration == declaration } ?: return null
        return resolution.referenced.explain()
    }
}
