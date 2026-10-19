package com.serranofp.alloy

import com.intellij.openapi.fileTypes.LanguageFileType
import javax.swing.Icon

object AlloyFileType : LanguageFileType(AlloyLanguage) {
    override fun getName(): String = "Alloy"

    override fun getDescription(): String = "Alloy model"

    override fun getDefaultExtension(): String = "als"

    override fun getIcon(): Icon = AlloyIcons.FILE
}
