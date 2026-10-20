package com.serranofp.alloy.semantics

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.serranofp.alloy.psi.AlloyBinderDecl
import com.serranofp.alloy.psi.AlloyFieldDecl
import com.serranofp.alloy.psi.AlloyFile
import com.serranofp.alloy.psi.AlloyFunDecl
import com.serranofp.alloy.psi.AlloyNamedDeclaration
import com.serranofp.alloy.psi.AlloyParamDecl
import com.serranofp.alloy.psi.AlloyPredDecl
import com.serranofp.alloy.psi.AlloySigDecl
import com.serranofp.alloy.psi.AlloySigName
import java.util.concurrent.TimeUnit

class AlloyReferenceTest : BasePlatformTestCase() {
    /** Computes the model off the EDT, as highlighting does, so that resolution on the EDT can use it. */
    private fun analyze(file: PsiFile = myFixture.file): AlloyModelResult =
        ApplicationManager.getApplication().executeOnPooledThread<AlloyModelResult> {
            ReadAction.compute<AlloyModelResult, Throwable> { AlloyModelService.getInstance(project).model(file as AlloyFile) }
        }.get(30, TimeUnit.SECONDS)

    /** The reference at the start of [marker], or at its `^`. */
    private fun reference(marker: String, file: PsiFile = myFixture.file): PsiPolyVariantReference {
        val text = marker.replace("^", "")
        val start = file.text.indexOf(text)
        assertTrue("no $text", start >= 0)
        val offset = start + marker.indexOf('^').coerceAtLeast(0)
        val reference = file.findReferenceAt(offset)
        assertNotNull("no reference at $marker in\n${com.intellij.psi.impl.DebugUtil.psiToString(file, true)}", reference)
        return reference as PsiPolyVariantReference
    }

    private fun targets(marker: String, file: PsiFile = myFixture.file): List<AlloyNamedDeclaration> =
        reference(marker, file).multiResolve(false).map { it.element as AlloyNamedDeclaration }

    private fun target(marker: String, file: PsiFile = myFixture.file): AlloyNamedDeclaration = targets(marker, file).single()

    private fun AlloyNamedDeclaration.line(): Int =
        containingFile.viewProvider.document!!.getLineNumber(textOffset)

    private fun exact(marker: String): Boolean = (reference(marker) as AlloyReference).isExact()

    // Go to declaration, with a model that type-checks

    fun testSignature() {
        myFixture.configureByText("m.als", "sig Node {}\npred p { some Node<use> }".replace("<use>", ""))
        analyze()
        val target = target("Node }")
        assertInstanceOf(target, AlloySigName::class.java)
        assertEquals(0, target.line())
        assertTrue(exact("Node }"))
    }

    fun testAcrossFiles() {
        myFixture.addFileToProject("a.als", "sig A {}\npred p[x: A] { some x }")
        myFixture.configureByText("b.als", "open a\nrun { p[A] } for 3")
        analyze()
        val target = target("p[A]")
        assertInstanceOf(target, AlloyPredDecl::class.java)
        assertEquals("a.als", target.containingFile.name)
        assertEquals("p", target.name)
    }

    fun testIntoTheOrderingLibrary() {
        myFixture.configureByText("m.als", "open util/ordering[S]\nsig S {}\nfact { some S.next and S in first.*next }")
        analyze()
        val target = target("first.")
        assertInstanceOf(target, AlloyFunDecl::class.java)
        assertEquals("first", target.name)
        assertTrue(target.containingFile.virtualFile.fileSystem is JarFileSystem)
        assertFalse(target.containingFile.virtualFile.isWritable)
        assertTrue(target.containingFile.virtualFile.url.endsWith("models/util/ordering.als"))
    }

    fun testLocalBinder() {
        myFixture.configureByText("m.als", "sig A { f: set A }\npred p { all x: A | some x.f }")
        analyze()
        val target = target("x.f")
        assertInstanceOf(target, AlloyBinderDecl::class.java)
        assertEquals("all x", target.parent.text.substring(0, 5))
    }

    fun testParameter() {
        myFixture.configureByText("m.als", "sig A {}\npred p[a: A] { some a }")
        analyze()
        assertInstanceOf(target("a }"), AlloyParamDecl::class.java)
    }

    fun testHeaderNames() {
        myFixture.configureByText("m.als", "sig A {}\nsig B extends A {}\npred A.q {}\npred show {}\nrun show for 3 but 2 A")
        analyze()
        assertEquals("A", target("A {}\npred").name)
        assertInstanceOf(target("A.q"), AlloySigName::class.java)
        assertInstanceOf(target("show for"), AlloyPredDecl::class.java)
        assertEquals("A", target("but 2 ^A").name)
        assertNull(myFixture.file.findReferenceAt(myFixture.file.text.indexOf("q {}")))
    }

    // Exact resolution for a valid model

    private val overloaded = "sig A { f: set A }\nsig B { f: set B }\npred p[a: A] { some a.f }"

    fun testOverloadedFieldResolvesExactly() {
        myFixture.configureByText("m.als", overloaded)
        analyze()
        val target = target("f }")
        assertInstanceOf(target, AlloyFieldDecl::class.java)
        assertEquals(0, target.line())
        assertTrue(exact("f }"))
    }

    // Name-based resolution for a model with errors

