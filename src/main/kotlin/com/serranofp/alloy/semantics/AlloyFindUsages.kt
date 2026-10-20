package com.serranofp.alloy.semantics

import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet
import com.intellij.usages.impl.rules.UsageType
import com.intellij.usages.impl.rules.UsageTypeProvider
import com.serranofp.alloy.lexer.AlloyLexer
import com.serranofp.alloy.lexer.AlloyTokenSets
import com.serranofp.alloy.psi.AlloyAssertDecl
import com.serranofp.alloy.psi.AlloyBinderDecl
import com.serranofp.alloy.psi.AlloyCommandDecl
import com.serranofp.alloy.psi.AlloyEnumDecl
import com.serranofp.alloy.psi.AlloyEnumMember
import com.serranofp.alloy.psi.AlloyFactDecl
import com.serranofp.alloy.psi.AlloyFieldDecl
import com.serranofp.alloy.psi.AlloyFunDecl
import com.serranofp.alloy.psi.AlloyLetDecl
import com.serranofp.alloy.psi.AlloyMacroDecl
import com.serranofp.alloy.psi.AlloyNamedDeclaration
import com.serranofp.alloy.psi.AlloyParamDecl
import com.serranofp.alloy.psi.AlloyPredDecl
import com.serranofp.alloy.psi.AlloySigName
import com.serranofp.alloy.psi.AlloyTypes

/** Find Usages on Alloy declarations; usages are found through the identifiers of the word index. */
class AlloyFindUsagesProvider : FindUsagesProvider {
    override fun getWordsScanner(): WordsScanner =
        DefaultWordsScanner(AlloyLexer(), TokenSet.create(AlloyTypes.IDENTIFIER), AlloyTokenSets.COMMENTS, AlloyTokenSets.STRINGS)

    override fun canFindUsagesFor(element: PsiElement): Boolean = element is AlloyNamedDeclaration && element.name != null

    override fun getHelpId(element: PsiElement): String? = null

    override fun getType(element: PsiElement): String = when (element) {
        is AlloySigName -> "signature"
        is AlloyFieldDecl -> "field"
        is AlloyEnumDecl -> "enum"
        is AlloyEnumMember -> "enum member"
        is AlloyPredDecl -> "predicate"
        is AlloyFunDecl -> "function"
        is AlloyAssertDecl -> "assertion"
        is AlloyMacroDecl -> "macro"
        is AlloyFactDecl -> "fact"
        is AlloyCommandDecl -> "command"
        is AlloyParamDecl -> "parameter"
        is AlloyBinderDecl, is AlloyLetDecl -> "variable"
        else -> "declaration"
    }

    override fun getDescriptiveName(element: PsiElement): String = (element as? AlloyNamedDeclaration)?.name.orEmpty()

    override fun getNodeText(element: PsiElement, useFullName: Boolean): String = getDescriptiveName(element)
}

/**
 * Separates the usages that the analyzer confirmed from those found by name in a file whose model does not
 * type-check, which may be wrong (an overloaded field, for example).
 */
class AlloyUsageTypeProvider : UsageTypeProvider {
    override fun getUsageType(element: PsiElement): UsageType? {
        val reference = element.reference as? AlloyReference ?: return null
        return if (reference.isExact()) CONFIRMED else UNCONFIRMED
    }

    companion object {
        val CONFIRMED = UsageType { "Usages confirmed by the analyzer" }
        val UNCONFIRMED = UsageType { "Unconfirmed usages (the model has errors)" }
    }
}
