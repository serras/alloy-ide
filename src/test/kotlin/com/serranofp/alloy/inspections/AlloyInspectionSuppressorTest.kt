package com.serranofp.alloy.inspections

class AlloyInspectionSuppressorTest : AlloyInspectionTestCase() {
    override val inspection = AlloyTestInspection()

    private fun reported(): List<String> = problems().map { it.first }

    /** Which comments before `pred a` suppress `AlloyTest` in it. */
    fun testCommentForms() {
        val suppressed = listOf(
            "// noinspection AlloyTest",
            "-- noinspection AlloyTest",
            "//noinspection AlloyTest",
            "// noinspection AlloyOther,AlloyTest",
            "// noinspection ALL",
            "// noinspection AlloyTest\n// another comment",
            "// noinspection AlloyTest\n\n",
            // Only line comments are suppression comments
            "/* noinspection AlloyTest */",
            "/** noinspection AlloyTest */",
            "// noinspection AlloyTestOther",
            "// noinspection AlloyTest\nsig S {}",
        ).associateWith { comment ->
            myFixture.configureByText("model.als", "$comment\npred a {}\npred b {}")
            "a" !in reported()
        }
        assertEquals(listOf(true, true, true, true, true, true, true, false, false, false, false), suppressed.values.toList())
    }

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

    fun testSuppressKeepsTheIndentation() {
        myFixture.configureByText("model.als", "pred a {}\n\n    pred <caret>b {}\n")
        myFixture.doHighlighting()
        myFixture.launchAction(myFixture.findSingleIntention("Suppress for predicate"))
        myFixture.checkResult("pred a {}\n\n    // noinspection AlloyTest\n    pred b {}\n")
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
