package com.serranofp.alloy.semantics

import com.intellij.analysis.problemsView.ProblemsProvider
import com.intellij.analysis.problemsView.toolWindow.HighlightingProblem
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class AlloyExternalAnnotatorTest : BasePlatformTestCase() {
    private fun highlights(severity: HighlightSeverity): List<HighlightInfo> =
        myFixture.doHighlighting(severity).filter { it.severity == severity }

    private fun HighlightInfo.text(): String = myFixture.file.text.substring(startOffset, endOffset)

    fun testUndefinedSignature() {
        myFixture.configureByText("model.als", "sig A { f: Undefined }")
        val error = highlights(HighlightSeverity.ERROR).single()
        assertEquals("Undefined", error.text())
        assertEquals("The name \"Undefined\" cannot be found.", error.description)
    }

    fun testCheckHighlighting() {
        myFixture.configureByText("model.als", "sig A {}\npred p { some <error descr=\"The name \\\"Bogus\\\" cannot be found.\">Bogus</error> }")
        myFixture.checkHighlighting(false, false, false)
    }

    fun testFixedErrorWithoutSaving() {
        myFixture.configureByText("model.als", "sig A { f: Undefined }")
        assertEquals(1, highlights(HighlightSeverity.ERROR).size)

        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.replaceString(11, 20, "A")
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEmpty(highlights(HighlightSeverity.ERROR))
    }

    fun testWarning() {
        myFixture.configureByText("model.als", "sig A {}\nfact { some A & none }")
        val warning = highlights(HighlightSeverity.WARNING).single()
        assertEquals("&", warning.text())
        assertTrue(warning.description, warning.description.startsWith("& is irrelevant because the two subexpressions are always disjoint."))
        assertTrue(warning.toolTip!!, warning.toolTip!!.contains("<br>"))
        assertEmpty(highlights(HighlightSeverity.ERROR))
    }

    fun testErrorInAnOpenedModule() {
        val library = myFixture.addFileToProject("lib.als", "module lib\nsig Item { f: Undefined }")
        myFixture.configureByText("main.als", "-- uses lib\nopen lib\nsig Box { items: set Item }")
        val atOpen = highlights(HighlightSeverity.ERROR).single()
        assertEquals("lib", atOpen.text())
        assertTrue(atOpen.description, atOpen.description.startsWith("The opened module lib has errors (lib.als:2):"))
        assertTrue(atOpen.description, atOpen.description.contains("The name \"Undefined\" cannot be found."))

        // And in the module's own file, at the error
        myFixture.configureFromExistingVirtualFile(library.virtualFile)
        assertEquals("Undefined", highlights(HighlightSeverity.ERROR).single().text())
    }

    fun testErrorInAModuleOpenedIndirectly() {
        myFixture.addFileToProject("inner.als", "module inner\nsig Item { f: Undefined }")
        myFixture.addFileToProject("middle.als", "module middle\nopen inner")
        myFixture.configureByText("main.als", "open middle\nsig Box {}")
        assertEquals("middle", highlights(HighlightSeverity.ERROR).single().text())
    }

    fun testValidModel() {
        myFixture.configureByText("model.als", "open util/ordering[S]\nsig S {}\nfact { some first }\nrun {} for 3")
        assertEmpty(myFixture.doHighlighting(HighlightSeverity.WARNING))
    }

    fun testProblemsViewEntry() {
        myFixture.configureByText("model.als", "sig A {}\n\nsig B { f: Undefined }")
        highlights(HighlightSeverity.ERROR)

        // The Problems tool window lists the highlighters of the file's markup model, as HighlightingProblems
        val provider = object : ProblemsProvider {
            override val project: Project get() = this@AlloyExternalAnnotatorTest.project
        }
        val markup = DocumentMarkupModel.forDocument(myFixture.editor.document, project, false)
        val problems = markup.allHighlighters
            .filter { (it.errorStripeTooltip as? HighlightInfo)?.severity == HighlightSeverity.ERROR }
            .map { HighlightingProblem(provider, myFixture.file.virtualFile, it) }
        val problem = problems.single()
        assertEquals("The name \"Undefined\" cannot be found.", problem.text)
        // Selecting it navigates to line 3, column 12 (0-based 2 and 11)
        assertEquals(2, problem.line)
        assertEquals(11, problem.column)
    }
}
