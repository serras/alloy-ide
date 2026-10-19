package com.serranofp.alloy.parser

import com.intellij.lang.ASTNode
import com.intellij.lang.ParserDefinition
import com.intellij.lang.PsiParser
import com.intellij.lexer.Lexer
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.tree.IFileElementType
import com.intellij.psi.tree.TokenSet
import com.serranofp.alloy.AlloyLanguage
import com.serranofp.alloy.lexer.AlloyLexer
import com.serranofp.alloy.lexer.AlloyTokenSets
import com.serranofp.alloy.psi.AlloyFile
import com.serranofp.alloy.psi.AlloyTypes

class AlloyParserDefinition : ParserDefinition {
    override fun createLexer(project: Project?): Lexer = AlloyLexer()

    override fun createParser(project: Project?): PsiParser = AlloyParser()

    override fun getFileNodeType(): IFileElementType = IFileElementType(AlloyLanguage)

    override fun getCommentTokens(): TokenSet = AlloyTokenSets.COMMENTS

    override fun getStringLiteralElements(): TokenSet = AlloyTokenSets.STRINGS

    override fun createElement(node: ASTNode): PsiElement = AlloyTypes.Factory.createElement(node)

    override fun createFile(viewProvider: FileViewProvider): PsiFile = AlloyFile(viewProvider)
}
