package com.serranofp.alloy.editor

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.icons.AllIcons
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.JBColor
import com.serranofp.alloy.psi.AlloyNamedDeclaration

class AlloyCompletionTest : BasePlatformTestCase() {
    private val declarations = "sig A {}\npred p {}\nfun g: A { A }\nassert q { some A }\n"

    /** The items offered at the caret of [text], without inserting a single one. */
    private fun items(text: String, name: String = "m.als"): List<LookupElement> {
        myFixture.configureByText(name, text)
        val settings = CodeInsightSettings.getInstance()
        val autocomplete = settings.AUTOCOMPLETE_ON_CODE_COMPLETION
        settings.AUTOCOMPLETE_ON_CODE_COMPLETION = false
        try {
            return myFixture.completeBasic()?.toList().orEmpty()
        } finally {
            settings.AUTOCOMPLETE_ON_CODE_COMPLETION = autocomplete
        }
    }

    private fun names(text: String, name: String = "m.als"): List<String> = items(text, name).map { it.lookupString }

    private fun names(items: List<LookupElement>): List<String> = items.map { it.lookupString }

    private fun render(item: LookupElement): LookupElementPresentation = LookupElementPresentation.renderElement(item)

    // Names in scope

    fun testTopLevelAndOpenedNames() {
        myFixture.addFileToProject("a.als", "sig A {}\npred p[x: A] {}")
        val names = names("open a\nsig B {}\nfact { some <caret> }", "b.als")
        assertContainsElements(names, "A", "p", "B")
    }

    fun testBindersAndParameters() {
        assertContainsElements(names("sig A {}\npred q[y: A] { all z: A | some <caret> }"), "y", "z")
    }

    fun testBinderOutOfScopeInItsBound() {
        assertDoesntContain(names("sig A {}\npred q { all z: <caret> | some z }"), "z")
    }

    fun testFieldsOfTheEnclosingSignature() {
        assertContainsElements(names("sig S { f: set S } { some <caret> }"), "f")
    }

    fun testBundledModuleNames() {
        assertContainsElements(names("open util/ordering[S]\nsig S {}\nfact { some <caret> }"), "first", "last", "next")
    }

    fun testPrivateDeclarationsOfAnOpenedModule() {
        myFixture.addFileToProject("a.als", "sig A {}\nprivate pred hidden {}")
        val names = names("open a\nfact { some <caret> }", "b.als")
        assertContainsElements(names, "A")
        assertDoesntContain(names, "hidden")
    }

    fun testModelWithErrors() {
        val valid = names("open util/ordering[S]\nsig S {}\npred p {}\nfact { some <caret> }").toSet()
        val withError = names("open util/ordering[S]\nsig S {}\npred p {}\npred broken { some Undefined }\nfact { some <caret> }").toSet()
        assertEquals(valid + "broken", withError)
    }

    fun testNoNamesInCommentsOrStrings() {
        // The platform's word completion can still offer the words of the file in comments, as plain text
        fun declarations(text: String) = items(text).filter { it.psiElement is AlloyNamedDeclaration }
        assertEmpty(declarations("sig Apple {}\n// A<caret>"))
        assertEmpty(declarations("sig Apple {}\n/* A<caret> */"))
        assertEmpty(declarations("sig Apple {}\nfact { \"A<caret>\" }"))
    }

    // Qualified names

    fun testAliasQualifier() {
        val names = names("open util/ordering[S] as ord\nsig S {}\npred own {}\nfact { some ord/<caret> }")
        assertContainsElements(names, "first", "last", "next")
        assertDoesntContain(names, "S", "own")
        // No keywords after a qualifier
        assertDoesntContain(names, "all", "some")
    }

    fun testPathQualifier() {
        val names = names("open util/ordering[S]\nsig S {}\nfact { some util/ordering/<caret> }")
        assertContainsElements(names, "first", "last", "next")
        assertDoesntContain(names, "S")
    }

    fun testThisQualifier() {
        val names = names("open util/ordering[S]\nsig S {}\npred own {}\nfact { some this/<caret> }")
        assertContainsElements(names, "S", "own")
        assertDoesntContain(names, "first")
    }

    fun testUnknownQualifier() {
        assertEmpty(names("open util/ordering[S]\nsig S {}\nfact { some nope/<caret> }"))
    }

    /** A `/` that is not inside a name, here among a module's parameters, qualifies nothing. */
    fun testSlashOutsideANameIsNoQualifier() {
        myFixture.configureByText("m.als", "module m[x / y]")
        val position = myFixture.file.findElementAt(myFixture.file.text.indexOf('y'))!!
        assertFalse(AlloyCompletionContext.of(position) is AlloyCompletionContext.Qualified)
    }

    // Completion filtered by context

    fun testRunTarget() {
        val names = names(declarations + "run <caret>")
        assertContainsElements(names, "p", "g")
        assertDoesntContain(names, "A", "q")
    }

    fun testCheckTarget() {
        val names = names(declarations + "check <caret>")
        assertContainsElements(names, "q")
        assertDoesntContain(names, "A", "p", "g")
    }

    fun testSignatureParent() {
        val extends = names(declarations + "sig B extends <caret> {}")
        assertContainsElements(extends, "A")
        assertDoesntContain(extends, "p", "g", "q")
        val subset = names(declarations + "sig C in <caret> {}")
        assertContainsElements(subset, "A")
        assertDoesntContain(subset, "p", "g", "q")
    }

    fun testNoKeywordsInModulePaths() {
        assertDoesntContain(names("open <caret>\nsig S {}"), "abstract", "sig", "all")
    }

