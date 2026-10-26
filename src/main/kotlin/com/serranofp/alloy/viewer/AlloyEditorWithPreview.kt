package com.serranofp.alloy.viewer

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.TextEditorWithPreview
import com.intellij.openapi.fileEditor.TextEditorWithPreviewProvider
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.serranofp.alloy.AlloyBundle
import com.serranofp.alloy.AlloyFileType
import com.serranofp.alloy.semantics.AlloyPositions
import java.beans.PropertyChangeListener
import javax.swing.JComponent

/** The instance preview of an editor of [file]: its viewer, which keeps [file]'s session while it is open. */
class AlloyInstancePreview(project: Project, private val file: VirtualFile) : UserDataHolderBase(), FileEditor {
    private val panel = AlloyInstancePanel(project, file, this)

    init {
        AlloyInstanceViewer.getInstance(project).attach(file, this)
    }

    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent = panel
    override fun getName(): String = AlloyBundle.message("viewer.preview.name")
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) {}
    override fun isModified() = false
    override fun isValid() = file.isValid
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun dispose() {}
}

/**
 * An Alloy file's editor: its text, and beside it the instance preview. It opens as text only while the file has no
 * session, whatever layout was chosen last; the layout buttons are in the editor tab, so that they do not cover the
 * preview's toolbar.
 */
class AlloyEditorWithPreview(editor: TextEditor, preview: AlloyInstancePreview) :
    TextEditorWithPreview(editor, preview, AlloyFileType.name, Layout.SHOW_EDITOR, false) {
    private val viewer = AlloyInstanceViewer.getInstance(editor.editor.project!!)
    private var built = false

    /** The platform builds the component with the layout chosen last in any Alloy editor. */
    override fun getComponent(): JComponent {
        val component = super.getComponent()
        if (!built) {
            built = true
            showTextWithoutSession()
        }
        return component
    }

    override val isShowActionsInTabs: Boolean get() = true

    /** The divider is where it was last left, in every Alloy file. */
    override val splitterProportionKey: String get() = "Alloy.EditorWithPreview.SplitterProportion"

    /** A saved layout with the preview shows an empty one when the file has no session, as after a restart. */
    override fun setState(state: FileEditorState) {
        super.setState(state)
        showTextWithoutSession()
    }

    private fun showTextWithoutSession() {
        val file = file ?: return
        if (viewer.session(file) == null && getLayout() != Layout.SHOW_EDITOR) setLayout(Layout.SHOW_EDITOR)
    }
}

/** Opens the Alloy files of the project, not the bundled library modules, with [AlloyEditorWithPreview]. */
class AlloyFileEditorProvider : TextEditorWithPreviewProvider(AlloyInstancePreviewProvider()), DumbAware {
    override fun getEditorTypeId() = EDITOR_TYPE_ID

    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR

    override fun createSplitEditor(firstEditor: TextEditor, secondEditor: FileEditor): FileEditor =
        AlloyEditorWithPreview(firstEditor, secondEditor as AlloyInstancePreview)

    companion object {
        /** It keys the saved state of the editor, so it does not change. */
        const val EDITOR_TYPE_ID = "alloy-editor-with-preview"
    }
}

private class AlloyInstancePreviewProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile): Boolean {
        if (!FileTypeRegistry.getInstance().isFileOfType(file, AlloyFileType)) return false
        val library = AlloyPositions.libraryRoot() ?: return true
        return !VfsUtilCore.isAncestor(library, file, false)
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor = AlloyInstancePreview(project, file)

    override fun getEditorTypeId() = "alloy-instance-preview"

    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
