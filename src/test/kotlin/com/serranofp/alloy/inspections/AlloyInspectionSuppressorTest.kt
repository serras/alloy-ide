package com.serranofp.alloy.inspections

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AlloyInspectionSuppressorTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(AlloyTestInspection())
    }

    private fun reported(): List<String> =
        myFixture.doHighlighting(HighlightSeverity.WEAK_WARNING)
            .filter { it.severity == HighlightSeverity.WEAK_WARNING }
            .map { myFixture.file.text.substring(it.startOffset, it.endOffset) }

    fun testSuppressedPredicateIsNotReported() {
        myFixture.configureByText("model.als", "// noinspection AlloyTest\npred a {}\npred b {}")
        assertEquals(listOf("b"), reported())
    }

    fun testCommentForAnotherInspectionDoesNotSuppress() {
        myFixture.configureByText("model.als", "// noinspection AlloyOther\npred a {}\npred b {}")
        assertEquals(listOf("a", "b"), reported())
    }

    fun testDashDashComment() {
        myFixture.configureByText("model.als", "-- noinspection AlloyTest\npred a {}\npred b {}")
        assertEquals(listOf("b"), reported())
    }

    fun testTrailingCommentOfThePreviousParagraph() {
        myFixture.configureByText("model.als", "pred a {} // noinspection AlloyTest\npred b {}")
        assertEquals(listOf("a", "b"), reported())
    }

    fun testSuppressionAmongOtherComments() {
        myFixture.configureByText("model.als", "// noinspection AlloyOther, AlloyTest\n/** A predicate. */\npred a {}\nrun b {} for 3")
        assertEquals(listOf("for 3"), reported())
    }

    fun testSuppressForPredicateWritesTheComment() {
        myFixture.configureByText("model.als", "pred a {}\n\npred <caret>b {}\n")
        myFixture.doHighlighting()
        myFixture.launchAction(myFixture.findSingleIntention("Suppress for predicate"))
        myFixture.checkResult("pred a {}\n\n// noinspection AlloyTest\npred b {}\n")
        assertEquals(listOf("a"), reported())
    }

    fun testSuppressForCommandAddsToAnExistingComment() {
        myFixture.configureByText("model.als", "// noinspection AlloyOther\nrun {} <caret>for 3")
        myFixture.doHighlighting()
        myFixture.launchAction(myFixture.findSingleIntention("Suppress for command"))
        myFixture.checkResult("// noinspection AlloyOther,AlloyTest\nrun {} for 3")
        assertEmpty(reported())
    }
}
