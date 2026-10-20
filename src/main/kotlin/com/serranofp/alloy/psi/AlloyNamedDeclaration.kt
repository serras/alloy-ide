package com.serranofp.alloy.psi

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

object AlloyElementFactory {
    /** A fresh `IDENTIFIER` leaf with [name], to replace a declaration's or reference's identifier. */
    fun identifier(project: Project, name: String): PsiElement {
        val file = PsiFileFactory.getInstance(project).createFileFromText("dummy.als", AlloyLanguage, "sig $name {}")
        return PsiTreeUtil.findChildOfType(file, AlloySigName::class.java)?.node?.findChildByType(AlloyTypes.IDENTIFIER)?.psi
            ?: throw IllegalArgumentException("Not an Alloy name: $name")
    }
}
