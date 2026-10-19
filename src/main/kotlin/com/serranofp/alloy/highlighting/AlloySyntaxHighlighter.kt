package com.serranofp.alloy.highlighting

import com.intellij.lexer.Lexer
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.HighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey
import com.intellij.openapi.fileTypes.SyntaxHighlighter
import com.intellij.openapi.fileTypes.SyntaxHighlighterBase
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.TokenType
import com.intellij.psi.tree.IElementType
import com.serranofp.alloy.lexer.AlloyLexer
import com.serranofp.alloy.lexer.AlloyTokenSets
import com.serranofp.alloy.psi.AlloyTypes

object AlloyHighlighterColors {
    @JvmField val KEYWORD = createTextAttributesKey("ALLOY_KEYWORD", DefaultLanguageHighlighterColors.KEYWORD)
    @JvmField val TEMPORAL_OPERATOR = createTextAttributesKey("ALLOY_TEMPORAL_OPERATOR", DefaultLanguageHighlighterColors.KEYWORD)
    @JvmField val OPERATOR = createTextAttributesKey("ALLOY_OPERATOR", DefaultLanguageHighlighterColors.OPERATION_SIGN)
    @JvmField val IDENTIFIER = createTextAttributesKey("ALLOY_IDENTIFIER", DefaultLanguageHighlighterColors.IDENTIFIER)
    @JvmField val NUMBER = createTextAttributesKey("ALLOY_NUMBER", DefaultLanguageHighlighterColors.NUMBER)
    @JvmField val STRING = createTextAttributesKey("ALLOY_STRING", DefaultLanguageHighlighterColors.STRING)
    @JvmField val LINE_COMMENT = createTextAttributesKey("ALLOY_LINE_COMMENT", DefaultLanguageHighlighterColors.LINE_COMMENT)
    @JvmField val BLOCK_COMMENT = createTextAttributesKey("ALLOY_BLOCK_COMMENT", DefaultLanguageHighlighterColors.BLOCK_COMMENT)
    @JvmField val DOC_COMMENT = createTextAttributesKey("ALLOY_DOC_COMMENT", DefaultLanguageHighlighterColors.DOC_COMMENT)
    @JvmField val BRACES = createTextAttributesKey("ALLOY_BRACES", DefaultLanguageHighlighterColors.BRACES)
    @JvmField val PARENTHESES = createTextAttributesKey("ALLOY_PARENTHESES", DefaultLanguageHighlighterColors.PARENTHESES)
    @JvmField val BRACKETS = createTextAttributesKey("ALLOY_BRACKETS", DefaultLanguageHighlighterColors.BRACKETS)
    @JvmField val COMMA = createTextAttributesKey("ALLOY_COMMA", DefaultLanguageHighlighterColors.COMMA)
    @JvmField val DOT = createTextAttributesKey("ALLOY_DOT", DefaultLanguageHighlighterColors.DOT)
    @JvmField val BAD_CHARACTER = createTextAttributesKey("ALLOY_BAD_CHARACTER", HighlighterColors.BAD_CHARACTER)
}

class AlloySyntaxHighlighter : SyntaxHighlighterBase() {
    override fun getHighlightingLexer(): Lexer = AlloyLexer()

    override fun getTokenHighlights(tokenType: IElementType?): Array<TextAttributesKey> =
        pack(attributesFor(tokenType))

    private fun attributesFor(tokenType: IElementType?): TextAttributesKey? = when {
        tokenType == null -> null
        AlloyTokenSets.KEYWORDS.contains(tokenType) -> AlloyHighlighterColors.KEYWORD
        AlloyTokenSets.TEMPORAL_OPERATORS.contains(tokenType) -> AlloyHighlighterColors.TEMPORAL_OPERATOR
        AlloyTokenSets.OPERATORS.contains(tokenType) -> AlloyHighlighterColors.OPERATOR
        AlloyTokenSets.BRACES.contains(tokenType) -> AlloyHighlighterColors.BRACES
        AlloyTokenSets.PARENTHESES.contains(tokenType) -> AlloyHighlighterColors.PARENTHESES
        AlloyTokenSets.BRACKETS.contains(tokenType) -> AlloyHighlighterColors.BRACKETS
        tokenType == AlloyTypes.COMMA -> AlloyHighlighterColors.COMMA
        tokenType == AlloyTypes.DOT -> AlloyHighlighterColors.DOT
        tokenType == AlloyTypes.IDENTIFIER -> AlloyHighlighterColors.IDENTIFIER
        tokenType == AlloyTypes.NUMBER -> AlloyHighlighterColors.NUMBER
        tokenType == AlloyTypes.STRING_LITERAL -> AlloyHighlighterColors.STRING
        tokenType == AlloyTypes.LINE_COMMENT -> AlloyHighlighterColors.LINE_COMMENT
        tokenType == AlloyTypes.BLOCK_COMMENT -> AlloyHighlighterColors.BLOCK_COMMENT
        tokenType == AlloyTypes.DOC_COMMENT -> AlloyHighlighterColors.DOC_COMMENT
        tokenType == TokenType.BAD_CHARACTER -> AlloyHighlighterColors.BAD_CHARACTER
        else -> null
    }
}

class AlloySyntaxHighlighterFactory : SyntaxHighlighterFactory() {
    override fun getSyntaxHighlighter(project: Project?, virtualFile: VirtualFile?): SyntaxHighlighter =
        AlloySyntaxHighlighter()
}
