package com.serranofp.alloy.viewer

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.EditorTextField
import com.serranofp.alloy.AlloyFileType
import com.serranofp.alloy.psi.AlloyTypes
import com.serranofp.alloy.semantics.AlloyExternalAnnotator

class AlloyEvaluatorFragmentTest : BasePlatformTestCase() {
    private fun configure(text: String, fragment: Boolean) {
        myFixture.configureByText("f.als", text)
        if (fragment) myFixture.file.putUserData(ALLOY_EVALUATOR_FRAGMENT) { null }
    }

    private fun problems() = myFixture.doHighlighting().filter { it.severity >= HighlightSeverity.WARNING }

    fun testFragmentHasNoDiagnostics() {
        configure("Undefined", fragment = false)
        assertNotEmpty(problems())
        configure("Undefined", fragment = true)
        assertEmpty(problems())
        assertNull(AlloyExternalAnnotator().collectInformation(myFixture.file))
    }

    fun testFragmentGetsNoModelFileCompletion() {
        configure("so<caret>", fragment = false)
        myFixture.completeBasic()
        assertContainsElements(myFixture.lookupElementStrings.orEmpty() + myFixture.editor.document.text, "some")
        configure("so<caret>", fragment = true)
        assertEmpty(myFixture.completeBasic().orEmpty().toList())
        assertEquals("so", myFixture.editor.document.text)
    }

    fun testTheFieldIsAnAlloyFragment() {
        val document = alloyEvaluatorDocument(project) { null }
        assertTrue(PsiDocumentManager.getInstance(project).getPsiFile(document)!!.isAlloyEvaluatorFragment)
        val field = EditorTextField(document, project, AlloyFileType, false, true)
        field.text = "some succ"
        field.addNotify()
        try {
            val tokens = generateSequence((field.editor as EditorEx).highlighter.createIterator(0)) { it.advance(); if (it.atEnd()) null else it }
                .map { it.tokenType }.toList()
            assertEquals(AlloyTypes.SOME, tokens.first())
            assertTrue(tokens.toString(), AlloyTypes.IDENTIFIER in tokens)
        } finally {
            field.removeNotify()
        }
    }
}
