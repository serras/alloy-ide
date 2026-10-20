package com.serranofp.alloy.semantics

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.CommonProcessors
import com.intellij.util.indexing.FindSymbolParameters
import com.serranofp.alloy.psi.AlloyPredDecl

class AlloySymbolContributorTest : BasePlatformTestCase() {
    private val contributor get() = ChooseByNameContributor.SYMBOL_EP_NAME.extensionList.filterIsInstance<AlloySymbolContributor>().single()

    private fun names(): Set<String> {
        val names = CommonProcessors.CollectProcessor<String>()
        contributor.processNames(names, GlobalSearchScope.projectScope(project), null)
        return names.results.toSet()
    }

    private fun items(name: String): List<NavigationItem> {
        val items = CommonProcessors.CollectProcessor<NavigationItem>()
        contributor.processElementsWithName(name, items, FindSymbolParameters.wrap(name, project, false))
        return items.results.toList()
    }

    fun testPredicateInAnotherFileIsFoundAndOpened() {
        val other = myFixture.addFileToProject("graph.als", "sig Node { next: set Node }\npred acyclic { no iden & ^next }")
        myFixture.configureByText("main.als", "open graph\nrun acyclic for 3")
        assertTrue(names().containsAll(listOf("Node", "next", "acyclic")))

        val item = items("acyclic").single()
        assertInstanceOf(item, AlloyPredDecl::class.java)
        assertEquals("acyclic", item.presentation!!.presentableText)
        assertEquals("graph.als", item.presentation!!.locationString)

        item.navigate(true)
        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
        assertEquals(other.virtualFile, FileEditorManager.getInstance(project).selectedFiles.first())
        assertEquals(other.text.indexOf("acyclic"), editor.caretModel.offset)
    }

    fun testNamedFactsAndCommands() {
        myFixture.configureByText("m.als", "sig A {}\nfact noEmpty { some A }\nrun withTwo { #A = 2 } for 3\nrun noEmpty for 2")
        assertTrue(names().containsAll(listOf("noEmpty", "withTwo")))
        assertEquals(1, items("withTwo").size)
        // `run noEmpty for 2` declares nothing: `noEmpty` is its target, not its name
        assertEquals(1, items("noEmpty").size)
    }

    fun testLocalNamesAreNotSymbols() {
        myFixture.configureByText("m.als", "sig A {}\npred p[param: A] { all binder: A | some binder }")
        assertFalse(names().contains("param"))
        assertFalse(names().contains("binder"))
    }
}
