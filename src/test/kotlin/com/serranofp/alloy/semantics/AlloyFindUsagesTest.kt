package com.serranofp.alloy.semantics

import com.intellij.codeInsight.highlighting.HighlightUsagesHandler
import com.intellij.lang.cacheBuilder.WordOccurrence
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.serranofp.alloy.psi.AlloyBinderDecl
import com.serranofp.alloy.psi.AlloyCommandDecl
import com.serranofp.alloy.psi.AlloyFactDecl
import com.serranofp.alloy.psi.AlloyFieldDecl
import com.serranofp.alloy.psi.AlloyParamDecl
import com.serranofp.alloy.psi.AlloyPredDecl
import com.serranofp.alloy.psi.AlloyRef
import com.serranofp.alloy.psi.AlloySigName

class AlloyFindUsagesTest : BasePlatformTestCase() {
    private fun analyze(vararg files: PsiFile) {
        files.forEach(::computeModel)
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

    fun testKindsAsCompletionShowsThem() {
        val file = myFixture.configureByText("m.als", "sig A {}\nfact named { all x: A | some x }\nrun named {} for 3")
        val provider = AlloyFindUsagesProvider()
        assertEquals("binder", provider.getType(PsiTreeUtil.findChildOfType(file, AlloyBinderDecl::class.java)!!))
        assertEquals("signature", provider.getType(PsiTreeUtil.findChildOfType(file, AlloySigName::class.java)!!))
        assertEquals("fact", provider.getType(PsiTreeUtil.findChildOfType(file, AlloyFactDecl::class.java)!!))
        assertEquals("command", provider.getType(PsiTreeUtil.findChildOfType(file, AlloyCommandDecl::class.java)!!))
    }

    fun testNamesOfTheDeclarations() {
        val file = myFixture.configureByText("m.als", "sig A {}\npred p[x: A] {}")
        val provider = AlloyFindUsagesProvider()
        val predicate = PsiTreeUtil.findChildOfType(file, AlloyPredDecl::class.java)!!
        assertEquals("p", provider.getDescriptiveName(predicate))
        assertEquals("p", provider.getNodeText(predicate, true))
        assertTrue(provider.canFindUsagesFor(predicate))
        assertFalse(provider.canFindUsagesFor(file))
        assertEquals("parameter", provider.getType(PsiTreeUtil.findChildOfType(file, AlloyParamDecl::class.java)!!))
    }

    /**
     * Identifiers are words of code, and the words in comments and strings are not: a search for usages, which
     * looks for words of code, does not stop at them. As the search splits a name like `a"b`, so does the index.
     */
    fun testWordsOfCodeCommentsAndStrings() {
        val words = mutableListOf<Pair<String, WordOccurrence.Kind?>>()
        val text = "sig a\"b { f': set univ } -- x\nfact { \"y\" = \"y\" }"
        AlloyFindUsagesProvider().wordsScanner.processWords(text) { words += it.baseText.substring(it.start, it.end) to it.kind; true }
        val code = words.filter { it.second == WordOccurrence.Kind.CODE }.map { it.first }
        assertTrue(code.toString(), code.containsAll(listOf("a", "b", "f", "univ")))
        assertFalse(code.toString(), "x" in code || "y" in code)
        assertEquals(listOf(WordOccurrence.Kind.COMMENTS), words.filter { it.first == "x" }.map { it.second })
        assertEquals(listOf(WordOccurrence.Kind.LITERALS, WordOccurrence.Kind.LITERALS), words.filter { it.first == "y" }.map { it.second })
    }

    fun testUsagesOfAParameterAndABinderStayInTheirParagraph() {
        val file = myFixture.configureByText(
            "m.als",
            "sig A { f: set A }\npred p[x: A] { all y: x.f | some y.f and y in x }\npred q[x: A] { some y: A | y in x }",
        )
        analyze(file)
        fun uses(declaration: PsiElement): List<Pair<Int, String>> = myFixture.findUsages(declaration)
            .map { usage -> file.text.substring(0, usage.navigationRange.startOffset).count { it == '\n' } to usage.element!!.text }
        val parameter = PsiTreeUtil.findChildrenOfType(file, AlloyParamDecl::class.java).first()
        assertEquals(listOf(1 to "x", 1 to "x"), uses(parameter))
        val binder = PsiTreeUtil.findChildrenOfType(file, AlloyBinderDecl::class.java).first()
        assertEquals(listOf(1 to "y", 1 to "y"), uses(binder))
    }

    /** `@f` names the field itself, rather than its join with `this`; it is a use of the field all the same. */
    fun testAtFieldIsAUsage() {
        val file = myFixture.configureByText("m.als", "sig A { f: set A } { some @f }\nfact { some A.f }")
        analyze(file)
        val field = PsiTreeUtil.findChildOfType(file, AlloyFieldDecl::class.java)!!
        assertEquals(2, myFixture.findUsages(field).size)
    }
}
