package com.serranofp.alloy.inspections

import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.CodeInsightTestFixture
import com.serranofp.alloy.psi.AlloyFile
import com.serranofp.alloy.semantics.AlloyModelResult
import com.serranofp.alloy.semantics.AlloyModelService
import junit.framework.TestCase
import java.util.concurrent.TimeUnit

/**
 * Asserts that the fixture's file parses and type-checks. The oracle to use after a quick-fix or an intention:
 * the model inspections report nothing on a model that does not type-check, so "the problem is gone" alone
 * also holds for a fix that breaks the model.
 */
fun CodeInsightTestFixture.assertTypeChecks() {
    PsiDocumentManager.getInstance(project).commitAllDocuments()
    val file = file as AlloyFile
    val result = ApplicationManager.getApplication()
        .executeOnPooledThread<AlloyModelResult> { ReadAction.computeBlocking<AlloyModelResult, Throwable> { AlloyModelService.getInstance(project).model(file) } }
        .get(60, TimeUnit.SECONDS)
    val errors = when (result) {
        is AlloyModelResult.Model -> return
        is AlloyModelResult.Errors -> result.errors.map { it.toString() }
        is AlloyModelResult.Unavailable -> listOf(result.cause.toString())
    }
    TestCase.fail("The model does not type-check: $errors\n${file.text}")
}

/** The problems that [inspection] reports in the fixture's file, in the order of the file. */
fun CodeInsightTestFixture.problemsOf(inspection: LocalInspectionTool): List<AlloyInspectionTestCase.Problem> =
    doHighlighting().filter { it.inspectionToolId == inspection.shortName }.sortedBy { it.startOffset }.map {
        AlloyInspectionTestCase.Problem(it.severity, file.text.substring(it.startOffset, it.endOffset), it.description)
    }

/**
 * Tests of a model [inspection]: the problems it reports, and its quick-fixes, which are checked to leave the
 * model type-checking. Tests of several inspections leave [inspection] null and pass theirs.
 */
abstract class AlloyInspectionTestCase : BasePlatformTestCase() {
    /** A problem reported in the fixture's file: its severity, the text it is on, and its message. */
    data class Problem(val severity: HighlightSeverity, val text: String, val message: String)

    protected open val inspection: LocalInspectionTool? = null

    override fun setUp() {
        super.setUp()
        // The scope fixes select the number they write with a template
        TemplateManagerImpl.setTemplateTesting(testRootDisposable)
        inspection?.let { myFixture.enableInspections(it) }
    }

    /** The text and message of each problem that [inspection] reports, in the order of the file. */
    protected fun problems(inspection: LocalInspectionTool = this.inspection!!): List<Pair<String, String>> =
        detailedProblems(inspection).map { it.text to it.message }

    /** Each problem that [inspection] reports, with its severity. */
    protected fun detailedProblems(inspection: LocalInspectionTool = this.inspection!!): List<Problem> = myFixture.problemsOf(inspection)

    /**
     * Applies the quick-fix or intention [fix] with the caret at [offset], checks that the model still
     * type-checks, and returns the text that a template then selects for editing, if any.
     */
    protected fun launch(offset: Int, fix: String): String? {
        assertTrue("No text at $offset", offset >= 0)
        myFixture.editor.caretModel.moveToOffset(offset)
        myFixture.launchAction(myFixture.findSingleIntention(fix))
        val template = TemplateManagerImpl.getTemplateState(myFixture.editor)
        val selected = template?.currentVariableRange?.substring(myFixture.editor.document.text)
        template?.gotoEnd(false)
        assertTypeChecks()
        return selected
    }

    /** [launch] with the caret at the first occurrence of [at]. */
    protected fun launch(at: String, fix: String): String? = launch(myFixture.file.text.indexOf(at), fix)

    protected fun assertTypeChecks() = myFixture.assertTypeChecks()

    /** Replaces the fixture's text, as an edit in the editor, and commits it. */
    protected fun edit(text: String) {
        val document = myFixture.editor.document
        WriteCommandAction.runWriteCommandAction(project) { document.setText(text) }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }
}
