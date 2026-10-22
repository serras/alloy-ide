package com.serranofp.alloy.simplifications

import com.intellij.codeInsight.intention.PsiElementBaseIntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.serranofp.alloy.AlloyBundle
import com.serranofp.alloy.AlloyFileType
import com.serranofp.alloy.psi.AlloyFile
import com.serranofp.alloy.semantics.AlloyModelResult
import com.serranofp.alloy.semantics.AlloyModelService
import com.serranofp.alloy.viewer.isAlloyEvaluatorFragment
import org.jetbrains.annotations.PropertyKey

/**
 * Offers the rewrites of [rules] between equivalent forms on the innermost written node around the caret
 * that one of them matches. It only uses the model already computed for the current text, on any thread, so
 * it is offered once highlighting has run, and never while the model has errors: an intention popup never
 * pays for a full analysis.
 */
abstract class AlloyRewriteIntention(
    private val rules: List<AlloyRewriteRule>,
    @PropertyKey(resourceBundle = "messages.AlloyBundle") private val family: String,
) : PsiElementBaseIntentionAction() {
    override fun getFamilyName(): String = AlloyBundle.message(family)

    override fun isAvailable(project: Project, editor: Editor, element: PsiElement): Boolean {
        val result = find(project, editor, element.containingFile) ?: return false
        text = result.fixName
        return true
    }

    override fun invoke(project: Project, editor: Editor, element: PsiElement) {
        val result = find(project, editor, element.containingFile) ?: return
        val documents = PsiDocumentManager.getInstance(project)
        val document = documents.getDocument(element.containingFile) ?: return
        document.replaceString(result.range.startOffset, result.range.endOffset, result.text)
        documents.commitDocument(document)
    }

    // The preview's file is a copy, which has no model of its own: the rewrite is found in the original
    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo {
        val original = file.originalFile
        val result = find(project, editor, original) ?: return IntentionPreviewInfo.EMPTY
        val document = PsiDocumentManager.getInstance(project).getDocument(original) ?: return IntentionPreviewInfo.EMPTY
        return IntentionPreviewInfo.CustomDiff(AlloyFileType, document.getText(result.range), result.text)
    }

    private fun find(project: Project, editor: Editor, psiFile: PsiFile?): AlloyRewriteResult? {
        val file = (psiFile as? AlloyFile)?.takeIf { it.virtualFile != null && !it.isAlloyEvaluatorFragment } ?: return null
        val model = AlloyModelService.getInstance(project).cachedModel(file) as? AlloyModelResult.Model ?: return null
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return null
        if (!PsiDocumentManager.getInstance(project).isCommitted(document)) return null
        val index = AlloyRewriteIndex.of(model, file, document)
        for (entry in index.around(editor.caretModel.offset)) {
            for (rule in rules) entry.context.rewrite(rule, entry.node)?.let { return it }
        }
        return null
    }
}
