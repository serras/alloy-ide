package com.serranofp.alloy.psi

import com.intellij.psi.tree.IElementType
import com.serranofp.alloy.AlloyLanguage

class AlloyTokenType(debugName: String) : IElementType(debugName, AlloyLanguage) {
    override fun toString(): String = "AlloyTokenType." + super.toString()
}
