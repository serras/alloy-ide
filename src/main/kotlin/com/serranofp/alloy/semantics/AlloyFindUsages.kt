package com.serranofp.alloy.semantics

import com.intellij.lang.cacheBuilder.DefaultWordsScanner
import com.intellij.lang.cacheBuilder.WordsScanner
import com.intellij.lang.findUsages.FindUsagesProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.TokenSet
import com.intellij.usages.impl.rules.UsageType
import com.intellij.usages.impl.rules.UsageTypeProvider
import com.serranofp.alloy.AlloyBundle
import com.serranofp.alloy.editor.AlloyPresentation
import com.serranofp.alloy.lexer.AlloyLexer
import com.serranofp.alloy.lexer.AlloyTokenSets
import com.serranofp.alloy.psi.AlloyNamedDeclaration
import com.serranofp.alloy.psi.AlloyTypes

/** Find Usages on Alloy declarations; usages are found through the identifiers of the word index. */
class AlloyFindUsagesProvider : FindUsagesProvider {
    override fun getWordsScanner(): WordsScanner =
        DefaultWordsScanner(AlloyLexer(), TokenSet.create(AlloyTypes.IDENTIFIER), AlloyTokenSets.COMMENTS, AlloyTokenSets.STRINGS)

    override fun canFindUsagesFor(element: PsiElement): Boolean = element is AlloyNamedDeclaration && element.name != null

    override fun getHelpId(element: PsiElement): String? = null

    override fun getType(element: PsiElement): String = AlloyPresentation.kind(element) ?: AlloyBundle.message("kind.declaration")

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
        return if (reference.isExact()) Usages.CONFIRMED else Usages.UNCONFIRMED
    }

    object Usages {
        val CONFIRMED = UsageType { AlloyBundle.message("usage.type.confirmed") }
        val UNCONFIRMED = UsageType { AlloyBundle.message("usage.type.unconfirmed") }
    }
}
