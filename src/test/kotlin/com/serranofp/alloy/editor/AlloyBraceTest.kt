package com.serranofp.alloy.editor

import com.intellij.codeInsight.highlighting.BraceMatchingUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.serranofp.alloy.AlloyFileType

class AlloyBraceTest : BasePlatformTestCase() {
    fun testTypingOpeningBraceInsertsClosingBrace() {
        myFixture.configureByText("model.als", "sig A <caret>")
        myFixture.type('{')
        myFixture.checkResult("sig A {<caret>}")
    }

    fun testTypingOpeningBracketInsertsClosingBracket() {
        myFixture.configureByText("model.als", "pred p<caret>")
        myFixture.type('[')
        myFixture.checkResult("pred p[<caret>]")
    }

    fun testTypingOpeningParenInsertsClosingParen() {
        myFixture.configureByText("model.als", "fact { some <caret> }")
        myFixture.type('(')
        myFixture.checkResult("fact { some (<caret>) }")
    }

    fun testMatchesSignatureBody() = assertMatch("sig A <caret>{ f: set A }", "sig A { f: set A ".length)

    fun testMatchesParentheses() = assertMatch("fact { some <caret>(A + B) }", "fact { some (A + B".length)

    fun testMatchesBrackets() = assertMatch("pred p<caret>[x: A] {}", "pred p[x: A".length)

    fun testMatchesNestedBraces() = assertMatch("fact <caret>{ all x: A | { some x } }", "fact { all x: A | { some x } ".length)

    private fun assertMatch(text: String, expectedOffset: Int) {
        myFixture.configureByText(AlloyFileType, text)
        val offset = BraceMatchingUtil.getMatchedBraceOffset(myFixture.editor, true, myFixture.file)
        assertEquals(expectedOffset, offset)
    }
}
