package com.serranofp.alloy.psi

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.FileViewProvider
import com.serranofp.alloy.AlloyFileType
import com.serranofp.alloy.AlloyLanguage

class AlloyFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, AlloyLanguage) {
    override fun getFileType(): FileType = AlloyFileType

    override fun toString(): String = "Alloy file"
}

/**
 * The name in the file's `module` declaration, if it has one. The analyzer finds the modules a file opens
 * relative to it (see `AlloyModelService.openedPath`), where an absent name counts as `anything`.
 */
val AlloyFile.moduleName: String?
    get() = children.filterIsInstance<AlloyModuleDecl>().firstOrNull()?.qualifiedName?.text
