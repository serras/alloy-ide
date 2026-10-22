package com.serranofp.alloy.psi

import com.intellij.lang.ASTNode
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.util.PsiTreeUtil
import com.serranofp.alloy.AlloyLanguage

/**
 * An element that declares a name: a signature, field, enum or enum member, predicate, function, assertion
 * or macro, and the parameters and binders inside paragraphs. Its name identifier is an `IDENTIFIER` leaf.
 */
interface AlloyNamedDeclaration : PsiNameIdentifierOwner

/**
 * The nodes of a field, parameter, binder, `let` or enum member [declaration] with its bound, from its name to
 * the end of its group: `a, b: set (A + B)` for `a`, and `x = a` in `let x = a, y = b`. The names of a group share
 * its bound, so a comma only ends the group after its `:`. Brackets and parentheses are not elements of their own
 * (see `bracket_group` in `Alloy.bnf`), so their closers and commas are counted to find those of the group.
 */
fun declarationNodes(declaration: AlloyNamedDeclaration): List<ASTNode> {
    var inBound = declaration is AlloyLetDecl || declaration is AlloyEnumMember
    var depth = 0
    val nodes = mutableListOf<ASTNode>()
    var node: ASTNode? = declaration.node
    while (node != null) {
        val type = node.elementType
        if (depth == 0 && (type in GROUP_ENDS || (type == AlloyTypes.COMMA && inBound))) break
        when (type) {
            AlloyTypes.LPAREN, AlloyTypes.LBRACKET -> depth++
            AlloyTypes.RPAREN, AlloyTypes.RBRACKET -> depth--
            AlloyTypes.COLON -> if (depth == 0) inBound = true
        }
        nodes += node
        node = node.treeNext
    }
    return nodes
}

private val GROUP_ENDS = setOf(AlloyTypes.RBRACE, AlloyTypes.RBRACKET, AlloyTypes.RPAREN, AlloyTypes.BAR, AlloyTypes.BLOCK)

object AlloyElementFactory {
    /** A fresh `IDENTIFIER` leaf with [name], to replace a declaration's or reference's identifier. */
    fun identifier(project: Project, name: String): PsiElement {
        val file = PsiFileFactory.getInstance(project).createFileFromText("dummy.als", AlloyLanguage, "sig $name {}")
        return PsiTreeUtil.findChildOfType(file, AlloySigName::class.java)?.node?.findChildByType(AlloyTypes.IDENTIFIER)?.psi
            ?.takeIf { it.text == name }
            ?: throw IllegalArgumentException("Not an Alloy name: $name")
    }
}
