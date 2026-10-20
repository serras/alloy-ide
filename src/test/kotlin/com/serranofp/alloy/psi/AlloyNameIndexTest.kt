package com.serranofp.alloy.psi

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.serranofp.alloy.psi.stubs.NAME_INDEX_KEY

class AlloyNameIndexTest : BasePlatformTestCase() {
    private fun find(name: String): List<AlloyNamedDeclaration> =
        StubIndex.getElements(NAME_INDEX_KEY, name, project, GlobalSearchScope.projectScope(project), AlloyNamedDeclaration::class.java).toList()

    /** The names indexed for [file]; light tests share their project with other tests' files. */
    private fun names(file: com.intellij.openapi.vfs.VirtualFile): Set<String> {
        val names = mutableSetOf<String>()
        StubIndex.getInstance().processAllKeys(NAME_INDEX_KEY, { names += it; true }, GlobalSearchScope.fileScope(project, file), null)
        return names.filterTo(mutableSetOf()) { name -> find(name).any { it.containingFile.virtualFile == file } }
    }

    fun testFindsDeclarationsAcrossFilesWithoutParsingThem() {
        // Files on disk only: nothing has built their PSI or AST
        val a = myFixture.tempDirFixture.createFile("a.als", "sig Node { next: lone Node }\npred p[x: Node] { some x.next }")
        val b = myFixture.tempDirFixture.createFile("b.als", "open a\npred p { all n: Node | n in n.next }\nassert noCycles { no iden & ^next }")

        val ps = find("p")
        assertEquals(setOf(a, b), ps.map { it.containingFile.virtualFile }.toSet())
        assertTrue(ps.all { it is AlloyPredDecl })
        assertEquals(listOf("p", "p"), ps.map { it.name })
        assertInstanceOf(find("next").single(), AlloyFieldDecl::class.java)
        assertInstanceOf(find("Node").single(), AlloySigName::class.java)
        assertInstanceOf(find("noCycles").single(), AlloyAssertDecl::class.java)

        // The answers come from stubs: neither file was parsed
        for (file in listOf(a, b)) {
            val psi = PsiManager.getInstance(project).findFile(file) as PsiFileImpl
            assertNull("${file.name} was parsed", psi.treeElement)
        }
    }

    fun testTopLevelDeclarationsAndFields() {
        val file = myFixture.tempDirFixture.createFile(
            "m.als",
            """
            sig A, B { f, g: set A }
            enum Color { Red, Green }
            fun h[x: A]: set A { let y = x | { z: A | z in y } }
            let m[q] { q }
            assert { all w: A | w in A }
            fact named { some A }
            run show {} for 3
            """.trimIndent(),
        )
        // Named facts and commands too, for Go to Symbol; the unnamed assertion is not there
        assertEquals(setOf("A", "B", "f", "g", "Color", "Red", "Green", "h", "m", "named", "show"), names(file))
    }

    fun testIndexFollowsEdits() {
        val file = myFixture.configureByText("m.als", "sig A {}\npred p {}")
        assertEquals(1, find("p").size)
        WriteCommandAction.runWriteCommandAction(project) {
            val offset = myFixture.editor.document.text.indexOf("p {")
            myFixture.editor.document.replaceString(offset, offset + 1, "q")
        }
        com.intellij.psi.PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEmpty(find("p"))
        assertEquals(file.virtualFile, find("q").single().containingFile.virtualFile)
    }

    fun testDeclarationsNameTheirIdentifier() {
        myFixture.configureByText(
            "m.als",
            "sig A { f: set A }\nenum E { M }\npred A.p[x: A] { all y: x | let z = y | some z }\nfun g: A { { c: A | some c } }\nlet mac[q] { q }\nassert check1 {}",
        )
        val declarations = PsiTreeUtil.findChildrenOfType(myFixture.file, AlloyNamedDeclaration::class.java)
        assertEquals(
            listOf("A", "f", "E", "M", "p", "x", "y", "z", "g", "c", "mac", "q", "check1"),
            declarations.map { it.name },
        )
        for (declaration in declarations) {
            assertEquals(declaration.name, declaration.nameIdentifier!!.text)
            assertEquals(declaration.nameIdentifier!!.textOffset, declaration.textOffset)
        }
        // `pred A.p` is named by `p`, not by its receiver
        assertEquals("p", PsiTreeUtil.findChildOfType(myFixture.file, AlloyPredDecl::class.java)!!.nameIdentifier!!.text)
    }

    fun testSetNameReplacesTheIdentifier() {
        myFixture.configureByText("m.als", "sig A {}\npred p[x: A] { some x }")
        val parameter = PsiTreeUtil.findChildOfType(myFixture.file, AlloyParamDecl::class.java)!!
        val predicate = PsiTreeUtil.findChildOfType(myFixture.file, AlloyPredDecl::class.java)!!
        WriteCommandAction.runWriteCommandAction(project) {
            parameter.setName("y")
            predicate.setName("q")
        }
        assertEquals("sig A {}\npred q[y: A] { some x }", myFixture.file.text)
    }
}
