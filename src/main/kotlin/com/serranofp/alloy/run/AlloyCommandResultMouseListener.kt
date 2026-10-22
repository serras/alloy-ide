package com.serranofp.alloy.run

import com.intellij.codeInsight.hints.declarative.StringInlayActionPayload
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseEventArea
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.PsiDocumentManager
import com.serranofp.alloy.AlloyFileType
import javax.swing.SwingUtilities

/**
 * The platform runs the action of a declarative hint only on Ctrl/Cmd-click; result hints also act on a plain
 * click, through [AlloyCommandResultClickHandler]. Ctrl/Cmd-clicks are left to the platform.
 */
class AlloyCommandResultMouseListener(
    private val handler: AlloyCommandResultClickHandler = AlloyCommandResultClickHandler(),
) : EditorMouseListener {
    override fun mouseClicked(e: EditorMouseEvent) {
        if (e.isConsumed || e.area != EditorMouseEventArea.EDITING_AREA) return
        val mouse = e.mouseEvent
        if (!SwingUtilities.isLeftMouseButton(mouse) || mouse.isControlDown || mouse.isMetaDown) return
        val inlay = e.inlay ?: return
        if (inlay.placement != Inlay.Placement.AFTER_LINE_END || !isDeclarativeHint(inlay)) return
        val editor = e.editor
        val project = editor.project ?: return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        if (file.fileType != AlloyFileType) return
        // Right after an edit, the offsets of the PSI may not be those of the document
        val documents = PsiDocumentManager.getInstance(project)
        if (!documents.isCommitted(editor.document)) return
        val psiFile = documents.getPsiFile(editor.document) ?: return
        val line = editor.document.getLineNumber(inlay.offset)
        val command = AlloyCommands.commands(psiFile)
            .firstOrNull { editor.document.getLineNumber(it.firstChild.textRange.startOffset) == line } ?: return
        val ref = AlloyCommands.ref(command)
        val result = AlloyCommandResults.getInstance(project).get(file, ref) ?: return
        if (result.link == null && !result.isRunning) return
        e.consume()
        handler.click(editor, StringInlayActionPayload(AlloyCommandResultHintsProvider.Codec.encode(ref)))
    }

    /**
     * Whether [inlay] is a declarative hint, as result hints are, rather than another end-of-line inlay such as
     * Git's inline blame. Its renderer is internal API, so only its package is checked.
     */
    private fun isDeclarativeHint(inlay: Inlay<*>): Boolean = inlay.renderer.javaClass.name.startsWith(DECLARATIVE_HINTS)

    private companion object {
        const val DECLARATIVE_HINTS = "com.intellij.codeInsight.hints.declarative."
    }
}
