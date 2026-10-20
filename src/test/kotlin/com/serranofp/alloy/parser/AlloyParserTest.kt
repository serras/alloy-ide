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

    /** Unknown names and ill-typed expressions are the bundled analyzer's business. */
    fun testTypeErrorInBody() = doTest(true, true)

    fun testBrokenSig() = doTest(true)

    fun testUnclosedBody() = doTest(true)

    fun testTopLevelGarbage() = doTest(true)

    /** A field `f`, a parameter `x`, a quantifier binder `y` and a `let` binder `z`. */
    fun testDeclarations() = doTest(true, true)

    /** `x` and `f` in `some x.f`, qualified names, `this/A` and `@f`. */
    fun testReferences() = doTest(true, true)

    /** A binder without a bound still declares `x`, and its use is still a reference. */
    fun testBrokenBinder() = doTest(true, true)

    /** The error stays inside the quantifier; the next predicate is untouched. */
    fun testQuantifierWithoutBar() = doTest(true)

    /** Several binder groups with `disj`, comprehensions, `sum`, block bodies and `let` with several bindings. */
    fun testBinders() = doTest(true, true)

    /** Enum members, untyped macro parameters and typed function parameters. */
    fun testParameters() = doTest(true, true)

    /** After `:`, `one Node, id: ...` is a multiplicity and the next declaration, not a quantifier. */
    fun testMultiplicities() = doTest(true, true)
}
