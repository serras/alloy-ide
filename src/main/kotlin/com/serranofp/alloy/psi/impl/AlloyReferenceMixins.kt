package com.serranofp.alloy.psi.impl

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiReference
import com.intellij.psi.util.PsiTreeUtil
import com.serranofp.alloy.psi.AlloyBlock
import com.serranofp.alloy.psi.AlloyCommandDecl
import com.serranofp.alloy.psi.AlloyFunDecl
import com.serranofp.alloy.psi.AlloyPredDecl
import com.serranofp.alloy.psi.AlloyQualifiedName
import com.serranofp.alloy.psi.AlloySigDecl
import com.serranofp.alloy.semantics.AlloyReference

/** An identifier use inside a paragraph. */
abstract class AlloyRefMixin(node: ASTNode) : ASTWrapperPsiElement(node) {
    override fun getReference(): PsiReference = AlloyReference(this)
}

/**
 * A name in a paragraph's header. It is a reference where it names something declared elsewhere: the parents
 * of a signature, the receiver of `pred A.p`, and the target and scope of a command. Module paths, aliases
 * and the names of facts, assertions, enums, predicates, functions and macros are not.
 */
abstract class AlloyQualifiedNameMixin(node: ASTNode) : ASTWrapperPsiElement(node) {
    override fun getReference(): PsiReference? = if (isReference()) AlloyReference(this) else null

    private fun isReference(): Boolean = when (val paragraph = parent) {
        is AlloySigDecl -> true
        // The last name is the declared one
        is AlloyPredDecl, is AlloyFunDecl -> PsiTreeUtil.getChildrenOfTypeAsList(paragraph, AlloyQualifiedName::class.java).lastOrNull() !== this
        // `run label { ... }` labels its block; `run show for 3` targets `show`
        is AlloyCommandDecl -> PsiTreeUtil.skipWhitespacesAndCommentsForward(this) !is AlloyBlock
        else -> false
    }
}
