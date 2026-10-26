package com.serranofp.alloy.viewer

import com.intellij.openapi.fileEditor.TextEditor
import com.serranofp.alloy.semantics.AlloyPositions

class AlloyFileEditorProviderTest : AlloyEditorTestCase() {
    private val provider = AlloyFileEditorProvider()

    private val ordering get() = AlloyPositions.libraryRoot()!!.findFileByRelativePath("util/ordering.als")!!

    fun testAcceptsTheProjectsAlloyFilesOnly() {
        assertTrue(provider.accept(project, alloyFile()))
        assertFalse(provider.accept(project, ordering))
        assertFalse(provider.accept(project, myFixture.addFileToProject("notes.txt", "run show").virtualFile))
    }

    fun testAProjectFileOpensWithThePreview() {
        val editors = openEditors(alloyFile())
        assertInstanceOf(editors.single(), AlloyEditorWithPreview::class.java)
    }

    fun testALibraryModuleOpensAsText() {
        val editor = openEditors(ordering).single()
        assertInstanceOf(editor, TextEditor::class.java)
        assertFalse(editor is AlloyEditorWithPreview)
    }
}
