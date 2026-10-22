package com.serranofp.alloy.viewer

import com.intellij.codeInsight.completion.CodeCompletionHandlerBase
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.testFramework.PlatformTestUtil
import com.serranofp.alloy.semantics.AlloyModelService
import com.serranofp.alloy.semantics.AlloyReference
import java.util.concurrent.atomic.AtomicInteger
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.util.FileContentUtilCore
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.EditorTextField
import com.serranofp.alloy.AlloyFileType
import com.serranofp.alloy.psi.AlloyTypes
import com.serranofp.alloy.semantics.AlloyExternalAnnotator

class AlloyEvaluatorFragmentTest : BasePlatformTestCase() {
    private fun configure(text: String, fragment: Boolean) {
        myFixture.configureByText("f.als", text)
        myFixture.file.virtualFile.putUserData(ALLOY_EVALUATOR_FRAGMENT, if (fragment) { { null } } else null)
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

    /** The mark is on the virtual file, so that a new PSI of it, as a reparse makes, is still the fragment. */
    fun testTheFragmentSurvivesAReparse() {
        val document = alloyEvaluatorDocument(project) { null }
        val documents = PsiDocumentManager.getInstance(project)
        val before = documents.getPsiFile(document)!!
        val file = before.viewProvider.virtualFile
        FileContentUtilCore.reparseFiles(file)
        val after = PsiManager.getInstance(project).findFile(file)!!
        assertNotSame(before, after)
        assertTrue(after.isAlloyEvaluatorFragment)
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

    private fun model(text: String) = link(text).let { AlloySolutionModel(it.solution, module = it.module) }

    /** The field's own file, which is not physical, completes the names of the model it evaluates in. */
    fun testTheFieldCompletesTheModelsNames() {
        val model = model("sig Node { succ: set Node }\nrun { some succ } for 2")
        val field = EditorTextField(alloyEvaluatorDocument(project) { model }, project, AlloyFileType, false, true)
        field.text = "su"
        field.addNotify()
        try {
            val editor = field.editor!!
            editor.caretModel.moveToOffset(2)
            CodeCompletionHandlerBase(CompletionType.BASIC).invokeCompletion(project, editor)
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            val lookup = LookupManager.getActiveLookup(editor)
            try {
                // `sub` is from util/integer, which every model opens
                assertSameElements(lookup!!.items.map { it.lookupString }, "succ", "sub")
            } finally {
                lookup?.hideLookup(true)
            }
        } finally {
            field.removeNotify()
        }
    }

    /** A fragment is not a module: its names resolve by name only, without asking the analyzer for a model of it. */
    fun testResolvingInTheFragmentMakesNoModel() {
        val service = AlloyModelService.getInstance(project)
        val parse = service.parse
        val parses = AtomicInteger()
        service.parse = { reporter, contents, path -> parses.incrementAndGet(); parse(reporter, contents, path) }
        try {
            val document = alloyEvaluatorDocument(project) { null }
            // Parsed as a module, the names of a fact are references
            WriteCommandAction.runWriteCommandAction(project) { document.setText("fact { some succ }") }
            val documents = PsiDocumentManager.getInstance(project)
            documents.commitDocument(document)
            val file = documents.getPsiFile(document)!!
            val reference = file.findReferenceAt(document.text.indexOf("succ") + 1) as AlloyReference
            assertEmpty(reference.multiResolve(false))
            assertNull(reference.exactResolution())
            assertEquals(0, parses.get())
        } finally {
            service.parse = parse
        }
    }
}
