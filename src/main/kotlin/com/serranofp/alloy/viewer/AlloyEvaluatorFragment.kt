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
 * Marks the Alloy file behind the evaluator's field, whose value gives the model it evaluates in, if any. Such a
 * fragment is one expression, not a module: the model file's completion and diagnostics skip it.
 */
val ALLOY_EVALUATOR_FRAGMENT: Key<() -> AlloySolutionModel?> = Key.create("ALLOY_EVALUATOR_FRAGMENT")

/** Whether this is the evaluator's fragment, or the completion copy of it. */
val PsiFile.isAlloyEvaluatorFragment: Boolean
    get() = getUserData(ALLOY_EVALUATOR_FRAGMENT) != null || originalFile.getUserData(ALLOY_EVALUATOR_FRAGMENT) != null

/** The document of a new evaluator fragment, whose expressions are evaluated in [model]. */
fun alloyEvaluatorDocument(project: Project, model: () -> AlloySolutionModel?): Document {
    val file = PsiFileFactory.getInstance(project).createFileFromText("evaluator.als", AlloyLanguage, "", true, false)
    file.putUserData(ALLOY_EVALUATOR_FRAGMENT, model)
    return PsiDocumentManager.getInstance(project).getDocument(file)!!
}

/** An expression does not parse as a module, so the fragment's syntax errors are the analyzer's to report. */
class AlloyEvaluatorErrorFilter : HighlightErrorFilter() {
    override fun shouldHighlightErrorElement(element: PsiErrorElement): Boolean = !element.containingFile.isAlloyEvaluatorFragment
}
