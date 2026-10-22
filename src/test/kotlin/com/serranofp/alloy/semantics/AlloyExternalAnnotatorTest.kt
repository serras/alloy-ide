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
import edu.mit.csail.sdg.alloy4.ErrorSyntax
import edu.mit.csail.sdg.alloy4.ErrorType
import edu.mit.csail.sdg.alloy4.Pos

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

    fun testErrorInALibraryModuleIsAtItsOpen() {
        // The analyzer reports this one inside util/ordering
        myFixture.configureByText("main.als", "open util/ordering[Int]\nsig A {}")
        val atOpen = highlights(HighlightSeverity.ERROR).single()
        assertEquals("util/ordering", atOpen.text())
        assertTrue(atOpen.description, atOpen.description.startsWith("The opened module util/ordering has errors (ordering.als:"))
    }

    fun testErrorsInAModuleOpenedTwiceAreAtBothOpens() {
        myFixture.addFileToProject("d.als", "module d\nsig D { f: Undefined }")
        myFixture.addFileToProject("b.als", "module b\nopen d")
        myFixture.addFileToProject("c.als", "module c\nopen d")
        myFixture.configureByText("main.als", "open b\nopen c\nsig Box {}")
        assertEquals(listOf("b", "c"), highlights(HighlightSeverity.ERROR).map { it.text() }.sorted())
    }

    fun testWarningInAnOpenedModuleIsNotShownInTheOpener() {
        myFixture.addFileToProject("lib.als", "module lib\nsig Item {}\nfact { some Item & none }")
        myFixture.configureByText("main.als", "open lib\nsig Box { items: set Item }")
        assertEmpty(highlights(HighlightSeverity.WARNING))
        assertEmpty(highlights(HighlightSeverity.ERROR))
    }

    /**
     * A position past the text, as the analyzer reports for text that has changed since, is clamped into it:
     * past the last line to the last line, past the end of a line to its end.
     */
    fun testErrorPastTheTextIsClampedIntoIt() {
        val service = AlloyModelService.getInstance(project)
        service.parse = { _, _, filename -> throw ErrorSyntax(Pos(filename, 1, 5), "Unexpected end") }
        try {
            myFixture.configureByText("model.als", "sig A {}\nsig B {}")
            val error = highlights(HighlightSeverity.ERROR).single()
            assertEquals("Unexpected end", error.description)
            assertEquals("sig A {}\n".length, error.startOffset)
            assertEquals("s", error.text())

            service.parse = { _, _, filename -> throw ErrorSyntax(Pos(filename, 30, 1, 40, 2), "Unexpected end") }
            WriteCommandAction.runWriteCommandAction(project) { myFixture.editor.document.insertString(0, " ") }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            // From the end of the first line to the end of the second
            assertEquals("\nsig B {}", highlights(HighlightSeverity.ERROR).single().text())
        } finally {
            service.restoreParse()
        }
    }

    fun testErrorWithoutAPositionIsForTheWholeFile() {
        val service = AlloyModelService.getInstance(project)
        service.parse = { _, _, _ -> throw ErrorType("Somewhere unknown") }
        try {
            myFixture.configureByText("model.als", "sig A {}")
            val error = highlights(HighlightSeverity.ERROR).single()
            assertEquals("Somewhere unknown", error.description)
            assertTrue(error.isFileLevelAnnotation)
        } finally {
            service.restoreParse()
        }
    }

    fun testAnalyzerFailureShowsNothing() {
        val service = AlloyModelService.getInstance(project)
        service.parse = { _, _, _ -> throw IllegalStateException("analyzer bug") }
        try {
            myFixture.configureByText("model.als", "sig A { f: Undefined }")
            assertEmpty(myFixture.doHighlighting(HighlightSeverity.WARNING))
        } finally {
            service.restoreParse()
        }
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
