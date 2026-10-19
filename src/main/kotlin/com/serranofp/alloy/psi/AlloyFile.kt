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
