package com.serranofp.alloy.viewer

import com.intellij.codeInsight.highlighting.HighlightErrorFilter
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.serranofp.alloy.AlloyLanguage

/**
 * Marks the (light) virtual file behind the evaluator's field, whose value gives the model it evaluates in, if
 * any. Such a fragment is one expression, not a module: the model file's completion and diagnostics skip it. The
 * mark is on the virtual file, not on the PSI file, because the PSI is rebuilt when the file is reparsed.
 */
val ALLOY_EVALUATOR_FRAGMENT: Key<() -> AlloySolutionModel?> = Key.create("ALLOY_EVALUATOR_FRAGMENT")

/** The model the evaluator's fragment, or the completion copy of it, evaluates in; null for any other file. */
val PsiFile.alloyEvaluatorModel: (() -> AlloySolutionModel?)?
    get() = viewProvider.virtualFile.getUserData(ALLOY_EVALUATOR_FRAGMENT)
        ?: originalFile.takeIf { it !== this }?.viewProvider?.virtualFile?.getUserData(ALLOY_EVALUATOR_FRAGMENT)

/** Whether this is the evaluator's fragment, or the completion copy of it. */
val PsiFile.isAlloyEvaluatorFragment: Boolean
    get() = alloyEvaluatorModel != null

/** The document of a new evaluator fragment, whose expressions are evaluated in [model]. */
fun alloyEvaluatorDocument(project: Project, model: () -> AlloySolutionModel?): Document {
    val file = PsiFileFactory.getInstance(project).createFileFromText("evaluator.als", AlloyLanguage, "", true, false)
    file.viewProvider.virtualFile.putUserData(ALLOY_EVALUATOR_FRAGMENT, model)
    return PsiDocumentManager.getInstance(project).getDocument(file)!!
}

/** An expression does not parse as a module, so the fragment's syntax errors are the analyzer's to report. */
class AlloyEvaluatorErrorFilter : HighlightErrorFilter() {
    override fun shouldHighlightErrorElement(element: PsiErrorElement): Boolean = !element.containingFile.isAlloyEvaluatorFragment
}
