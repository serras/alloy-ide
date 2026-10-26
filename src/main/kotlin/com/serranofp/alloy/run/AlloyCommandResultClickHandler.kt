package com.serranofp.alloy.run

import com.intellij.codeInsight.hints.declarative.InlayActionHandler
import com.intellij.codeInsight.hints.declarative.InlayActionPayload
import com.intellij.codeInsight.hints.declarative.StringInlayActionPayload
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.serranofp.alloy.viewer.AlloyInstanceViewer

/**
 * A click on a result hint: stops a running analysis, or shows an instance or counterexample in the instance
 * preview of the editor clicked, even when the result is outdated.
 */
class AlloyCommandResultClickHandler : InlayActionHandler {
    override fun handleClick(e: EditorMouseEvent, payload: InlayActionPayload) = click(e.editor, payload)

    fun click(editor: Editor, payload: InlayActionPayload) {
        val project = editor.project ?: return
        val ref = (payload as? StringInlayActionPayload)?.text?.let(AlloyCommandResultHintsProvider.Codec::decode) ?: return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val result = AlloyCommandResults.getInstance(project).get(file, ref) ?: return
        if (result.isRunning) {
            AlloyRunService.getInstance(project).activeExecution
                ?.takeIf { it.file == file && it.command == ref }
                ?.stop()
            return
        }
        if (result.link is CommandLink.Solution) AlloyInstanceViewer.getInstance(project).open(file, ref, result, editor)
    }

    companion object {
        const val HANDLER_ID = "alloy.command.result"
    }
}
