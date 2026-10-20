package com.serranofp.alloy.run

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.serranofp.alloy.settings.AlloySettings

class AlloyCommandResultsTest : BasePlatformTestCase() {
    private val results get() = AlloyCommandResults.getInstance(project)
    private val show = AlloyCommandRef("show", 0)

    override fun setUp() {
        super.setUp()
        results.clear()
        myFixture.configureByText("model.als", "sig A {}\nrun show for 3\ncheck noCycles for 3\n")
    }

    private val file get() = myFixture.file.virtualFile
    private val document get() = myFixture.editor.document

    private fun recordShow(state: CommandState = CommandState.InstanceFound, summary: String = "Instance found · 4 ms") {
        results.record(
            file,
            show,
            CommandResult(state, summary, link = "XML: /tmp/0.cnf.xml", documentStamp = results.stamp(document), settingsGeneration = AlloySettings.getInstance().generation),
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

    fun testOtherCommandsAreUnaffected() {
        recordShow()
        assertNull(results.get(file, AlloyCommandRef("noCycles", 1)))
    }
}
