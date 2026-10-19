package com.serranofp.alloy.editor

import com.intellij.lang.BracePair
import com.intellij.lang.PairedBraceMatcher
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IElementType
import com.serranofp.alloy.psi.AlloyTypes

class AlloyBraceMatcher : PairedBraceMatcher {
    override fun getPairs(): Array<BracePair> = arrayOf(
        BracePair(AlloyTypes.LBRACE, AlloyTypes.RBRACE, true),
        BracePair(AlloyTypes.LPAREN, AlloyTypes.RPAREN, false),
        BracePair(AlloyTypes.LBRACKET, AlloyTypes.RBRACKET, false),
    )

    override fun isPairedBracesAllowedBeforeType(lbraceType: IElementType, contextType: IElementType?): Boolean = true

    override fun getCodeConstructStart(file: PsiFile, openingBraceOffset: Int): Int = openingBraceOffset
}