    fun testNavigatingWhileEditing() {
        myFixture.configureByText("m.als", "sig Node {}\npred broken { some Undefined }\npred p { some Node }")
        assertInstanceOf(analyze(), AlloyModelResult.Errors::class.java)
        val target = target("Node }")
        assertInstanceOf(target, AlloySigName::class.java)
        assertFalse(exact("Node }"))
    }

    fun testInnerBinderShadowsOuterDeclaration() {
        myFixture.configureByText("m.als", "sig x {}\nsig A {}\npred broken { some Undefined }\npred p { some x: A | x in A }")
        analyze()
        assertInstanceOf(target("x in"), AlloyBinderDecl::class.java)
    }

    fun testBinderIsNotInScopeInItsOwnBound() {
        myFixture.configureByText("m.als", "sig x { f: set x }\npred broken { some Undefined }\npred p { all x: x.f | some x }")
        analyze()
        assertInstanceOf(target("x.f"), AlloySigName::class.java)
        assertInstanceOf(target("some ^x }"), AlloyBinderDecl::class.java)
    }

    fun testAmbiguousNameOffersEveryCandidate() {
        myFixture.configureByText("m.als", "$overloaded\npred broken { some Undefined }")
        analyze()
        val targets = targets("f }")
        assertEquals(2, targets.size)
        assertTrue(targets.all { it is AlloyFieldDecl })
        assertEquals(setOf(0, 1), targets.map { it.line() }.toSet())
    }

    fun testFieldOfTheEnclosingSignatureComesFirst() {
        myFixture.configureByText("m.als", "sig A { f: set A } { some f }\nsig B { f: set B }\npred broken { some Undefined }")
        analyze()
        assertEquals(0, target("f }\nsig B").line())
    }

    fun testQualifiedNameIntoAnOpenedLibrary() {
        myFixture.configureByText("m.als", "open util/ordering[S] as ord\nsig S {}\nfact { some ord/first and some Undefined }")
        analyze()
        val target = target("ord/first")
        assertEquals("first", target.name)
        assertTrue(target.containingFile.virtualFile.url.endsWith("models/util/ordering.als"))
    }

    fun testOpenedProjectModuleByName() {
        myFixture.addFileToProject("lib.als", "module lib\nsig Item {}")
        myFixture.configureByText("main.als", "open lib\nsig Box { items: set Item }\npred broken { some Undefined }")
        analyze()
        assertEquals("lib.als", target("Item }").containingFile.name)
    }

    fun testBuiltinsResolveToNothing() {
        myFixture.configureByText("m.als", "sig A { f: set univ }\npred broken { some Undefined }\npred p { some A & Int }")
        analyze()
        assertEmpty(targets("Undefined"))
    }

    fun testWithoutAComputedModelResolutionIsByName() {
        myFixture.configureByText("m.als", overloaded)
        // On the EDT, before any highlighting computed the model
        assertEquals(2, targets("f }").size)
        assertFalse(exact("f }"))
        analyze()
        assertEquals(1, targets("f }").size)
    }

    fun testSigDeclarationsAreTheirNames() {
        myFixture.configureByText("m.als", "sig A, B {}\npred p { some B }")
        analyze()
        val target = target("B }")
        assertEquals("B", target.name)
        assertInstanceOf(target.parent, AlloySigDecl::class.java)
    }

    // Paths of `open` declarations

    private fun module(marker: String): PsiElement? {
        val reference = myFixture.file.findReferenceAt(myFixture.file.text.indexOf(marker))
        assertInstanceOf(reference, AlloyModuleReference::class.java)
        assertEquals(marker.substringBefore('['), reference!!.rangeInElement.substring(reference.element.text))
        return reference.resolve()
    }

    fun testOpenPathToAProjectModule() {
        val a = myFixture.addFileToProject("a.als", "sig A {}")
        myFixture.configureByText("b.als", "open a\nfact { some A }")
        assertEquals(a, module("a"))
    }

    fun testOpenPathToALibraryModule() {
        myFixture.configureByText("m.als", "open util/ordering[S] as ord\nsig S {}")
        val target = module("util/ordering[") as AlloyFile
        assertTrue(target.virtualFile.url, target.virtualFile.url.endsWith("!/models/util/ordering.als"))
        // The whole path is one reference, also from its last name
        assertSame(myFixture.file.findReferenceAt(myFixture.file.text.indexOf("ordering"))!!.element,
            myFixture.file.findReferenceAt(myFixture.file.text.indexOf("util"))!!.element)
    }

    fun testUnknownOpenPathResolvesToNothing() {
        myFixture.configureByText("m.als", "open util/nothing\nsig S {}")
        assertNull(module("util/nothing"))
    }

    fun testAliasesAndModuleNamesAreNotReferences() {
        myFixture.configureByText("m.als", "module m\nopen util/ordering[S] as ord\nsig S {}")
        assertNull(myFixture.file.findReferenceAt(myFixture.file.text.indexOf("ord\n")))
        assertNull(myFixture.file.findReferenceAt(myFixture.file.text.indexOf("m\n")))
    }

    fun testRenamingTheOpenedModuleKeepsThePath() {
        myFixture.addFileToProject("a.als", "sig A {}")
        myFixture.configureByText("b.als", "open a\nfact { some A }")
        val reference = myFixture.file.findReferenceAt(myFixture.file.text.indexOf("a\n"))!!
        WriteCommandAction.runWriteCommandAction(project) { reference.handleElementRename("c.als") }
        assertEquals("open a\nfact { some A }", myFixture.file.text)
    }
}
