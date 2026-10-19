package com.serranofp.alloy.editor

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AlloyKeywordCompletionTest : BasePlatformTestCase() {
    fun testKeywordListIsComplete() {
        assertContainsElements(KEYWORDS, "abstract", "sig", "Int", "always", "triggered", "steps")
        assertDoesntContain(KEYWORDS, "'", ";")
    }

    fun testUniquePrefixCompletesKeyword() {
        myFixture.configureByText("model.als", "ab<caret>")
        assertNull("single match is inserted directly", myFixture.completeBasic())
        myFixture.checkResult("abstract<caret>")
    }

    fun testPrefixOffersMatchingKeywords() {
        myFixture.configureByText("model.als", "sig A {}\na<caret>")
        myFixture.completeBasic()
        assertContainsElements(myFixture.lookupElementStrings!!, "abstract", "all", "always", "assert", "after")
    }

    fun testNoKeywordsInLineComment() = assertNoKeywords("sig A {} // ab<caret>")

    fun testNoKeywordsInBlockComment() = assertNoKeywords("/* a<caret> */ sig A {}")

    fun testNoKeywordsInString() = assertNoKeywords("fact { \"a<caret>\" }")

    private fun assertNoKeywords(text: String) {
        myFixture.configureByText("model.als", text)
        val before = myFixture.editor.document.text
        val items = myFixture.completeBasic()?.map { it.lookupString }.orEmpty()
        assertEmpty(items.filter { it in KEYWORDS })
        assertEquals("nothing was inserted", before, myFixture.editor.document.text)
    }
}
