package com.serranofp.alloy.parser

import com.intellij.testFramework.ParsingTestCase

class AlloyParserTest : ParsingTestCase("parser", "als", AlloyParserDefinition()) {
    override fun getTestDataPath(): String = "src/test/testData"

    override fun includeRanges(): Boolean = true

    fun testModule() = doTest(true, true)

    fun testOpen() = doTest(true, true)

    fun testSig() = doTest(true, true)

    fun testEnum() = doTest(true, true)

    fun testFact() = doTest(true, true)

    fun testAssert() = doTest(true, true)

    fun testPred() = doTest(true, true)

    fun testFun() = doTest(true, true)

    fun testMacro() = doTest(true, true)

    fun testCommand() = doTest(true, true)

    fun testParagraphs() = doTest(true, true)

    /** Unknown names and ill-typed expressions are the language server's business. */
    fun testTypeErrorInBody() = doTest(true, true)

    fun testBrokenSig() = doTest(true)

    fun testUnclosedBody() = doTest(true)

    fun testTopLevelGarbage() = doTest(true)
}
