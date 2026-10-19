package com.serranofp.alloy.parser

import com.intellij.lang.PsiBuilder
import com.intellij.lang.parser.GeneratedParserUtilBase

object AlloyParserUtil : GeneratedParserUtilBase() {
    /** Consumes a single token of any type; used for the contents of balanced groups. */
    @JvmStatic
    fun anyToken(builder: PsiBuilder, @Suppress("UNUSED_PARAMETER") level: Int): Boolean {
        if (builder.eof()) return false
        builder.advanceLexer()
        return true
    }
}
