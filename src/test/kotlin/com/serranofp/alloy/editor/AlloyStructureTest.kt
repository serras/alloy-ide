package com.serranofp.alloy.editor

import com.intellij.icons.AllIcons
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.breadcrumbs.BreadcrumbsProvider
import com.serranofp.alloy.AlloyLanguage
import com.serranofp.alloy.psi.AlloyFieldDecl
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

    /** Fields are in the tree, so autoscroll from a field selects the field rather than its signature. */
    fun testEditorElementOfAFieldIsTheField() {
        myFixture.configureByText("m.als", model.replace("next: set Node", "ne<caret>xt: set Node"))
        val builder = LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(myFixture.file) as TreeBasedStructureViewBuilder
        val element = builder.createStructureViewModel(myFixture.editor).currentEditorElement
        assertInstanceOf(element, AlloyFieldDecl::class.java)
        assertEquals("next", (element as AlloyFieldDecl).name)
    }

    fun testIconsOfTheEntries() {
        myFixture.configureByText("m.als", model)
        val builder = LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(myFixture.file) as TreeBasedStructureViewBuilder
        val root = builder.createStructureViewModel(myFixture.editor).root
        val icons = root.children.associate { it.presentation.presentableText to it.presentation.getIcon(false) }
        assertEquals(
            mapOf(
                "module graph" to AllIcons.Nodes.Module,
                "Node" to AllIcons.Nodes.Class,
                "Root, Leaf" to AllIcons.Nodes.Class,
                "Color" to AllIcons.Nodes.Enum,
                "acyclic" to AllIcons.Nodes.Static,
                "fact" to AllIcons.Nodes.Static,
                "show" to AllIcons.Nodes.Method,
                "reach" to AllIcons.Nodes.Function,
                "noCycles" to AllIcons.Nodes.Test,
                "twice" to AllIcons.Nodes.Lambda,
                "run show for 3" to AllIcons.RunConfigurations.TestState.Run,
                "run {...} for 2 but 3 Node" to AllIcons.RunConfigurations.TestState.Run,
                "check noCycles for 5" to AllIcons.RunConfigurations.TestState.Run,
            ),
            icons,
        )
        val node = root.children.single { it.presentation.presentableText == "Node" }
        assertEquals(setOf(AllIcons.Nodes.Field), node.children.map { it.presentation.getIcon(false) }.toSet())
        val color = root.children.single { it.presentation.presentableText == "Color" }
        assertEquals(setOf(AllIcons.Nodes.Constant), color.children.map { it.presentation.getIcon(false) }.toSet())
    }

    /** A paragraph the parser could not finish is still listed, with those after it. */
    fun testFileWithAParseError() {
        myFixture.configureByText("m.als", "sig A { f: }\npred p[x: A {}\nfact named { some A }")
        val builder = LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(myFixture.file) as TreeBasedStructureViewBuilder
        val root = builder.createStructureViewModel(myFixture.editor).root
        assertEquals(listOf("A", "p", "named"), root.children.map { it.presentation.presentableText })
    }
}
