package com.serranofp.alloy.editor

import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** One golden file per kind of region, named after the test. */
class AlloyFoldingTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String = "src/test/testData/folding"

    private fun doTest() = myFixture.testFolding("$testDataPath/${getTestName(false)}.als")

    /** Signature and paragraph bodies over several lines, not those on one line nor the blocks inside them. */
    fun testBodies() = doTest()

    /** Block and doc comments over several lines; not those on one line, nor line comments. */
    fun testComments() = doTest()

    /** Consecutive `open`s, also with comments between them; a single `open` is not folded. */
    fun testOpenGroups() = doTest()
}
