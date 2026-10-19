package com.serranofp.alloy.lexer

import com.intellij.lexer.Lexer
import com.intellij.testFramework.LexerTestCase

class AlloyLexerTest : LexerTestCase() {
    override fun createLexer(): Lexer = AlloyLexer()

    override fun getDirPath(): String = "src/test/testData/lexer"

    override fun getPathToTestDataFile(extension: String): String = "${getDirPath()}/${getTestName(true)}$extension"

    fun testKeywords() = doFileTest("als")

    fun testTemporal() = doFileTest("als")

    fun testOperators() = doFileTest("als")

    fun testIdentifiers() = doFileTest("als")

    fun testNumbers() = doFileTest("als")

    fun testStrings() = doFileTest("als")

    fun testComments() = doFileTest("als")

    fun testBadCharacters() = doFileTest("als")

    fun testUnterminatedBlockComment() = doFileTest("als")

    fun testUnterminatedDocComment() = doFileTest("als")

    fun testUnterminatedString() = doFileTest("als")

    fun testUnterminatedStringBackslash() = doFileTest("als")

    fun testLineCommentAtEof() = doFileTest("als")

    fun testRestart() = checkCorrectRestart(
        """
        module example
        /** doc */ sig A { f: set A }
        pred p[x: A] { always some x.f' and "s" = "s" } -- note
        run p for 3 but 5 Int
        """.trimIndent()
    )
}
