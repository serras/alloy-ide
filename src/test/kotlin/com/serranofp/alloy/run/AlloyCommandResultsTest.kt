package com.serranofp.alloy.run

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.psi.PsiDocumentManager
import com.serranofp.alloy.settings.AlloySettings

class AlloyCommandResultsTest : AlloyRunTestCase() {
    private val show = AlloyCommandRef("show", 0)

    private val instance by lazy { instanceLink() }

    override fun setUp() {
        super.setUp()
        myFixture.configureByText("model.als", "sig A {}\nrun show for 3\ncheck noCycles for 3\n")
    }

    private val file get() = myFixture.file.virtualFile
    private val document get() = myFixture.editor.document

    private fun recordShow(state: CommandState = CommandState.InstanceFound, summary: String = "Instance found · 4 ms") {
        results.record(
            file,
            show,
            CommandResult(state, summary, link = instance, documentStamp = results.stamp(document), settingsGeneration = AlloySettings.getInstance().generation),
        )
    }

    private val showResult get() = results.get(file, show)!!

    fun testNoResultBeforeAnExecution() {
        assertNull(results.get(file, show))
        assertEmpty(results.results(file).entries)
    }

    fun testCurrentAfterRecording() {
        recordShow()
        assertEquals(CommandState.InstanceFound, showResult.state)
        assertFalse(results.isOutdated(showResult, document))
        assertEquals(setOf(show), results.results(file).keys)
    }

    fun testOutdatedAfterAnEdit() {
        recordShow()
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, "// edit\n") }
        assertEquals(Outdated.FileChanged, results.outdated(showResult, document))
    }

    fun testStillOutdatedAfterUndo() {
        recordShow()
        val text = document.text
        myFixture.type("x")
        val editor = FileEditorManagerEx.getInstanceEx(project).getSelectedEditor(file) as TextEditor
        UndoManager.getInstance(project).undo(editor)
        assertEquals(text, document.text)
        assertTrue(results.isOutdated(showResult, document))
    }

    fun testOutdatedAfterASettingsChange() {
        recordShow()
        AlloySettings.getInstance().nextGeneration()
        assertEquals(Outdated.SettingsChanged, results.outdated(showResult, document))
    }

    fun testCurrentAgainWhenRecordedAgain() {
        recordShow()
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, "// edit\n") }
        AlloySettings.getInstance().nextGeneration()
        assertTrue(results.isOutdated(showResult, document))

        recordShow(CommandState.Running, AlloyRunLog.RUNNING)
        assertEquals(CommandState.Running, showResult.state)
        assertFalse(results.isOutdated(showResult, document))
    }

    /** Results last for the IDE session, not for the editor. */
    fun testCurrentAfterTheFileIsClosedAndReopened() {
        recordShow()
        val result = showResult
        FileEditorManager.getInstance(project).closeFile(file)
        myFixture.openFileInEditor(file)
        assertSame(result, showResult)
        assertNull(results.outdated(result, myFixture.editor.document))
    }

    fun testOtherCommandsAreUnaffected() {
        recordShow()
        assertNull(results.get(file, AlloyCommandRef("noCycles", 1)))
    }

    private fun refs(): List<AlloyCommandRef> = AlloyCommands.commands(myFixture.file).map(AlloyCommands::ref)

    fun testResultFollowsItsLabelWhenCommandsAreInsertedAbove() {
        recordShow()
        WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.text.indexOf("run show"), "run other for 2\n") }
        PsiDocumentManager.getInstance(project).commitDocument(document)
        val moved = refs()[1]
        assertEquals(1, moved.index)
        assertSame(showResult, results.get(file, moved))
        assertNull(results.get(file, refs()[0]))
    }

    fun testCommandsSharingALabelHaveTheirOwnResults() {
        myFixture.configureByText("model.als", "sig A {}\ncheck a for 3\ncheck a for 6\nrun {} for 2\n")
        val (first, second, unlabelled) = refs()
        assertEquals(listOf(0, 1, 0), listOf(first.occurrence, second.occurrence, unlabelled.occurrence))
        assertFalse(first == second)
        assertEquals("check a for 6", AlloyCommands.find(myFixture.file, second)!!.text)
        assertEquals(second, AlloyCommandResultHintsProvider.Codec.decode(AlloyCommandResultHintsProvider.Codec.encode(second)))
        // A label is the same with or without `this/`
        assertEquals(second, AlloyCommandRef("this/a", 7, 1))
    }

    fun testOnlyTheLatestSolutionsAreKept() {
        val refs = (0..AlloyCommandResults.MAX_SOLUTIONS).map { AlloyCommandRef("c$it", it) }
        for (ref in refs) {
            results.record(file, ref, CommandResult(CommandState.InstanceFound, "Instance found · 1 ms", link = instance, documentStamp = 0, settingsGeneration = 0))
        }
        assertNull(results.get(file, refs.first())!!.link)
        assertEquals("Instance found · 1 ms", results.get(file, refs.first())!!.summary)
        assertTrue(refs.drop(1).all { results.get(file, it)!!.link != null })
    }

    fun testResultsOfADeletedFileAreDropped() {
        val other = myFixture.addFileToProject("other.als", "run show for 3\n").virtualFile
        results.record(other, show, CommandResult(CommandState.InstanceFound, "Instance found · 1 ms", link = instance, documentStamp = 0, settingsGeneration = 0))
        WriteAction.run<Exception> { other.delete(this) }
        assertEmpty(results.results(other).entries)
        // Nor does a late event of its execution bring them back
        results.record(other, show, CommandResult(CommandState.Stopped, AlloyRunLog.STOPPED, documentStamp = 0, settingsGeneration = 0))
        assertEmpty(results.results(other).entries)
    }

    fun testOutdatedAfterAnEditOfAnOpenedModule() {
        val library = myFixture.addFileToProject("lib.als", "module lib\nsig Item {}\n").virtualFile
        val generation = AlloySettings.getInstance().generation
        results.record(file, show, CommandResult(CommandState.InstanceFound, "", documentStamp = results.stamp(document), settingsGeneration = generation, modules = setOf(library)))
        val unrelated = myFixture.addFileToProject("unrelated.als", "sig B {}\n").virtualFile
        val documents = FileDocumentManager.getInstance()
        WriteCommandAction.runWriteCommandAction(project) { documents.getDocument(unrelated)!!.insertString(0, "// edit\n") }
        assertNull(results.outdated(showResult, document))

        WriteCommandAction.runWriteCommandAction(project) { documents.getDocument(library)!!.insertString(0, "// edit\n") }
        assertEquals(Outdated.FileChanged, results.outdated(showResult, document))
    }
}
