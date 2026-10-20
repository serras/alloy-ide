package com.serranofp.alloy.semantics

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.serranofp.alloy.semantics.AlloyScopes.Layer

class AlloyScopesTest : BasePlatformTestCase() {
    /** What is visible at the caret of [text], in a file `m.als`. */
    private fun visible(text: String, qualifier: String? = null): List<AlloyScopes.Visible> {
        myFixture.configureByText("m.als", text)
        return AlloyScopes.visible(myFixture.file.findElementAt(myFixture.caretOffset)!!, qualifier)
    }

    private fun List<AlloyScopes.Visible>.names(): Set<String> = mapNotNull { it.declaration.name }.toSet()

    private fun List<AlloyScopes.Visible>.layerOf(name: String): Layer = single { it.declaration.name == name }.layer

    fun testBindersInTheirBodyOnly() {
        val body = visible("sig A {}\npred p { all z: A | some <caret>z }")
        assertEquals(Layer.LOCAL, body.layerOf("z"))
        val bound = visible("sig A {}\npred p { all z: <caret>A | some z }")
        assertFalse(bound.names().contains("z"))
    }

    fun testParametersInTheirParagraph() {
        val inside = visible("sig A {}\npred q[y: A] { all z: A | some <caret>y }")
        assertEquals(Layer.LOCAL, inside.layerOf("y"))
        assertEquals(Layer.LOCAL, inside.layerOf("z"))
        val outside = visible("sig A {}\npred q[y: A] { some y }\nfact { some <caret>A }")
        assertFalse(outside.names().contains("y"))
    }

    fun testFieldsOfTheEnclosingSignature() {
        val visible = visible("sig S { f: set S } { some <caret>f }")
        assertTrue(visible.any { it.declaration.name == "f" && it.layer == Layer.FIELD })
    }

    fun testTopLevelDeclarations() {
        val visible = visible("sig A { f: set A }\nenum Color { Red }\npred p {}\nfun g: A { A }\nassert ok { some A }\nfact { some <caret>A }")
        assertTrue(visible.names().containsAll(listOf("A", "f", "Color", "Red", "p", "g", "ok")))
        assertEquals(Layer.TOP_LEVEL, visible.layerOf("p"))
        assertTrue(visible.all { it.module == null })
    }

    fun testAnInnerLayerHidesAnOuterOne() {
        val visible = visible("sig A {}\npred x {}\npred p[x: A] { all x: A | some <caret>x }")
        val x = visible.single { it.declaration.name == "x" }
        assertEquals(Layer.LOCAL, x.layer)
        assertEquals("all x", x.declaration.parent.text.substring(0, 5))
    }

    fun testOverloadedFieldsAreAllKept() {
        val visible = visible("sig A { f: set A }\nsig B { f: set B }\nfact { some <caret>A }")
        assertEquals(2, visible.count { it.declaration.name == "f" })
    }

    fun testOpenedProjectAndLibraryDeclarations() {
        val a = myFixture.addFileToProject("a.als", "sig Item {}\npred p[x: Item] {}")
        val visible = visible("open a\nopen util/ordering[S]\nsig S {}\nfact { some <caret>S }")
        val p = visible.single { it.declaration.name == "p" }
        assertEquals(Layer.OPENED, p.layer)
        assertEquals(a, p.module)
        val first = visible.single { it.declaration.name == "first" }
        assertTrue(first.module!!.virtualFile.url.endsWith("!/models/util/ordering.als"))
        assertTrue(visible.names().containsAll(listOf("Item", "first", "last", "next")))
    }

    fun testPrivateDeclarationsOfOtherModulesAreHidden() {
        myFixture.addFileToProject(
            "a.als",
            "private sig H {}\nsig A { private g: A, h: A }\nprivate pred hidden {}\nprivate fun hiddenFun: A { A }\nprivate enum E { M }\npred shown {}",
        )
        val visible = visible("open a\nprivate pred mine {}\nfact { some <caret>A }")
        assertTrue(visible.names().containsAll(listOf("A", "h", "shown", "mine")))
        assertFalse(visible.names().any { it in listOf("H", "g", "hidden", "hiddenFun", "E", "M") })
    }

    fun testAliasQualifierYieldsOnlyThatModule() {
        myFixture.addFileToProject("a.als", "sig Item {}")
        val visible = visible("open a\nopen util/ordering[S] as ord\nsig S {}\nfact { some <caret>S }", "ord")
        assertTrue(visible.names().containsAll(listOf("first", "last", "next")))
        assertTrue(visible.all { it.module!!.virtualFile.url.endsWith("!/models/util/ordering.als") })
    }

    fun testThisQualifierYieldsTheCurrentFile() {
        val visible = visible("open util/ordering[S]\nsig S { f: set S }\npred p[x: S] { some <caret>x }", "this")
        assertEquals(setOf("S", "f", "p"), visible.names())
    }

    fun testUnknownQualifierYieldsNothing() {
        assertEmpty(visible("open util/ordering[S]\nsig S {}\nfact { some <caret>S }", "nope"))
    }
}
