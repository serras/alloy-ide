package com.serranofp.alloy.semantics

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.xml.util.XmlStringUtil
import com.serranofp.alloy.AlloyBundle
import com.serranofp.alloy.psi.AlloyFile
import com.serranofp.alloy.psi.AlloyOpenDecl
import edu.mit.csail.sdg.alloy4.Err
import java.io.File

/** One analyzer error or warning; a null [range] is reported for the whole file. */
data class AlloyDiagnostic(val severity: HighlightSeverity, val message: String, val range: TextRange?)

/**
 * Shows the errors and warnings of the bundled analyzer (see [AlloyModelService]) for the current text. An
 * error in an opened project module is shown in that module's own file, and at the `open` that leads to it.
 */
class AlloyExternalAnnotator : ExternalAnnotator<AlloyFile, List<AlloyDiagnostic>>() {
    // The evaluator's fragment is an expression, not a module
    override fun collectInformation(file: PsiFile): AlloyFile? = (file as? AlloyFile)?.takeIf { it.isAnalyzable }

    // A write action cancels the parse, which then starts over; not so a blocking read action, which holds it off
    override fun doAnnotate(file: AlloyFile): List<AlloyDiagnostic> =
        ReadAction.nonBlocking<List<AlloyDiagnostic>> { if (file.isValid) diagnostics(file) else emptyList() }.executeSynchronously()

    override fun apply(file: PsiFile, diagnostics: List<AlloyDiagnostic>, holder: AnnotationHolder) {
        for ((severity, message, range) in diagnostics) {
            val tooltip = XmlStringUtil.wrapInHtml(XmlStringUtil.escapeString(message).replace("\n", "<br>"))
            val builder = holder.newAnnotation(severity, message).tooltip(tooltip)
            if (range == null) builder.fileLevel().create()
            else builder.range(clamp(range, file)).create()
        }
    }

    private fun clamp(range: TextRange, file: PsiFile): TextRange = range.intersection(file.textRange) ?: TextRange.from(file.textLength, 0)

    private fun diagnostics(file: AlloyFile): List<AlloyDiagnostic> {
        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return emptyList()
        val filename = AlloyPositions.filename(file.virtualFile)
        return when (val result = AlloyModelService.getInstance(file.project).model(file)) {
            is AlloyModelResult.Model -> result.warnings.filter { it.pos.filename == filename }.map {
                AlloyDiagnostic(HighlightSeverity.WARNING, it.msg, AlloyPositions.textRange(it.pos, document))
            }
            is AlloyModelResult.Errors -> result.errors.flatMap { error ->
                if (error.pos.filename == filename) {
                    listOf(AlloyDiagnostic(HighlightSeverity.ERROR, error.msg, AlloyPositions.textRange(error.pos, document)))
                } else {
                    errorsAtOpens(file, error, result.modules)
                }
            }
            // Already logged by the service; the native features keep working
            is AlloyModelResult.Unavailable -> emptyList()
        }
    }

    /**
     * The `open`s of [file] that lead to the module where [error] is, a project or a library module; the whole
     * file if there is none.
     */
    private fun errorsAtOpens(file: AlloyFile, error: Err, modules: AlloyModules): List<AlloyDiagnostic> {
        val service = AlloyModelService.getInstance(file.project)
        val atOpens = file.children.filterIsInstance<AlloyOpenDecl>().mapNotNull { open ->
            val name = open.qualifiedNameList.firstOrNull() ?: return@mapNotNull null
            val opened = service.openedFilename(file, open) ?: return@mapNotNull null
            if (!modules.reaches(opened, error.pos.filename)) return@mapNotNull null
            AlloyDiagnostic(HighlightSeverity.ERROR, openedModuleMessage(name.text, error), name.textRange)
        }
        return atOpens.ifEmpty { listOf(AlloyDiagnostic(HighlightSeverity.ERROR, error.msg, null)) }
    }

    private fun openedModuleMessage(module: String, error: Err): String {
        val where = error.pos.filename.takeIf { it.isNotEmpty() }?.let { " (${File(it).name}:${error.pos.y})" }.orEmpty()
        return AlloyBundle.message("annotator.opened.module.errors", module, where, error.msg)
    }
}
