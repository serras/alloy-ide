package com.serranofp.alloy.viewer

import com.intellij.codeInsight.hints.declarative.StringInlayActionPayload
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.FileEditorManagerTestCase
import com.intellij.util.ui.UIUtil
import com.serranofp.alloy.run.AlloyCommandRef
import com.serranofp.alloy.run.AlloyCommandResultClickHandler
import com.serranofp.alloy.run.AlloyCommandResultHintsProvider
import com.serranofp.alloy.run.AlloyCommandResults
import com.serranofp.alloy.run.CommandResult
import com.serranofp.alloy.run.CommandState
import com.serranofp.alloy.settings.AlloySettings

/**
 * A test of the editors that the platform's own editor manager opens, through [AlloyFileEditorProvider], unlike a
 * light test's fixture editor. Each test starts and ends with no session and no result.
 */
abstract class AlloyEditorTestCase : FileEditorManagerTestCase() {
    protected val viewer: AlloyInstanceViewer get() = AlloyInstanceViewer.getInstance(project)
    protected val results: AlloyCommandResults get() = AlloyCommandResults.getInstance(project)

    override fun setUp() {
        super.setUp()
        results.clear()
        viewer.clear()
    }

    override fun tearDown() {
        try {
            results.clear()
            viewer.clear()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    /** A new project file [name] with the text [SHOW_FILE]. */
    protected fun alloyFile(name: String = "m.als"): VirtualFile = myFixture.addFileToProject(name, SHOW_FILE).virtualFile

    /** The editors that opening [file] gives. */
    protected fun openEditors(file: VirtualFile): List<com.intellij.openapi.fileEditor.FileEditor> = manager!!.openFile(file, true).toList()

    protected fun openEditor(file: VirtualFile): AlloyEditorWithPreview = openEditors(file).single() as AlloyEditorWithPreview

    /** Records a current result of `show` in [file] that found the solution of [STATIC]. */
    protected fun record(file: VirtualFile): CommandResult {
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        val result = CommandResult(
            CommandState.InstanceFound, "found", link = link(STATIC),
            documentStamp = results.stamp(document), settingsGeneration = AlloySettings.getInstance().generation,
        )
        results.record(file, SHOW, result)
        return result
    }

    /** Clicks the hint of `show` in [editor]'s text, after recording its result. */
    protected fun clickShow(editor: AlloyEditorWithPreview, command: AlloyCommandRef = SHOW) {
        record(editor.file!!)
        AlloyCommandResultClickHandler().click(editor.editor, StringInlayActionPayload(AlloyCommandResultHintsProvider.Codec.encode(command)))
        UIUtil.dispatchAllInvocationEvents()
    }
}
