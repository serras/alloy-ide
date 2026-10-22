package com.serranofp.alloy.semantics

import com.intellij.lang.LanguageRefactoringSupport
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.rename.RenameUtil
import com.intellij.refactoring.rename.inplace.VariableInplaceRenameHandler
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.fixtures.CodeInsightTestUtil
import com.serranofp.alloy.AlloyLanguage
import com.serranofp.alloy.psi.AlloyBinderDecl
import com.serranofp.alloy.psi.AlloyElementFactory
import com.serranofp.alloy.psi.AlloySigName

class AlloyRenameTest : BasePlatformTestCase() {
    private fun analyze(vararg files: PsiFile) {
        files.forEach(::computeModel)
    }

    /** The message of the refusal, which tests get as an exception instead of a hint. */
    private fun refused(newName: String): String {
        try {
            myFixture.renameElementAtCaret(newName)
        } catch (e: CommonRefactoringUtil.RefactoringErrorHintException) {
            return e.message!!
        }
        fail("the rename was not refused")
        error("unreachable")
    }

    fun testRenamingASignatureAcrossFilesWithASingleUndo() {
        val bText = "open a\nsig C extends A {}\nrun { some A } for 3 but 2 A"
        val b = myFixture.addFileToProject("b.als", bText)
        val aText = "sig A {}\npred p[x: A] { some x }"
        val a = myFixture.configureByText("a.als", "sig A<caret> {}\npred p[x: A] { some x }")
        analyze(a, b)

        myFixture.renameElementAtCaret("Node")
        assertEquals("sig Node {}\npred p[x: Node] { some x }", a.text)
        assertEquals("open a\nsig C extends Node {}\nrun { some Node } for 3 but 2 Node", b.text)

        // One undo for both files, after the confirmation that the platform asks for an undo across files
        val editor = FileEditorManager.getInstance(project).getSelectedEditor(a.virtualFile) as TextEditor
        TestDialogManager.setTestDialog(TestDialog.OK, testRootDisposable)
        UndoManager.getInstance(project).undo(editor)
        assertEquals(aText, myFixture.editor.document.text)
        assertEquals(bText, b.viewProvider.document!!.text)
    }

    fun testRenamingFromAReference() {
        val file = myFixture.configureByText("m.als", "sig A {}\npred show {}\nrun show<caret> for 3")
        analyze(file)
        myFixture.renameElementAtCaret("display")
        assertEquals("sig A {}\npred display {}\nrun display for 3", file.text)
    }

    fun testRenamingALocalBinder() {
        val file = myFixture.configureByText(
            "m.als",
            "sig A { f: set A }\npred p { all x<caret>: A | some x.f }\npred q { some x: A | x in A }",
        )
        analyze(file)
        myFixture.renameElementAtCaret("y")
        assertEquals("sig A { f: set A }\npred p { all y: A | some y.f }\npred q { some x: A | x in A }", file.text)
    }

    fun testLocalBindersAreRenamedInPlace() {
        val file = myFixture.configureByText("m.als", "sig A {}\npred p { all x: A | some x }")
        val support = LanguageRefactoringSupport.getInstance().forLanguage(AlloyLanguage)
        assertTrue(support.isInplaceRenameAvailable(PsiTreeUtil.findChildOfType(file, AlloyBinderDecl::class.java)!!, null))
        assertFalse(support.isInplaceRenameAvailable(PsiTreeUtil.findChildOfType(file, AlloySigName::class.java)!!, null))
    }

    fun testRenamingThroughAQualifiedUseKeepsTheQualifier() {
        val b = myFixture.addFileToProject("b.als", "open a as x\nrun { x/p } for 3\nrun x/p for 2")
        val a = myFixture.configureByText("a.als", "sig A {}\npred p<caret> { some A }")
        analyze(a, b)
        myFixture.renameElementAtCaret("q")
        assertEquals("sig A {}\npred q { some A }", a.text)
        assertEquals("open a as x\nrun { x/q } for 3\nrun x/q for 2", b.text)
    }

