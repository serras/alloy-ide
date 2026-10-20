package com.serranofp.alloy.semantics

import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReferenceBase
import com.serranofp.alloy.psi.AlloyFile
import com.serranofp.alloy.psi.AlloyOpenDecl

/**
 * The path of an `open` declaration, which resolves to the module that the analyzer opens for it: a project
 * file, or else a bundled library module. Renaming or moving that file leaves the path as it is.
 */
class AlloyModuleReference(element: PsiElement) : PsiReferenceBase<PsiElement>(element, TextRange(0, element.textLength), false) {
    override fun resolve(): PsiElement? {
        val file = element.containingFile as? AlloyFile ?: return null
        val open = element.parent as? AlloyOpenDecl ?: return null
        return AlloyModelService.getInstance(element.project).openedModule(file, open)
    }

    override fun handleElementRename(newElementName: String): PsiElement = element

    override fun bindToElement(element: PsiElement): PsiElement = this.element

    override fun getVariants(): Array<Any> = emptyArray()
}
