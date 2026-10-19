package com.serranofp.alloy

import com.intellij.lang.Language

object AlloyLanguage : Language("Alloy") {
    private fun readResolve(): Any = AlloyLanguage

    override fun getDisplayName(): String = "Alloy"
}