    fun testRenamingOneOfTwoOverloadedFields() {
        val file = myFixture.configureByText(
            "m.als",
            "sig A { f<caret>: set A }\nsig B { f: set B }\npred p[a: A, b: B] { some a.f and some b.f }",
        )
        analyze(file)
        myFixture.renameElementAtCaret("g")
        assertEquals("sig A { g: set A }\nsig B { f: set B }\npred p[a: A, b: B] { some a.g and some b.f }", file.text)
    }

    fun testRenamingAnEnumMember() {
        val file = myFixture.configureByText("m.als", "enum Color { Red<caret>, Green }\nfact { some Red and Red != Green }")
        analyze(file)
        myFixture.renameElementAtCaret("Crimson")
        assertEquals("enum Color { Crimson, Green }\nfact { some Crimson and Crimson != Green }", file.text)
    }

    fun testInPlaceRenameOfALocalBinder() {
        val file = myFixture.configureByText("m.als", "sig A { f: set A }\npred p { all x<caret>: A | some x.f and x in A }")
        analyze(file)
        CodeInsightTestUtil.doInlineRename(VariableInplaceRenameHandler(), "y", myFixture)
        assertEquals("sig A { f: set A }\npred p { all y: A | some y.f and y in A }", file.text)
    }

    fun testRefusedOnAModelWithErrors() {
        val text = "sig A {}\npred p { some A and some Undefined }"
        val file = myFixture.configureByText("m.als", "sig A<caret> {}\npred p { some A and some Undefined }")
        analyze(file)
        val message = refused("B")
        assertTrue(message, message.startsWith(AlloyRenameProcessor.NEEDS_A_MODEL_WITHOUT_ERRORS))
        assertTrue(message, message.contains("m.als has errors"))
        assertEquals(text, file.text)
    }

    fun testRefusedWhenAFileWithUsesHasErrors() {
        val b = myFixture.addFileToProject("b.als", "open a\nrun { some A and some Undefined } for 3")
        val a = myFixture.configureByText("a.als", "sig A<caret> {}")
        analyze(a, b)
        val message = refused("B")
        assertTrue(message, message.contains("b.als has errors"))
        assertEquals("sig A {}", a.text)
    }

    fun testKeywordsAndNonIdentifiersAreNotValidNames() {
        val file = myFixture.configureByText("m.als", "sig A {}")
        val sig = PsiTreeUtil.findChildOfType(file, AlloySigName::class.java)!!
        for (name in listOf("sig", "all", "always", "Int", "this", "steps", "a b", "1a", "a/b", "x'", "")) {
            assertFalse(name, RenameUtil.isValidName(project, sig, name))
        }
        for (name in listOf("Node", "a\"b", "set_of")) assertTrue(name, RenameUtil.isValidName(project, sig, name))
        assertThrows(IllegalArgumentException::class.java) { AlloyElementFactory.identifier(project, "a b") }
    }

    fun testCancelledCheckAbortsTheRename() {
        val file = myFixture.configureByText("m.als", "sig A {}")
        AlloyModelService.getInstance(project).parse = { _, _, _ -> throw ProcessCanceledException() }
        try {
            assertNull(AlloyRenameProcessor().substituteElementToRename(PsiTreeUtil.findChildOfType(file, AlloySigName::class.java)!!, null))
        } finally {
            AlloyModelService.getInstance(project).restoreParse()
        }
    }

    fun testRefusedOnAReadOnlyProjectFile() {
        val file = myFixture.configureByText("m.als", "sig A {}")
        WriteAction.runAndWait<Throwable> { file.virtualFile.isWritable = false }
        try {
            val message = AlloyRenameProcessor().refusal(PsiTreeUtil.findChildOfType(file, AlloySigName::class.java)!!)!!
            assertTrue(message, message.contains("m.als is read-only"))
            assertFalse(message, message.contains("library"))
        } finally {
            WriteAction.runAndWait<Throwable> { file.virtualFile.isWritable = true }
        }
    }

    fun testRefusedOnALibraryDeclaration() {
        val text = "open util/ordering[S]\nsig S {}\nfact { some first }"
        val file = myFixture.configureByText("m.als", "open util/ordering[S]\nsig S {}\nfact { some first<caret> }")
        analyze(file)
        val message = refused("start")
        assertTrue(message, message.contains("read-only library module ordering"))
        assertEquals(text, file.text)
    }
}
