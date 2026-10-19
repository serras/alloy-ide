package com.serranofp.alloy.editor

import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AlloyCommenterTest : BasePlatformTestCase() {
    fun testLineCommentToggles() {
        myFixture.configureByText("model.als", "sig A {}<caret>\nsig B {}")
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
        myFixture.checkResult("//sig A {}\nsig B {}")

        myFixture.editor.caretModel.moveToOffset(0)
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_LINE)
        myFixture.checkResult("sig A {}\nsig B {}")
    }

    fun testBlockCommentWrapsSelection() {
        myFixture.configureByText("model.als", "fact { <selection>some A</selection> }")
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_BLOCK)
        myFixture.checkResult("fact { /*some A*/ }")
    }

    fun testBlockCommentToggles() {
        myFixture.configureByText("model.als", "fact { <selection>/*some A*/</selection> }")
        myFixture.performEditorAction(IdeActions.ACTION_COMMENT_BLOCK)
        myFixture.checkResult("fact { some A }")
    }
}
