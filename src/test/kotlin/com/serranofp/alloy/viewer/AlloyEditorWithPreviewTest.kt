package com.serranofp.alloy.viewer

import com.intellij.openapi.fileEditor.FileEditorStateLevel
import com.intellij.openapi.fileEditor.TextEditorWithPreview.Layout
import com.intellij.openapi.fileEditor.TextEditorWithPreview.MyFileEditorState
import com.intellij.openapi.util.Disposer
import com.serranofp.alloy.run.waitUntil
import javax.swing.SwingConstants

class AlloyEditorWithPreviewTest : AlloyEditorTestCase() {
    fun testANewEditorShowsTheTextOnlySideBySide() {
        val editor = openEditor(alloyFile())
        assertEquals(Layout.SHOW_EDITOR, editor.getLayout())
        assertFalse(editor.isVerticalSplit())
    }

    private fun AlloyEditorWithPreview.restore(layout: Layout) {
        val saved = getState(FileEditorStateLevel.FULL) as MyFileEditorState
        setState(MyFileEditorState(layout, saved.firstState, saved.secondState, saved.isVerticalSplit))
    }

    fun testASavedPreviewNeedsASession() {
        val file = alloyFile()
        val editor = openEditor(file)
        editor.restore(Layout.SHOW_EDITOR_AND_PREVIEW)
        assertEquals(Layout.SHOW_EDITOR, editor.getLayout())
        viewer.open(file, SHOW, record(file))
        editor.restore(Layout.SHOW_EDITOR_AND_PREVIEW)
        assertEquals(Layout.SHOW_EDITOR_AND_PREVIEW, editor.getLayout())
    }

    fun testClosingTheEditorDropsTheSession() {
        val file = alloyFile()
        val editor = openEditor(file)
        viewer.open(file, SHOW, record(file))
        assertNotNull(viewer.session(file))
        Disposer.dispose(editor)
        assertNull(viewer.session(file))
    }

    fun testClosingTheFileDropsTheSession() {
        val file = alloyFile()
        openEditor(file)
        viewer.open(file, SHOW, record(file))
        manager!!.closeFile(file)
        assertNull(viewer.session(file))
    }

    fun testTheHintShowsThePreviewBesideTheText() {
        val editor = openEditor(alloyFile())
        clickShow(editor)
        assertEquals(Layout.SHOW_EDITOR_AND_PREVIEW, editor.getLayout())
        assertNotNull(viewer.session(editor.file!!))
    }

    fun testThePreviewOnlyStaysSo() {
        val editor = openEditor(alloyFile())
        editor.setLayout(Layout.SHOW_PREVIEW)
        clickShow(editor)
        assertEquals(Layout.SHOW_PREVIEW, editor.getLayout())
    }

    fun testOnlyTheClickedEditorOfTheFileChanges() {
        val file = alloyFile()
        val first = openEditor(file)
        manager!!.currentWindow!!.split(SwingConstants.VERTICAL, true, file, true)
        fun editors() = manager!!.getAllEditors(file).filterIsInstance<AlloyEditorWithPreview>()
        waitUntil("the split did not open the file") { editors().size == 2 }
        val editors = editors()
        val second = editors.single { it !== first }
        clickShow(second)
        assertEquals(Layout.SHOW_EDITOR_AND_PREVIEW, second.getLayout())
        assertEquals(Layout.SHOW_EDITOR, first.getLayout())
        // Closing one keeps the session for the other
        Disposer.dispose(second)
        assertNotNull(viewer.session(file))
    }

    fun testOpeningWithoutAnEditorShowsTheSelectedOnesPreview() {
        val file = alloyFile()
        val editor = openEditor(file)
        viewer.open(file, SHOW, record(file))
        assertEquals(Layout.SHOW_EDITOR_AND_PREVIEW, editor.getLayout())
    }

    fun testTheHeaderMovesTheCaretOfTheText() {
        val file = alloyFile()
        val editor = openEditor(file)
        clickShow(editor)
        editor.editor.caretModel.moveToOffset(0)
        viewer.navigateToOrigin(file)
        assertEquals(SHOW_FILE.indexOf("run show"), editor.editor.caretModel.offset)
    }

    fun testTheHeaderShowsTheTextOfAPreviewOnly() {
        val file = alloyFile()
        val editor = openEditor(file)
        clickShow(editor)
        editor.setLayout(Layout.SHOW_PREVIEW)
        editor.editor.caretModel.moveToOffset(0)
        viewer.navigateToOrigin(file)
        assertEquals(SHOW_FILE.indexOf("run show"), editor.editor.caretModel.offset)
        assertFalse(editor.getLayout() == Layout.SHOW_PREVIEW)
    }
}
