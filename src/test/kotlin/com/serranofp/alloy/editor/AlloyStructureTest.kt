package com.serranofp.alloy.editor

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.breadcrumbs.BreadcrumbsProvider
import com.serranofp.alloy.AlloyLanguage
import com.serranofp.alloy.psi.AlloyPredDecl

class AlloyStructureTest : BasePlatformTestCase() {
    private val model = """
        module graph
        open util/ordering[Node]
        /** A node. */
        abstract sig Node { next: set Node, disj a, b: lone Node }
        one sig Root, Leaf extends Node {}
        enum Color { Red, Green }
        fact acyclic { no iden & ^next }
        fact { some Node }
        pred show[n: Node] { some n.next }
        fun reach[n: Node]: set Node { n.^next }
        assert noCycles { no iden & ^next }
        let twice[x] = x.next.next
        run show for 3
        run { some Root } for 2 but 3 Node
        check noCycles for 5
    """.trimIndent()

    private val expected = """
        -m.als
         module graph
         -Node
          next
          a
          b
         Root, Leaf
         -Color
          Red
          Green
         acyclic
         fact
         show
         reach
         noCycles
         twice
         run show for 3
         run {...} for 2 but 3 Node
         check noCycles for 5
    """.trimIndent()

    private fun structure() {
        myFixture.testStructureView { component ->
            PlatformTestUtil.expandAll(component.tree)
            PlatformTestUtil.assertTreeEqual(component.tree, expected + "\n")
        }
    }

    fun testStructureView() {
        myFixture.configureByText("m.als", model)
        structure()
    }

    fun testFileWithATypeError() {
        myFixture.configureByText("m.als", model.replace("some n.next", "some n.next and some Undefined"))
        structure()
    }

    fun testSelectingAnEntryMovesTheCaret() {
        myFixture.configureByText("m.als", model)
        val builder = LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(myFixture.file) as TreeBasedStructureViewBuilder
        val root = builder.createStructureViewModel(myFixture.editor).root
        val show = root.children.single { it.presentation.presentableText == "show" } as StructureViewTreeElement
        val next = root.children.single { it.presentation.presentableText == "Node" }.children.single { it.presentation.presentableText == "next" } as StructureViewTreeElement
        show.navigate(true)
        assertEquals(model.indexOf("show["), myFixture.caretOffset)
        next.navigate(true)
        assertEquals(model.indexOf("next:"), myFixture.caretOffset)
    }

    private fun breadcrumbs(): List<String> {
        val provider = BreadcrumbsProvider.EP_NAME.extensionList.single { AlloyLanguage in it.languages }
        return generateSequence(myFixture.file.findElementAt(myFixture.caretOffset)) { it.parent }
            .filter(provider::acceptElement)
            .map(provider::getElementInfo)
            .toList()
            .reversed()
    }

    fun testBreadcrumbsInAPredicateBody() {
        myFixture.configureByText("m.als", model.replace("some n.next }", "some n.ne<caret>xt }"))
        assertEquals(listOf("show"), breadcrumbs())
    }

    fun testBreadcrumbsOnAField() {
        myFixture.configureByText("m.als", model.replace("next: set Node", "ne<caret>xt: set Node"))
        assertEquals(listOf("Node", "next"), breadcrumbs())
    }

    /** A field's element is its name, so its type is in the signature itself. */
    fun testBreadcrumbsInAFieldType() {
        myFixture.configureByText("m.als", model.replace("next: set Node", "next: set No<caret>de"))
        assertEquals(listOf("Node"), breadcrumbs())
    }

    fun testBreadcrumbsWithATypeError() {
        myFixture.configureByText("m.als", model.replace("{ n.^next }", "{ n.^ne<caret>xt and Undefined }"))
        assertEquals(listOf("reach"), breadcrumbs())
    }

    /** Autoscroll from source selects what the tree lists: the predicate, as parameters and binders are not in it. */
    fun testEditorElementOfAParameterIsItsParagraph() {
        myFixture.configureByText("m.als", model.replace("pred show[n: Node]", "pred show[n<caret>: Node]"))
        val builder = LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(myFixture.file) as TreeBasedStructureViewBuilder
        val element = builder.createStructureViewModel(myFixture.editor).currentEditorElement
        assertInstanceOf(element, AlloyPredDecl::class.java)
    }
}