    // Presentation

    fun testItemFromAnOpenedModule() {
        val first = items("open util/ordering[S]\nsig S {}\nfact { some <caret> }").single { it.lookupString == "first" }
        val presentation = render(first)
        assertEquals(AllIcons.Nodes.Function, presentation.icon)
        assertEquals("function", presentation.typeText)
        assertEquals("util/ordering", presentation.tailText?.trim())
    }

    fun testLocalItem() {
        val a = items(declarations + "fact { some <caret> }").single { it.lookupString == "A" }
        val presentation = render(a)
        assertEquals(AllIcons.Nodes.Class, presentation.icon)
        assertEquals("signature", presentation.typeText)
        assertNull(presentation.tailText)
    }

    fun testKindsOfLocals() {
        val items = items("sig A {}\npred q[y: A] { all z: A | some <caret> }")
        assertEquals("parameter", render(items.single { it.lookupString == "y" }).typeText)
        assertEquals("binder", render(items.single { it.lookupString == "z" }).typeText)
    }

    fun testBinderRanksBeforeTopLevelDeclaration() {
        val names = names("sig A {}\npred za {}\npred p { all zb: A | some z<caret> }")
        assertTrue(names.toString(), names.indexOf("zb") in 0 until names.indexOf("za"))
    }

    fun testQualifiedCompletionInsertsOnlyTheIdentifier() {
        myFixture.configureByText("m.als", "open util/ordering[S] as ord\nsig S {}\nfact { some ord/fi<caret> }")
        assertNull("single match is inserted directly", myFixture.completeBasic())
        myFixture.checkResult("open util/ordering[S] as ord\nsig S {}\nfact { some ord/first<caret> }")
    }

    // Module paths

    /** Opens [path] in the project with [text], whose `<caret>` marks the caret, and lists what completion offers. */
    private fun moduleItems(path: String, text: String): List<LookupElement> {
        val file = myFixture.addFileToProject(path, text.replace("<caret>", ""))
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(text.indexOf("<caret>"))
        val settings = CodeInsightSettings.getInstance()
        val autocomplete = settings.AUTOCOMPLETE_ON_CODE_COMPLETION
        settings.AUTOCOMPLETE_ON_CODE_COMPLETION = false
        try {
            return myFixture.completeBasic()?.toList().orEmpty()
        } finally {
            settings.AUTOCOMPLETE_ON_CODE_COMPLETION = autocomplete
        }
    }

    fun testSiblingModule() {
        myFixture.addFileToProject("m/lib.als", "module lib\nsig Item {}")
        val names = names(moduleItems("m/main.als", "module main\nopen <caret>"))
        assertContainsElements(names, "lib")
        // Never the current file, and no names or keywords
        assertDoesntContain(names, "main", "Item", "sig")
    }

    fun testModuleInASubdirectory() {
        myFixture.addFileToProject("m/sub/x.als", "module app/sub/x")
        assertContainsElements(names(moduleItems("m/main.als", "module app/main\nopen <caret>")), "app/sub/x")
    }

    fun testFileWithoutAModuleDeclaration() {
        myFixture.addFileToProject("m/sub/y.als", "sig Y {}")
        assertContainsElements(names(moduleItems("m/main.als", "module main\nopen <caret>")), "sub/y")
    }

    fun testBundledModules() {
        val names = names(moduleItems("m/main.als", "module main\nopen util/<caret>"))
        assertContainsElements(names, "util/ordering", "util/integer", "util/boolean", "util/relation", "util/graph")
    }

    fun testBundledModulesCompleteThePath() {
        myFixture.addFileToProject("m/main.als", "module main\nopen util/orde\nsig S {}")
        myFixture.configureFromTempProjectFile("m/main.als")
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("orde") + 4)
        assertNull("single match is inserted directly", myFixture.completeBasic())
        assertEquals("module main\nopen util/ordering\nsig S {}", myFixture.file.text)
    }

    fun testProjectModuleShadowsABundledOne() {
        val ordering = myFixture.addFileToProject("m/util/ordering.als", "module util/ordering[elem]")
        val items = moduleItems("m/main.als", "module main\nopen util/<caret>").filter { it.lookupString == "util/ordering" }
        assertEquals(ordering, items.single().psiElement)
    }

    fun testUnreachableModuleIsGreyedOutAndLast() {
        myFixture.addFileToProject("m/b/x.als", "module foo/x")
        myFixture.addFileToProject("m/lib.als", "module lib")
        val items = moduleItems("m/main.als", "module main\nopen <caret>")
        val unreachable = items.single { it.lookupString == "foo/x" }
        val presentation = render(unreachable)
        assertEquals("cannot be opened from this file", presentation.tailText?.trim())
        assertEquals(JBColor.GRAY, presentation.itemTextForeground)
        assertFalse(render(items.single { it.lookupString == "lib" }).itemTextForeground == JBColor.GRAY)
        assertEquals(items.last(), unreachable)
    }

    fun testUnreachableModuleIsNeverInsertedOnAUniquePrefix() {
        myFixture.addFileToProject("m/b/x.als", "module foo/x")
        myFixture.addFileToProject("m/main.als", "module main\nopen fo\nsig S {}")
        myFixture.configureFromTempProjectFile("m/main.als")
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("fo") + 2)
        val items = myFixture.completeBasic()
        assertNotNull("the lookup stays open", items)
        assertEquals(listOf("foo/x"), items!!.map { it.lookupString })
        assertEquals("module main\nopen fo\nsig S {}", myFixture.file.text)
    }
}
