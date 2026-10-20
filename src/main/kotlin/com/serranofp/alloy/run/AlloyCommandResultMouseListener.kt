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
        if (inlay.placement != Inlay.Placement.AFTER_LINE_END) return
        val editor = e.editor
        val project = editor.project ?: return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        if (file.fileType != AlloyFileType) return
        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return
        val line = editor.document.getLineNumber(inlay.offset)
        val command = AlloyCommands.commands(psiFile)
            .firstOrNull { editor.document.getLineNumber(it.firstChild.textRange.startOffset) == line } ?: return
        val ref = AlloyCommands.ref(command)
        val result = AlloyCommandResults.getInstance(project).get(file, ref) ?: return
        if (result.link == null && result.state != CommandState.Running) return
        e.consume()
        handler.click(editor, StringInlayActionPayload(AlloyCommandResultHintsProvider.encode(ref)))
    }
}
