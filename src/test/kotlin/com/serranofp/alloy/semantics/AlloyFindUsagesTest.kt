package com.serranofp.alloy.semantics

import com.intellij.codeInsight.highlighting.HighlightUsagesHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.serranofp.alloy.psi.AlloyFieldDecl
import com.serranofp.alloy.psi.AlloyFile
import com.serranofp.alloy.psi.AlloyPredDecl
import com.serranofp.alloy.psi.AlloyRef
import java.util.concurrent.TimeUnit

class AlloyFindUsagesTest : BasePlatformTestCase() {
    private fun analyze(vararg files: PsiFile) {
        for (file in files) {
            ApplicationManager.getApplication().executeOnPooledThread<AlloyModelResult> {
                ReadAction.computeBlocking<AlloyModelResult, Throwable> { AlloyModelService.getInstance(project).model(file as AlloyFile) }
            }.get(30, TimeUnit.SECONDS)
        }
    }

    private val library = "sig A {}\npred p[x: A] { some x }\nfact { all a: A | p[a] }"

    fun testPredicateUsedInTwoFiles() {
        val a = myFixture.addFileToProject("a.als", library)
        val b = myFixture.addFileToProject("b.als", "open a\nrun { p[A] } for 3\nrun p for 2")
        analyze(a, b)
        val predicate = PsiTreeUtil.findChildOfType(a, AlloyPredDecl::class.java)!!

        val usages = myFixture.findUsages(predicate)
        assertEquals(
            listOf("a.als:3", "b.als:2", "b.als:3"),
            usages.map { "${it.virtualFile!!.name}:${it.file!!.viewProvider.document!!.getLineNumber(it.navigationRange.startOffset) + 1}" }.sorted(),
        )
        // Each entry navigates to the use
        assertTrue(usages.all { it.element!!.text == "p" })
        assertTrue(usages.all { AlloyUsageTypeProvider().getUsageType(it.element!!) == AlloyUsageTypeProvider.Usages.CONFIRMED })
    }

    fun testUsesInAFileWithErrorsAreUnconfirmed() {
        val a = myFixture.addFileToProject("a.als", library)
        val b = myFixture.addFileToProject("b.als", "open a\nrun { p[A] } for 3\npred broken { some Undefined }")
        analyze(a, b)
        val predicate = PsiTreeUtil.findChildOfType(a, AlloyPredDecl::class.java)!!

        val tree = myFixture.getUsageViewTreeTextRepresentation(predicate)
        val confirmed = tree.indexOf(AlloyUsageTypeProvider.Usages.CONFIRMED.toString())
        val unconfirmed = tree.indexOf(AlloyUsageTypeProvider.Usages.UNCONFIRMED.toString())
        assertTrue(tree, confirmed >= 0 && unconfirmed >= 0)
        // a.als in the confirmed group, b.als in the unconfirmed one
        val groups = if (confirmed < unconfirmed) listOf(tree.substring(confirmed, unconfirmed), tree.substring(unconfirmed))
        else listOf(tree.substring(confirmed), tree.substring(unconfirmed, confirmed))
        assertTrue(tree, "a.als" in groups[0] && "b.als" !in groups[0])
        assertTrue(tree, "b.als" in groups[1] && "a.als" !in groups[1])
    }

    fun testOverloadedFieldUsagesWithoutErrors() {
        val file = myFixture.configureByText("m.als", "sig A { f: set A }\nsig B { f: set B }\npred p[a: A, b: B] { some a.f and some b.f }")
        analyze(file)
        val fieldOfA = PsiTreeUtil.findChildrenOfType(file, AlloyFieldDecl::class.java).first()
        val usages = myFixture.findUsages(fieldOfA)
        // Only the use on `a: A`, not the one on `b: B`
        assertEquals(listOf("a.f"), usages.map { file.text.substring(it.navigationRange.startOffset - 2, it.navigationRange.endOffset) })
    }

    fun testHighlightUsagesInTheFile() {
        val file = myFixture.configureByText("m.als", "sig A { f: set A }\npred p[a: A] { some a.f<caret> and no f & iden }")
        analyze(file)
        HighlightUsagesHandler.invoke(project, myFixture.editor, file)
        val highlighted = myFixture.editor.markupModel.allHighlighters
            .filter { it.textAttributesKey == EditorColors.SEARCH_RESULT_ATTRIBUTES || it.textAttributesKey == EditorColors.WRITE_SEARCH_RESULT_ATTRIBUTES }
            .map { file.text.substring(it.startOffset, it.endOffset) to it.startOffset }
            .sortedBy { it.second }
        // The declaration and both uses
        assertEquals(listOf("f", "f", "f"), highlighted.map { it.first })
        assertEquals(file.text.indexOf("f:"), highlighted.first().second)
        assertEquals(2, PsiTreeUtil.findChildrenOfType(file, AlloyRef::class.java).count { it.text == "f" })
    }
}
