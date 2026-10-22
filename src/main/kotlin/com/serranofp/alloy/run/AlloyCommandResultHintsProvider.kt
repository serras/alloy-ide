package com.serranofp.alloy.run

import com.intellij.codeInsight.hints.declarative.EndOfLinePosition
import com.intellij.codeInsight.hints.declarative.HintColorKind
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayActionData
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.codeInsight.hints.declarative.StringInlayActionPayload
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.serranofp.alloy.psi.AlloyCommandDecl
import com.serranofp.alloy.psi.AlloyFile

/**
 * The result of each executed command at the end of the command's first line. It only reads
 * [AlloyCommandResults], which restarts the daemon (and so this provider) whenever a result changes.
 */
class AlloyCommandResultHintsProvider : InlayHintsProvider {
    override fun createCollector(file: PsiFile, editor: Editor): InlayHintsCollector? {
        val virtualFile = file.virtualFile ?: return null
        val results = AlloyCommandResults.getInstance(file.project)
        if (results.results(virtualFile).isEmpty()) return null
        return Collector(results, virtualFile, editor)
    }

    private class Collector(
        private val results: AlloyCommandResults,
        private val file: VirtualFile,
        private val editor: Editor,
    ) : SharedBypassCollector {
        override fun collectFromElement(element: PsiElement, sink: InlayTreeSink) {
            if (element !is AlloyCommandDecl || element.parent !is AlloyFile) return
            val ref = AlloyCommands.ref(element)
            val result = results.get(file, ref) ?: return
            val document = editor.document
            val outdated = results.outdated(result, document)
            val line = document.getLineNumber(element.firstChild.textRange.startOffset)
            val format = if (outdated != null) HintFormat.default.withColorKind(HintColorKind.TextWithoutBackground) else HintFormat.default
            val text = if (outdated != null) "${result.summary}$OUTDATED_SUFFIX" else result.summary
            val action = if (result.link != null || result.state == CommandState.Running) {
                InlayActionData(StringInlayActionPayload(Codec.encode(ref)), AlloyCommandResultClickHandler.HANDLER_ID)
            } else {
                null
            }
            sink.addPresentation(EndOfLinePosition(line), emptyList(), AlloyCommandResultTooltip.html(result, outdated), format) {
                text(text, action)
            }
        }
    }

    companion object {
        const val PROVIDER_ID = "alloy.command.results"
        const val OUTDATED_SUFFIX = " (outdated)"
    }

    object Codec {
        /** The click payload: `<index>:<label>`, with an empty label for unlabelled commands. */
        fun encode(ref: AlloyCommandRef): String = "${ref.index}:${ref.label.orEmpty()}"

        fun decode(payload: String): AlloyCommandRef? {
            val index = payload.substringBefore(':', "").toIntOrNull() ?: return null
            return AlloyCommandRef(payload.substringAfter(':').ifEmpty { null }, index)
        }
    }
}
