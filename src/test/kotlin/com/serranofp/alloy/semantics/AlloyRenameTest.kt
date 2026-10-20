package com.serranofp.alloy.semantics

import com.intellij.lang.LanguageRefactoringSupport
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.serranofp.alloy.AlloyLanguage
import com.serranofp.alloy.psi.AlloyBinderDecl
import com.serranofp.alloy.psi.AlloyFile
import com.serranofp.alloy.psi.AlloySigName
import java.util.concurrent.TimeUnit

class AlloyRenameTest : BasePlatformTestCase() {
    private fun analyze(vararg files: PsiFile) {
        for (file in files) {
            ApplicationManager.getApplication().executeOnPooledThread<AlloyModelResult> {
                ReadAction.compute<AlloyModelResult, Throwable> { AlloyModelService.getInstance(project).model(file as AlloyFile) }
            }.get(30, TimeUnit.SECONDS)
        }
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

    fun testRefusedOnALibraryDeclaration() {
        val text = "open util/ordering[S]\nsig S {}\nfact { some first }"
        val file = myFixture.configureByText("m.als", "open util/ordering[S]\nsig S {}\nfact { some first<caret> }")
        analyze(file)
        val message = refused("start")
        assertTrue(message, message.contains("read-only library module ordering"))
        assertEquals(text, file.text)
    }
}
