package com.serranofp.alloy.run

import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.hints.declarative.InlayActionHandler
import com.intellij.codeInsight.hints.declarative.InlayActionPayload
import com.intellij.codeInsight.hints.declarative.StringInlayActionPayload
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project

/**
 * A click on a result hint: stops a running analysis, or opens the result's instance, counterexample or
 * core through the server, even when the result is outdated.
 */
class AlloyCommandResultClickHandler(
    private val connection: (Project) -> AlloyServerConnection? = LspAlloyServerConnection::find,
) : InlayActionHandler {
    override fun handleClick(e: EditorMouseEvent, payload: InlayActionPayload) = click(e.editor, payload)

    fun click(editor: Editor, payload: InlayActionPayload) {
        val project = editor.project ?: return
        val ref = (payload as? StringInlayActionPayload)?.text?.let(AlloyCommandResultHintsProvider::decode) ?: return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val result = AlloyCommandResults.getInstance(project).get(file, ref) ?: return
        if (result.state == CommandState.Running) {
            AlloyRunService.getInstance(project).activeExecution
                ?.takeIf { it.file == file && it.command == ref }
                ?.stop()
            return
        }
        val link = result.link ?: return
        val server = connection(project)
        if (server == null) {
            HintManager.getInstance().showErrorHint(editor, AlloyRunService.SERVER_UNAVAILABLE_MESSAGE)
            return
        }
        server.openModel(link)
    }

    companion object {
        const val HANDLER_ID = "alloy.command.result"
    }
}
